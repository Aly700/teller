package dev.affan.teller.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferSettlementService implements ApprovalLifecycleListener {

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final AuditService auditService;
    private final Clock clock;

    public TransferSettlementService(
            TransferRepository transfers,
            AccountRepository accounts,
            EntryRepository entries,
            AuditService auditService,
            Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.entries = entries;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void approved(UUID decisionId) {
        transfers.findLockedByDecisionId(decisionId).ifPresent(transfer -> {
            if (transfer.getState() != TransferState.HELD) {
                throw new InvalidApprovalTransitionException("linked transfer is no longer held");
            }
            Money money = Money.of(transfer.getAmountMinor(), transfer.getCurrency());
            List<Account> locked = lockAccounts(transfer);
            Account source = accountWithId(locked, transfer.getFromAccountId());
            Account destination = accountWithId(locked, transfer.getToAccountId());
            source.postReservedDebit(money);
            destination.postCredit(money);
            transfer.post(clock.instant());
            writeEntries(transfer, source, destination, money);
            auditService.append(
                    AuditEventType.TRANSFER_POSTED,
                    "TRANSFER",
                    transfer.getId(),
                    Map.of("decisionId", decisionId, "approvalId", transfer.getApprovalId()));
        });
    }

    @Override
    @Transactional
    public void rejected(UUID decisionId, String reasonCode) {
        transfers.findLockedByDecisionId(decisionId).ifPresent(transfer -> {
            if (transfer.getState() != TransferState.HELD) {
                return;
            }
            Money money = Money.of(transfer.getAmountMinor(), transfer.getCurrency());
            Account source = accounts.findLockedById(transfer.getFromAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("account", transfer.getFromAccountId()));
            source.release(money);
            transfer.reverse(reasonCode, clock.instant());
            auditService.append(
                    AuditEventType.TRANSFER_REVERSED,
                    "TRANSFER",
                    transfer.getId(),
                    Map.of(
                            "decisionId", decisionId,
                            "approvalId", transfer.getApprovalId(),
                            "reasonCode", reasonCode));
        });
    }

    private List<Account> lockAccounts(Transfer transfer) {
        return List.of(transfer.getFromAccountId(), transfer.getToAccountId()).stream()
                .sorted(Comparator.naturalOrder())
                .map(id -> accounts.findLockedById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("account", id)))
                .toList();
    }

    private void writeEntries(
            Transfer transfer,
            Account source,
            Account destination,
            Money money) {
        Instant now = clock.instant();
        entries.saveAll(List.of(
                Entry.create(
                        UUID.randomUUID(),
                        transfer.getId(),
                        source.getId(),
                        EntryDirection.DEBIT,
                        money.minorUnits(),
                        now),
                Entry.create(
                        UUID.randomUUID(),
                        transfer.getId(),
                        destination.getId(),
                        EntryDirection.CREDIT,
                        money.minorUnits(),
                        now)));
    }

    private static Account accountWithId(List<Account> accounts, UUID id) {
        return accounts.stream().filter(account -> account.getId().equals(id)).findFirst().orElseThrow();
    }
}
