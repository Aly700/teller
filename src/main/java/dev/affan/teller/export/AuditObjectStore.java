package dev.affan.teller.export;

public interface AuditObjectStore {
    void put(String objectKey, byte[] jsonLines);
}
