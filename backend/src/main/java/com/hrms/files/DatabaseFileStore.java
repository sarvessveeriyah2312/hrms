package com.hrms.files;

import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Files kept in PostgreSQL (row-level security keeps each company's files apart). Only PDF, JPEG and PNG,
 * checked by their first bytes rather than the name or the browser's content type; at most 5 MB.
 */
@Component
class DatabaseFileStore implements FileStore {

    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Map<String, byte[]> SIGNATURES = Map.of(
        "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII),
        "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
        "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    private final JdbcClient jdbc;

    DatabaseFileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    FileMeta store(UUID owner, String originalName, byte[] data) {
        if (data.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "That file is empty.");
        if (data.length > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files can be up to 5 MB.");
        String type = detect(data).orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE",
            "Upload a PDF, JPG or PNG."));
        String name = cleanName(originalName, type);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into file_object (id, tenant_id, owner_user_id, name, content_type, size_bytes, sha256, data)
                values (:id, :tenant, :owner, :name, :type, :size, :sha, :data)""")
            .param("id", id).param("tenant", TenantContext.require()).param("owner", owner).param("name", name)
            .param("type", type).param("size", data.length).param("sha", sha256(data)).param("data", data).update();
        return new FileMeta(id, owner, name, type, data.length);
    }

    @Override
    public Optional<FileMeta> meta(UUID id) {
        return jdbc.sql("select id, owner_user_id, name, content_type, size_bytes from file_object where id = :id")
            .param("id", id).query(FileMeta.class).optional();
    }

    @Override
    public Optional<StoredFile> read(UUID id) {
        return meta(id).map(m -> new StoredFile(m,
            jdbc.sql("select data from file_object where id = :id").param("id", id).query(byte[].class).single()));
    }

    static Optional<String> detect(byte[] data) {
        return SIGNATURES.entrySet().stream().filter(e -> startsWith(data, e.getValue())).map(Map.Entry::getKey).findFirst();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    /** Keeps a readable name without path parts or odd characters, and with the extension matching the content. */
    static String cleanName(String original, String type) {
        String base = original == null ? "document" : original.replaceAll(".*[/\\\\]", "");
        base = base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replaceAll("[^\\p{L}\\p{N} ._()-]", "_").trim();
        if (base.isEmpty()) base = "document";
        if (base.length() > 80) base = base.substring(0, 80);
        String ext = switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        return base + ext;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
