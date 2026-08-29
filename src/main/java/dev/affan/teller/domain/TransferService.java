package dev.affan.teller.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final EntryRepository entries;
    private final PolicyStore policies;
    private final RuleStore rules;
    private final DecisionService decisionService;
    private final AuditService auditService;
    private final Clock clock;

    public TransferService(
            AccountRepository accounts,
            TransferRepository transfers,
            EntryRepository entries,
            PolicyStore policies,
            RuleStore rules,
            DecisionService decisionService,
            AuditService auditService,
            Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
        this.policies = policies;
        this.rules = rules;
        this.decisionService = decisionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Account createAccount(String currency) {
        Account account = accounts.save(Account.open(UUID.randomUUID(), currency, clock.instant()));
        auditService.append(
                AuditEventType.ACCOUNT_CREATED,
                "ACCOUNT",
                account.getId(),
                Map.of("currency", account.getCurrency()));
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("account", id));
    }

    @Transactional
    public Account deposit(UUID id, Money money) {
        Account account = lockAccount(id);
        account.deposit(money);
        auditService.append(
                AuditEventType.ACCOUNT_DEPOSITED,
                "ACCOUNT",
                account.getId(),
                Map.of("amountMinor", money.minorUnits(), "currency", money.currency()));
        return account;
    }

    @Transactional
    public Transfer createTransfer(CreateTransferCommand command) {
        Money money = command.money();
        List<Account> locked = lockAccounts(command.fromAccountId(), command.toAccountId());
        Account source = accountWithId(locked, command.fromAccountId());
        Account destination = accountWithId(locked, command.toAccountId());
        requireTransferCurrencies(source, destination, money);

        Policy policy = policies.findActivePolicy()
                .orElseThrow(() -> new ConflictException("no active policy is configured"));
        Map<Long, Long> velocityCounts = velocityCounts(policy, source.getId(), clock.instant());
        boolean reservationAvailable = source.getAvailableBalanceMinor() >= money.minorUnits();
        DecisionOutcome outcome = decisionService.evaluateTransfer(
                new EvaluateTransferPolicyCommand(
                        policy.getId(),
                        command.initiatedBy(),
                        source.getId(),
                        destination.getId(),
                        money,
                        velocityCounts),
                reservationAvailable);
        Transfer transfer = Transfer.pending(
                UUID.randomUUID(),
                command.idempotencyKey(),
                source.getId(),
                destination.getId(),
                money,
                outcome.decision().getId(),
                clock.instant());
        transfer = transfers.save(transfer);

        switch (outcome.decision().getEffect()) {
            case DENY -> transfer.deny("POLICY_DENIED");
            case ALLOW -> postAuthorized(transfer, source, destination, money);
            case REQUIRE_APPROVAL -> holdOrDeny(transfer, source, money, outcome);
        }
        auditTransfer(transfer);
        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer getTransfer(UUID id) {
        return transfers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("transfer", id));
    }

    @Transactional
    public Transfer reverse(UUID id, String reasonCode) {
        Transfer transfer = transfers.findLockedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("transfer", id));
        Money money = Money.of(transfer.getAmountMinor(), transfer.getCurrency());
        List<Account> locked = lockAccounts(transfer.getFromAccountId(), transfer.getToAccountId());
        Account source = accountWithId(locked, transfer.getFromAccountId());
        Account destination = accountWithId(locked, transfer.getToAccountId());
        if (transfer.getState() == TransferState.HELD) {
            source.release(money);
        } else if (transfer.getState() == TransferState.POSTED) {
            destination.reversePostedCredit(money);
            source.reversePostedDebit(money);
            writeEntries(transfer, destination, source, money);
        }
        transfer.reverse(reasonCode, clock.instant());
        auditService.append(
                AuditEventType.TRANSFER_REVERSED,
                "TRANSFER",
                transfer.getId(),
                Map.of("reasonCode", transfer.getReasonCode()));
        return transfer;
    }

    private void postAuthorized(Transfer transfer, Account source, Account destination, Money money) {
        if (source.getAvailableBalanceMinor() < money.minorUnits()) {
            transfer.deny("INSUFFICIENT_FUNDS");
            return;
        }
        transfer.authorize();
        source.postDebit(money);
        destination.postCredit(money);
        transfer.post(clock.instant());
        writeEntries(transfer, source, destination, money);
    }

    private void holdOrDeny(Transfer transfer, Account source, Money money, DecisionOutcome outcome) {
        if (source.getAvailableBalanceMinor() < money.minorUnits()) {
            transfer.deny("INSUFFICIENT_FUNDS");
            return;
        }
        source.reserve(money);
        transfer.hold(outcome.approval().getId());
    }

    private Map<Long, Long> velocityCounts(Policy policy, UUID sourceAccountId, Instant now) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        rules.findRulesByPolicyId(policy.getId()).stream()
                .map(Rule::getVelocityWindowSeconds)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(window -> counts.put(
                        window,
                        transfers.countByFromAccountIdAndCreatedAtGreaterThanEqualAndStateNot(
                                sourceAccountId,
                                now.minusSeconds(window),
                                TransferState.DENIED)));
        return counts;
    }

    private void writeEntries(
            Transfer transfer,
            Account debitAccount,
            Account creditAccount,
            Money money) {
        Instant now = clock.instant();
        entries.saveAll(List.of(
                Entry.create(
                        UUID.randomUUID(),
                        transfer.getId(),
                        debitAccount.getId(),
                        EntryDirection.DEBIT,
                        money.minorUnits(),
                        now),
                Entry.create(
                        UUID.randomUUID(),
                        transfer.getId(),
                        creditAccount.getId(),
                        EntryDirection.CREDIT,
                        money.minorUnits(),
                        now)));
    }

    private List<Account> lockAccounts(UUID firstId, UUID secondId) {
        if (firstId.equals(secondId)) {
            throw new IllegalArgumentException("transfer accounts must be different");
        }
        return List.of(firstId, secondId).stream()
                .sorted(Comparator.naturalOrder())
                .map(this::lockAccount)
                .toList();
    }

    private Account lockAccount(UUID id) {
        return accounts.findLockedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("account", id));
    }

    private static Account accountWithId(List<Account> accounts, UUID id) {
        return accounts.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void requireTransferCurrencies(Account source, Account destination, Money money) {
        money.requireSameCurrency(source.getCurrency());
        money.requireSameCurrency(destination.getCurrency());
    }

    private void auditTransfer(Transfer transfer) {
        AuditEventType type = switch (transfer.getState()) {
            case POSTED -> AuditEventType.TRANSFER_POSTED;
            case HELD -> AuditEventType.TRANSFER_HELD;
            case DENIED -> AuditEventType.TRANSFER_DENIED;
            default -> AuditEventType.TRANSFER_CREATED;
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("decisionId", transfer.getDecisionId());
        details.put("matchedApprovalId", transfer.getApprovalId());
        details.put("state", transfer.getState());
        details.put("reasonCode", transfer.getReasonCode());
        details.put("amountMinor", transfer.getAmountMinor());
        details.put("currency", transfer.getCurrency());
        auditService.append(type, "TRANSFER", transfer.getId(), details);
    }
}
