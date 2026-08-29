package dev.affan.teller.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferStore {

    Transfer storeTransfer(Transfer transfer);

    Optional<Transfer> findTransferById(UUID id);

    Optional<Transfer> findLockedTransferById(UUID id);

    Optional<Transfer> findLockedTransferByDecisionId(UUID decisionId);

    long countTransfers(UUID fromAccountId, Instant createdAt, TransferState excludedState);

    List<Transfer> findAllTransfers();
}
