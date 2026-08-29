package dev.affan.teller.domain;

import java.util.Optional;
import java.util.UUID;

public interface PolicyStore {

    Optional<Policy> findPolicyById(UUID id);

    Optional<Policy> findActivePolicy();
}
