package com.hrms.attendance;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift templates: a named start and end, unpaid break and grace minutes, with a short code and colour for the
 * roster. Shifts copy a template's times, so changing a template never changes shifts already planned.
 */
@Service
class ShiftTemplates {

    static final Set<String> TONES = Set.of("blue", "green", "orange", "teal", "purple", "pink");

    record Template(UUID id, String name, String code, LocalTime startTime, LocalTime endTime, int breakMin, int graceMin,
                    String tone, boolean active) {
    }

    record Input(String name, String code, LocalTime startTime, LocalTime endTime, Integer breakMin, Integer graceMin,
                 String tone, Boolean active) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    ShiftTemplates(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    List<Template> list() {
        return jdbc.sql("""
                select id, name, code, start_time, end_time, break_min, grace_min, tone, active from shift_template
                order by active desc, start_time, name""").query(Template.class).list();
    }

    Template find(UUID id) {
        return jdbc.sql("select id, name, code, start_time, end_time, break_min, grace_min, tone, active from shift_template where id = :id")
            .param("id", id).query(Template.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "That shift template doesn't exist."));
    }

    @Transactional
    Template create(UUID actor, Input in) {
        check(in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into shift_template (id, tenant_id, name, code, start_time, end_time, break_min, grace_min, tone, active)
                    values (:id, :tenant, :name, :code, :start, :end, :break, :grace, :tone, :active)""")
                .param("id", id).param("tenant", TenantContext.require()).params(values(in)).update();
        } catch (DuplicateKeyException e) {
            throw taken(in.name());
        }
        audit.record(actor, "SHIFT_TEMPLATE_CREATED", "shift_template", id, Map.of("name", in.name().trim()));
        return find(id);
    }

    @Transactional
    Template update(UUID actor, UUID id, Input in) {
        find(id);
        check(in);
        try {
            jdbc.sql("""
                    update shift_template set name = :name, code = :code, start_time = :start, end_time = :end,
                           break_min = :break, grace_min = :grace, tone = :tone, active = :active where id = :id""")
                .param("id", id).params(values(in)).update();
        } catch (DuplicateKeyException e) {
            throw taken(in.name());
        }
        audit.record(actor, "SHIFT_TEMPLATE_UPDATED", "shift_template", id, Map.of("name", in.name().trim()));
        return find(id);
    }

    /** Shifts made from it keep their times; they just no longer point at it. */
    @Transactional
    void delete(UUID actor, UUID id) {
        Template t = find(id);
        jdbc.sql("delete from shift_template where id = :id").param("id", id).update();
        audit.record(actor, "SHIFT_TEMPLATE_DELETED", "shift_template", id, Map.of("name", t.name()));
    }

    private static Map<String, Object> values(Input in) {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("name", in.name().trim());
        v.put("code", in.code().trim().toUpperCase());
        v.put("start", in.startTime());
        v.put("end", in.endTime());
        v.put("break", in.breakMin() == null ? 0 : in.breakMin());
        v.put("grace", in.graceMin() == null ? 5 : in.graceMin());
        v.put("tone", in.tone() == null ? "blue" : in.tone());
        v.put("active", in.active() == null || in.active());
        return v;
    }

    static void check(Input in) {
        if (in == null || in.name() == null || in.name().isBlank() || in.name().trim().length() > 60) {
            throw bad("NAME_REQUIRED", "Give the shift a name of up to 60 characters.");
        }
        if (in.code() == null || in.code().isBlank() || in.code().trim().length() > 4) {
            throw bad("CODE_REQUIRED", "Give the shift a short code of up to 4 characters, like AM or N.");
        }
        checkTimes(in.startTime(), in.endTime(), in.breakMin(), in.graceMin());
        if (in.tone() != null && !TONES.contains(in.tone())) throw bad("BAD_TONE", "Choose one of the colours.");
    }

    /** Shared with roster shifts. */
    static void checkTimes(LocalTime start, LocalTime end, Integer breakMin, Integer graceMin) {
        if (start == null || end == null) throw bad("TIMES_REQUIRED", "Give the start and end times.");
        if (!end.isAfter(start)) throw bad("BAD_TIMES", "The shift must end after it starts, on the same day.");
        long length = Duration.between(start, end).toMinutes();
        if (breakMin != null && (breakMin < 0 || breakMin > 240 || breakMin >= length)) {
            throw bad("BAD_BREAK", "The break must be shorter than the shift (up to 240 minutes).");
        }
        if (graceMin != null && (graceMin < 0 || graceMin > 120)) throw bad("BAD_GRACE", "Grace minutes are 0 to 120.");
    }

    private static ApiException taken(String name) {
        return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_NAME_TAKEN", "There's already a shift called " + name.trim() + ".");
    }

    static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
