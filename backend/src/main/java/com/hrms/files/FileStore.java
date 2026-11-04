package com.hrms.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Stores uploaded documents (receipts, medical certificates). The database implementation is for now;
 * an S3/MinIO one can replace it behind this interface. Access checks belong to the module that owns the
 * document (e.g. leave decides who may see a certificate), so reads here are by id only.
 */
public interface FileStore {

    record FileMeta(UUID id, UUID ownerUserId, String name, String contentType, int sizeBytes) {
    }

    record StoredFile(FileMeta meta, byte[] data) {
    }

    Optional<FileMeta> meta(UUID id);

    Optional<StoredFile> read(UUID id);
}
