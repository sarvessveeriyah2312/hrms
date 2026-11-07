package com.hrms.employee;

import com.hrms.files.FileStore;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * An employee's profile: job details (HR), the profile sections ({@link ProfileSections}) and the photo.
 * HR changes sections directly; people change their own through {@link ProfileChangeService}.
 */
@Service
class ProfileService {

    static final Set<String> EMPLOYMENT_TYPES = Set.of("PERMANENT", "CONTRACT", "PART_TIME", "INTERN", "TEMPORARY");

    record Job(String fullName, LocalDate joinDate, String jobTitle, String employmentType, LocalDate confirmationDue,
               LocalDate confirmedOn, LocalDate resignedOn, LocalDate lastDay) {
    }

    /** What the caller may do on this profile. */
    record Access(boolean self, boolean manage, boolean seePrivate) {
    }

    /**
     * sections: only those the caller may see (sensitive values opened). hiddenSections: private ones they may not.
     * openChanges: requests waiting for HR or sent back (shown to the person and to HR).
     */
    record Profile(UUID id, String employeeNo, String email, String department, String manager, String entity, String country,
                   String status, Job job, boolean hasPhoto, Map<String, Object> sections, List<String> hiddenSections,
                   Access access, List<ProfileChangeService.Change> openChanges) {
    }

    private record Head(UUID id, String employeeNo, String fullName, String email, String department, String manager, String entity,
                        String country, String status, LocalDate joinDate, String jobTitle, String employmentType,
                        LocalDate confirmationDue, LocalDate confirmedOn, LocalDate resignedOn, LocalDate lastDay, UUID photoFileId) {
    }

    private record SectionRow(String section, String data) {
    }

    private final JdbcClient jdbc;
    private final ProfileSections sections;
    private final EmployeeAccess access;
    private final FileStore files;
    private final AuditLog audit;
    private final JsonMapper json;

    ProfileService(JdbcClient jdbc, ProfileSections sections, EmployeeAccess access, FileStore files, AuditLog audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.sections = sections;
        this.access = access;
        this.files = files;
        this.audit = audit;
        this.json = json;
    }

    // ---------- reading ----------

    @Transactional(readOnly = true)
    Profile profile(UUID me, UUID id, List<ProfileChangeService.Change> openChanges) {
        access.requireView(me, id);
        Head h = head(id);
        boolean self = id.equals(me);
        boolean manage = access.canManage(me, id);
        boolean seePrivate = self || manage;
        Map<String, Map<String, Object>> stored = stored(id);
        Map<String, Object> visible = new LinkedHashMap<>();
        List<String> hidden = new java.util.ArrayList<>();
        for (ProfileSections.Section s : sections.specs()) {
            if (s.isPrivate() && !seePrivate) {
                hidden.add(s.key());
                continue;
            }
            visible.put(s.key(), sections.open(s, stored.getOrDefault(s.key(), Map.of())));
        }
        Job job = new Job(h.fullName(), h.joinDate(), h.jobTitle(), h.employmentType(), h.confirmationDue(), h.confirmedOn(),
            h.resignedOn(), h.lastDay());
        return new Profile(h.id(), h.employeeNo(), h.email(), h.department(), h.manager(), h.entity(), h.country(), h.status(), job,
            h.photoFileId() != null, visible, hidden, new Access(self, manage, seePrivate), seePrivate ? openChanges : List.of());
    }

    /** One section as stored (sensitive values still sealed); empty when never filled in. */
    Map<String, Object> storedSection(UUID employeeId, String section) {
        return jdbc.sql("select data::text from employee_profile_section where employee_id = :id and section = :section")
            .param("id", employeeId).param("section", section).query(String.class).optional()
            .map(this::parse).orElse(Map.of());
    }

    private Map<String, Map<String, Object>> stored(UUID employeeId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.sql("select section, data::text as data from employee_profile_section where employee_id = :id")
            .param("id", employeeId).query(SectionRow.class).list()
            .forEach(r -> out.put(r.section(), parse(r.data())));
        return out;
    }

    // ---------- HR changes ----------

    /** HR sets a section directly (no approval). An empty section clears it. */
    @Transactional
    void updateSection(UUID me, UUID actorUser, UUID id, String sectionKey, Object input) {
        access.requireManage(me, id);
        ProfileSections.Section section = sections.require(sectionKey);
        Map<String, Object> clean = sections.clean(section, input);
        List<String> changed = sections.changedFields(section, sections.open(section, storedSection(id, sectionKey)), clean);
        write(id, sectionKey, sections.seal(section, clean), actorUser);
        audit.record(actorUser, "PROFILE_SECTION_UPDATED", "employee", id, Map.of("section", sectionKey, "fields", changed));
    }

    /** Stores a section's (already cleaned and sealed) values, or removes it when empty. Used by approvals too. */
    void write(UUID employeeId, String section, Map<String, Object> sealed, UUID actorUser) {
        if (sealed == null || sealed.isEmpty()) {
            jdbc.sql("delete from employee_profile_section where employee_id = :id and section = :section")
                .param("id", employeeId).param("section", section).update();
            return;
        }
        jdbc.sql("""
                insert into employee_profile_section (tenant_id, employee_id, section, data, updated_at, updated_by)
                values (:tenant, :id, :section, cast(:data as jsonb), now(), :actor)
                on conflict (employee_id, section) do update set data = excluded.data, updated_at = now(), updated_by = excluded.updated_by""")
            .param("tenant", TenantContext.require()).param("id", employeeId).param("section", section)
            .param("data", json.writeValueAsString(sealed)).param("actor", actorUser).update();
    }

