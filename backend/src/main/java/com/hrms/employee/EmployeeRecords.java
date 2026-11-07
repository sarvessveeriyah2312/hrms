package com.hrms.employee;

import com.hrms.files.FileStore;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents (contracts, IDs, certificates…) and assets (laptop, phone, access card…) kept on an employee's record.
 * Both are seen by the person and by people whose employee:manage scope covers them. People may add their own
 * documents and remove ones they added; HR adds, removes and records assets.
 */
@Service
class EmployeeRecords {

    static final Set<String> CATEGORIES = Set.of("CONTRACT", "IDENTITY", "CERTIFICATE", "LETTER", "MEDICAL", "OTHER");
    static final Set<String> ASSET_KINDS = Set.of("LAPTOP", "PHONE", "ACCESS_CARD", "VEHICLE", "UNIFORM", "OTHER");

    record DocumentInput(UUID fileId, String category, String description) {
    }

    record Document(UUID id, UUID employeeId, String category, String description, FileStore.FileMeta file, String uploadedBy,
                    Instant createdAt, boolean canDelete) {
    }

    record AssetInput(String kind, String name, String tag, LocalDate issuedOn, LocalDate returnedOn, String note) {
    }

    record Asset(UUID id, UUID employeeId, String kind, String name, String tag, LocalDate issuedOn, LocalDate returnedOn,
                 String note) {
    }

