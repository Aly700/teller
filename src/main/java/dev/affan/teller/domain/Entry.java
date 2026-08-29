package dev.affan.teller.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "entries")
public class Entry {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Entry() {
    }

    private Entry(
            UUID id,
            UUID transferId,
            UUID accountId,
            EntryDirection direction,
            long amountMinor,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.direction = Objects.requireNonNull(direction, "direction");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("entry amountMinor must be positive");
        }
        this.amountMinor = amountMinor;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Entry create(
            UUID id,
            UUID transferId,
            UUID accountId,
            EntryDirection direction,
            long amountMinor,
            Instant createdAt) {
        return new Entry(id, transferId, accountId, direction, amountMinor, createdAt);
    }

    public long signedAmountMinor() {
        return direction.signed(amountMinor);
    }

    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public UUID getAccountId() { return accountId; }
    public EntryDirection getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