    @Transactional
    void updateJob(UUID me, UUID actorUser, UUID id, Job in) {
        access.requireManage(me, id);
        if (in == null) throw bad("JOB_REQUIRED", "Fill in the job details.");
        String name = in.fullName() == null ? "" : in.fullName().trim();
        if (name.isEmpty()) throw bad("NAME_REQUIRED", "Fill in the full name.");
        if (name.length() > 120) throw bad("NAME_TOO_LONG", "The full name can be up to 120 characters.");
        if (in.joinDate() == null) throw bad("JOIN_DATE_REQUIRED", "Fill in the date they joined.");
        String title = in.jobTitle() == null || in.jobTitle().isBlank() ? null : in.jobTitle().trim();
        if (title != null && title.length() > 120) throw bad("TITLE_TOO_LONG", "The position can be up to 120 characters.");
        String type = in.employmentType() == null || in.employmentType().isBlank() ? null : in.employmentType();
        if (type != null && !EMPLOYMENT_TYPES.contains(type)) throw bad("BAD_EMPLOYMENT_TYPE", "Choose an employment type.");
        if (in.confirmationDue() != null && in.confirmationDue().isBefore(in.joinDate())) {
            throw bad("BAD_CONFIRMATION_DUE", "The end of probation can't be before they joined.");
        }
        if (in.confirmedOn() != null && in.confirmedOn().isBefore(in.joinDate())) {
            throw bad("BAD_CONFIRMED_ON", "The confirmation date can't be before they joined.");
        }
        if (in.resignedOn() != null && in.resignedOn().isBefore(in.joinDate())) {
            throw bad("BAD_RESIGNED_ON", "The resignation date can't be before they joined.");
        }
        if (in.lastDay() != null && in.lastDay().isBefore(in.joinDate())) {
            throw bad("BAD_LAST_DAY", "The last day can't be before they joined.");
        }
        if (in.lastDay() != null && in.resignedOn() != null && in.lastDay().isBefore(in.resignedOn())) {
            throw bad("BAD_LAST_DAY", "The last day can't be before they resigned.");
        }
        jdbc.sql("""
                update employee set full_name = :name, join_date = :join, job_title = :title, employment_type = :type,
                       confirmation_due = :due, confirmed_on = :confirmed, resigned_on = :resigned, last_day = :last,
                       version = version + 1
                where id = :id""")
            .param("name", name).param("join", in.joinDate()).param("title", title).param("type", type)
            .param("due", in.confirmationDue()).param("confirmed", in.confirmedOn()).param("resigned", in.resignedOn())
            .param("last", in.lastDay()).param("id", id).update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fullName", name);
        after.put("joinDate", in.joinDate().toString());
        after.put("jobTitle", title);
        after.put("employmentType", type);
        after.put("confirmedOn", in.confirmedOn() == null ? null : in.confirmedOn().toString());
        after.put("lastDay", in.lastDay() == null ? null : in.lastDay().toString());
        audit.record(actorUser, "EMPLOYEE_JOB_UPDATED", "employee", id, after);
    }

    // ---------- photo ----------

    /** The person sets their own photo, or HR sets someone's: a JPG or PNG they uploaded. Null removes it. */
    @Transactional
    void setPhoto(UUID me, UUID actorUser, UUID id, UUID fileId) {
        if (!id.equals(me)) access.requireManage(me, id);
        if (fileId != null) {
            FileStore.FileMeta meta = files.meta(fileId).filter(m -> m.ownerUserId().equals(actorUser))
                .orElseThrow(() -> bad("FILE_NOT_FOUND", "Upload the photo first."));
            if (!meta.contentType().startsWith("image/")) throw bad("PHOTO_TYPE", "Use a JPG or PNG for the photo.");
        }
        jdbc.sql("update employee set photo_file_id = :file where id = :id").param("file", fileId).param("id", id).update();
        audit.record(actorUser, fileId == null ? "EMPLOYEE_PHOTO_REMOVED" : "EMPLOYEE_PHOTO_SET", "employee", id, Map.of());
    }

    @Transactional(readOnly = true)
    FileStore.StoredFile photo(UUID me, UUID id) {
        access.requireView(me, id);
        UUID file = jdbc.sql("select photo_file_id from employee where id = :id").param("id", id).query(UUID.class).optional().orElse(null);
        if (file == null) throw new ApiException(HttpStatus.NOT_FOUND, "NO_PHOTO", "No photo yet.");
        return files.read(file).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_PHOTO", "No photo yet."));
    }

    // ---------- helpers ----------

    private Head head(UUID id) {
        return jdbc.sql("""
                select e.id, e.employee_no, e.full_name, u.email::text as email, d.name as department, m.full_name as manager,
                       l.name as entity, l.country, e.status, e.join_date, e.job_title, e.employment_type, e.confirmation_due,
                       e.confirmed_on, e.resigned_on, e.last_day, e.photo_file_id
                from employee e
                join legal_entity l on l.id = e.entity_id
                left join app_user u on u.id = e.user_id
                left join department d on d.id = e.department_id
                left join employee m on m.id = e.manager_id
                where e.id = :id""")
            .param("id", id).query(Head.class).optional().orElseThrow(EmployeeAccess::notFound);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parse(String data) {
        if (data == null) return Map.of();
        return json.readValue(data, Map.class);
    }

    String toJson(Map<String, Object> data) {
        return json.writeValueAsString(data);
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