    private record DocRow(UUID id, UUID employeeId, UUID fileId, String category, String description, UUID uploadedBy,
                          String uploaderName, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final EmployeeAccess access;
    private final FileStore files;
    private final AuditLog audit;

    EmployeeRecords(JdbcClient jdbc, EmployeeAccess access, FileStore files, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.files = files;
        this.audit = audit;
    }

    // ---------- documents ----------

    @Transactional(readOnly = true)
    List<Document> documents(UUID me, UUID user, UUID employeeId) {
        access.requirePrivate(me, employeeId);
        boolean manage = access.canManage(me, employeeId);
        return jdbc.sql("""
                select d.id, d.employee_id, d.file_id, d.category, d.description, d.uploaded_by,
                       coalesce(ue.full_name, u.email::text) as uploader_name, d.created_at
                from employee_document d
                left join app_user u on u.id = d.uploaded_by
                left join employee ue on ue.user_id = d.uploaded_by
                where d.employee_id = :emp order by d.created_at desc""")
            .param("emp", employeeId).query(DocRow.class).list().stream()
            .map(r -> new Document(r.id(), r.employeeId(), r.category(), r.description(), files.meta(r.fileId()).orElse(null),
                r.uploaderName(), r.createdAt(), manage || user.equals(r.uploadedBy())))
            .toList();
    }

    @Transactional
    Document addDocument(UUID me, UUID user, UUID employeeId, DocumentInput in) {
        access.requirePrivate(me, employeeId);
        if (!employeeId.equals(me)) access.requireManage(me, employeeId);
        if (in == null || in.fileId() == null) throw bad("FILE_REQUIRED", "Upload the document first.");
        FileStore.FileMeta meta = files.meta(in.fileId()).filter(m -> m.ownerUserId().equals(user))
            .orElseThrow(() -> bad("FILE_NOT_FOUND", "Upload the document first."));
        String category = in.category() == null ? "OTHER" : in.category();
        if (!CATEGORIES.contains(category)) throw bad("BAD_CATEGORY", "Choose what kind of document it is.");
        String description = trim(in.description(), 200, "DESCRIPTION_TOO_LONG", "Keep the description under 200 characters.");
        Boolean used = jdbc.sql("select exists (select 1 from employee_document where file_id = :file)").param("file", meta.id())
            .query(Boolean.class).single();
        if (used) throw bad("FILE_IN_USE", "That file is already on a record. Upload it again to add it here too.");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into employee_document (id, tenant_id, employee_id, file_id, category, description, uploaded_by)
                values (:id, :tenant, :emp, :file, :category, :description, :user)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("file", meta.id())
            .param("category", category).param("description", description).param("user", user).update();
        audit.record(user, "EMPLOYEE_DOCUMENT_ADDED", "employee", employeeId, Map.of("document", id, "category", category, "name", meta.name()));
        return documents(me, user, employeeId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    FileStore.StoredFile documentFile(UUID me, UUID employeeId, UUID documentId) {
        access.requirePrivate(me, employeeId);
        UUID file = jdbc.sql("select file_id from employee_document where id = :id and employee_id = :emp")
            .param("id", documentId).param("emp", employeeId).query(UUID.class).optional().orElseThrow(EmployeeRecords::docNotFound);
        return files.read(file).orElseThrow(EmployeeRecords::docNotFound);
    }

    @Transactional
    void deleteDocument(UUID me, UUID user, UUID employeeId, UUID documentId) {
        access.requirePrivate(me, employeeId);
        record Owner(UUID uploadedBy) {
        }
        Owner owner = jdbc.sql("select uploaded_by from employee_document where id = :id and employee_id = :emp")
            .param("id", documentId).param("emp", employeeId).query(Owner.class).optional().orElseThrow(EmployeeRecords::docNotFound);
        if (!access.canManage(me, employeeId) && !user.equals(owner.uploadedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_DELETE", "Only HR can remove a document HR added.");
        }
        jdbc.sql("delete from employee_document where id = :id").param("id", documentId).update();
        audit.record(user, "EMPLOYEE_DOCUMENT_REMOVED", "employee", employeeId, Map.of("document", documentId));
    }

    // ---------- assets ----------

    @Transactional(readOnly = true)
    List<Asset> assets(UUID me, UUID employeeId) {
        access.requirePrivate(me, employeeId);
        return jdbc.sql("""
                select id, employee_id, kind, name, tag, issued_on, returned_on, note from employee_asset
                where employee_id = :emp order by (returned_on is null) desc, issued_on desc""")
            .param("emp", employeeId).query(Asset.class).list();
    }

    @Transactional
    Asset addAsset(UUID me, UUID user, UUID employeeId, AssetInput in) {
        access.requireManage(me, employeeId);
        AssetInput a = checkAsset(in);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into employee_asset (id, tenant_id, employee_id, kind, name, tag, issued_on, returned_on, note, updated_by)
                values (:id, :tenant, :emp, :kind, :name, :tag, :issued, :returned, :note, :user)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("kind", a.kind())
            .param("name", a.name()).param("tag", a.tag()).param("issued", a.issuedOn()).param("returned", a.returnedOn())
            .param("note", a.note()).param("user", user).update();
        audit.record(user, "EMPLOYEE_ASSET_ISSUED", "employee", employeeId, Map.of("asset", id, "name", a.name()));
        return asset(id);
    }

    @Transactional
    Asset updateAsset(UUID me, UUID user, UUID employeeId, UUID assetId, AssetInput in) {
        access.requireManage(me, employeeId);
        AssetInput a = checkAsset(in);
        int n = jdbc.sql("""
                update employee_asset set kind = :kind, name = :name, tag = :tag, issued_on = :issued, returned_on = :returned,
                       note = :note, updated_by = :user, version = version + 1
                where id = :id and employee_id = :emp""")
            .param("kind", a.kind()).param("name", a.name()).param("tag", a.tag()).param("issued", a.issuedOn())
            .param("returned", a.returnedOn()).param("note", a.note()).param("user", user).param("id", assetId).param("emp", employeeId)
            .update();
        if (n == 0) throw assetNotFound();
        audit.record(user, a.returnedOn() == null ? "EMPLOYEE_ASSET_UPDATED" : "EMPLOYEE_ASSET_RETURNED", "employee", employeeId,
            Map.of("asset", assetId, "name", a.name()));
        return asset(assetId);
    }

    @Transactional
    void deleteAsset(UUID me, UUID user, UUID employeeId, UUID assetId) {
        access.requireManage(me, employeeId);
        int n = jdbc.sql("delete from employee_asset where id = :id and employee_id = :emp").param("id", assetId).param("emp", employeeId).update();
        if (n == 0) throw assetNotFound();
        audit.record(user, "EMPLOYEE_ASSET_DELETED", "employee", employeeId, Map.of("asset", assetId));
    }

    private Asset asset(UUID id) {
        return jdbc.sql("select id, employee_id, kind, name, tag, issued_on, returned_on, note from employee_asset where id = :id")
            .param("id", id).query(Asset.class).single();
    }

    private static AssetInput checkAsset(AssetInput in) {
        if (in == null) throw bad("ASSET_REQUIRED", "Fill in the asset.");
        String kind = in.kind() == null ? "OTHER" : in.kind();
        if (!ASSET_KINDS.contains(kind)) throw bad("BAD_ASSET_KIND", "Choose what kind of asset it is.");
        String name = trim(in.name(), 120, "NAME_TOO_LONG", "Keep the name under 120 characters.");
        if (name == null) throw bad("NAME_REQUIRED", "Name the asset, like “MacBook Air 13”.");
        String tag = trim(in.tag(), 60, "TAG_TOO_LONG", "Keep the serial or tag under 60 characters.");
        String note = trim(in.note(), 300, "NOTE_TOO_LONG", "Keep the note under 300 characters.");
        if (in.issuedOn() == null) throw bad("ISSUED_REQUIRED", "Give the date it was issued.");
        if (in.issuedOn().isAfter(LocalDate.now().plusDays(30))) throw bad("ISSUED_IN_FUTURE", "The issue date is too far ahead.");
        if (in.returnedOn() != null && in.returnedOn().isBefore(in.issuedOn())) {
            throw bad("BAD_RETURNED", "It can't be returned before it was issued.");
        }
        return new AssetInput(kind, name, tag, in.issuedOn(), in.returnedOn(), note);
    }

    private static String trim(String s, int max, String code, String message) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() > max) throw bad(code, message);
        return t;
    }

    static ApiException docNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "That document doesn't exist.");
    }

    static ApiException assetNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "That asset doesn't exist.");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
