package dev.affan.teller.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntryRepository extends JpaRepository<Entry, UUID> {
    List<Entry> findByTransferIdOrderByCreatedAtAscIdAsc(UUID transferId);
}
