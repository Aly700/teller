package dev.affan.teller.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Transfer> findLockedByDecisionId(UUID decisionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select transfer from Transfer transfer where transfer.id = :id")
    Optional<Transfer> findLockedById(@Param("id") UUID id);

    long countByFromAccountIdAndCreatedAtGreaterThanEqualAndStateNot(
            UUID fromAccountId,
            Instant createdAt,
            TransferState state);
}
