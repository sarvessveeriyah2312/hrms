package com.hrms.search;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.security.PermissionChecker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One search box for everything the signed-in person may see. Each kind of result is checked against the same
 * permission and data scope as its page, so search never shows more than browsing would. Rows from switched-off
 * modules don't appear because their permissions are removed at sign-in.
 *
 * <p>Results carry raw values (status, date, amount) so the web app formats them in the reader's language.
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    /**
     * kind: PERSON, LEAVE, CLAIM, USER, ROLE, DEPARTMENT, LEAVE_TYPE, CLAIM_TYPE, WORKFLOW or HOLIDAY.
     * {@code link} is the path in the web app that opens it. {@code tag} is a small extra value for the kind
     * (a workflow's subject: LEAVE, CLAIM, CORRECTION or OVERTIME).
     */
    record Hit(String kind, String id, String title, String meta, String status, LocalDate date, LocalDate endDate,
               BigDecimal amount, String currency, String tag, String link) {
        static Hit of(String kind, Object id, String title, String meta, String link) {
            return new Hit(kind, String.valueOf(id), title, meta, null, null, null, null, null, null, link);
        }
    }

    record Result(String query, List<Hit> hits) {
    }

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 80;
    static final int PER_KIND = 5;

    private final JdbcClient jdbc;
    private final PermissionChecker perm;

    SearchController(JdbcClient jdbc, PermissionChecker perm) {
        this.jdbc = jdbc;
        this.perm = perm;
    }

    @GetMapping
    @Transactional(readOnly = true)
    Result search(@RequestParam(required = false) String q, @AuthenticationPrincipal Jwt jwt) {
        String text = q == null ? "" : q.trim();
        if (text.length() < MIN_LENGTH) return new Result(text, List.of());
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String meClaim = jwt.getClaimAsString("employee_id");
        UUID me = meClaim == null ? null : UUID.fromString(meClaim);

        List<Hit> hits = new ArrayList<>();
        scope("employee:view").ifPresent(s -> hits.addAll(people(like, me, s)));
        hits.addAll(leave(like, me));
        hits.addAll(claims(like, me));
        if (perm.has("admin:users")) hits.addAll(users(like));
        if (perm.has("admin:roles")) hits.addAll(roles(like));
        if (perm.has("admin:company")) hits.addAll(departments(like));
        if (perm.has("leave:manage")) {
            hits.addAll(leaveTypes(like));
            hits.addAll(holidays(like));
        }
        if (perm.has("claims:manage")) hits.addAll(claimTypes(like));
        if (perm.has("workflow:manage")) hits.addAll(workflows(like));
        return new Result(text, hits);
    }

    private Optional<DataScope> scope(String permission) {
        return perm.has(permission) ? perm.scopeOf(permission) : Optional.empty();
    }

    // ---------- records ----------

    private List<Hit> people(String like, UUID me, DataScope scope) {
        record Row(UUID id, String employeeNo, String fullName, String email, String department) {
        }
        return jdbc.sql("""
                select e.id, e.employee_no, e.full_name, u.email::text as email, d.name as department
                from employee e
                left join app_user u on u.id = e.user_id
                left join department d on d.id = e.department_id
                where %s and (e.full_name ilike :q escape '\\' or e.employee_no ilike :q escape '\\'
                              or u.email::text ilike :q escape '\\' or u.phone ilike :q escape '\\' or d.name ilike :q escape '\\')
                order by (e.full_name ilike :q escape '\\') desc, e.full_name
                limit :n""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me).param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> Hit.of("PERSON", r.id(), r.fullName(), join(r.employeeNo(), r.department(), r.email()),
                "/app/people/" + r.id()))
            .toList();
    }

    /** Your own leave, plus others' within leave:view (approved and pending; plans stay private). */
    private List<Hit> leave(String like, UUID me) {
        boolean apply = perm.has("leave:apply");
        Optional<DataScope> view = scope("leave:view");
        if (me == null && view.isEmpty()) return List.of();
        if (!apply && view.isEmpty()) return List.of();
        String others = view.map(s -> "(r.status <> 'DRAFT' and " + EmployeeScope.predicate(s, "e") + ")").orElse("false");
        record Row(UUID id, String employeeName, String typeName, String reason, String status, LocalDate startDate,
                   LocalDate endDate, boolean mine) {
        }
        return jdbc.sql("""
                select r.id, e.full_name as employee_name, t.name as type_name, r.reason, r.status, r.start_date, r.end_date,
                       (r.employee_id = :me) as mine
                from leave_request r join leave_type t on t.id = r.leave_type_id join employee e on e.id = r.employee_id
                where (r.employee_id = :me or %s)
                  and (t.name ilike :q escape '\\' or e.full_name ilike :q escape '\\' or r.reason ilike :q escape '\\'
                       or e.employee_no ilike :q escape '\\')
                order by r.start_date desc
                limit :n""".formatted(others))
            .param("me", me == null ? new UUID(0, 0) : me).param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("LEAVE", r.id().toString(), r.typeName(), join(r.mine() ? null : r.employeeName(), r.reason()),
                r.status(), r.startDate(), r.endDate(), null, null, null, "/app/leave?request=" + r.id()))
            .toList();
    }

    /** Your own claims, plus others' within claims:view. */
    private List<Hit> claims(String like, UUID me) {
        Optional<DataScope> view = scope("claims:view");
        if (!perm.has("claims:submit") && view.isEmpty()) return List.of();
        String others = view.map(s -> EmployeeScope.predicate(s, "e")).orElse("false");
        record Row(UUID id, String employeeName, String typeName, String description, String merchant, String status,
                   LocalDate expenseDate, BigDecimal amount, String currency, boolean paid, boolean mine) {
        }
        return jdbc.sql("""
                select c.id, e.full_name as employee_name, t.name as type_name, c.description, c.merchant, c.status,
                       c.expense_date, c.amount, c.currency, (c.paid_at is not null) as paid, (c.employee_id = :me) as mine
                from claim c join claim_type t on t.id = c.claim_type_id join employee e on e.id = c.employee_id
                where (c.employee_id = :me or %s)
                  and (c.description ilike :q escape '\\' or c.merchant ilike :q escape '\\' or t.name ilike :q escape '\\'
                       or e.full_name ilike :q escape '\\' or e.employee_no ilike :q escape '\\' or c.payment_ref ilike :q escape '\\')
                order by c.expense_date desc
                limit :n""".formatted(others))
            .param("me", me == null ? new UUID(0, 0) : me).param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("CLAIM", r.id().toString(), r.typeName(),
                join(r.mine() ? null : r.employeeName(), r.description(), r.merchant()),
                r.paid() ? "PAID" : r.status(), r.expenseDate(), null, r.amount(), r.currency().trim(), null, "/app/claims?claim=" + r.id()))
            .toList();
    }

    // ---------- administration and settings ----------

    private List<Hit> users(String like) {
        record Row(UUID id, String fullName, String email, String phone, String status) {
        }
        return jdbc.sql("""
                select u.id, e.full_name, u.email::text as email, u.phone, u.status
                from app_user u left join employee e on e.user_id = u.id
                where e.full_name ilike :q escape '\\' or u.email::text ilike :q escape '\\' or u.phone ilike :q escape '\\'
                order by coalesce(e.full_name, u.email::text) limit :n""")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("USER", r.id().toString(), r.fullName() != null ? r.fullName() : r.email(),
                join(r.email(), r.phone()), r.status(), null, null, null, null, null, "/app/admin/users?open=" + r.id()))
            .toList();
    }

    private List<Hit> roles(String like) {
        record Row(UUID id, String name, String description) {
        }
        return jdbc.sql("""
                select id, name, description from role
                where name ilike :q escape '\\' or description ilike :q escape '\\' or code ilike :q escape '\\'
                order by name limit :n""")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> Hit.of("ROLE", r.id(), r.name(), r.description(), "/app/admin/roles?open=" + r.id()))
            .toList();
    }

    private List<Hit> departments(String like) {
        record Row(UUID id, String name, long people) {
        }
        return jdbc.sql("""
                select d.id, d.name, (select count(*) from employee e where e.department_id = d.id and e.status = 'ACTIVE') as people
                from department d where d.name ilike :q escape '\\' order by d.name limit :n""")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> Hit.of("DEPARTMENT", r.id(), r.name(), String.valueOf(r.people()),
                "/app/admin/company?tab=departments&open=" + r.id()))
            .toList();
    }

    private List<Hit> leaveTypes(String like) {
        record Row(UUID id, String name, boolean active) {
        }
        return jdbc.sql("select id, name, active from leave_type where name ilike :q escape '\\' or code ilike :q escape '\\' order by name limit :n")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("LEAVE_TYPE", r.id().toString(), r.name(), null, r.active() ? null : "RETIRED", null, null, null, null, null,
                "/app/leave/settings?type=" + r.id()))
            .toList();
    }

    private List<Hit> holidays(String like) {
        record Row(UUID id, String name, LocalDate date) {
        }
        return jdbc.sql("select id, name, date from public_holiday where name ilike :q escape '\\' order by date desc limit :n")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("HOLIDAY", r.id().toString(), r.name(), null, null, r.date(), null, null, null, null,
                "/app/leave/settings?tab=holidays"))
            .toList();
    }

    private List<Hit> claimTypes(String like) {
        record Row(UUID id, String name, boolean active) {
        }
        return jdbc.sql("select id, name, active from claim_type where name ilike :q escape '\\' or code ilike :q escape '\\' order by name limit :n")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("CLAIM_TYPE", r.id().toString(), r.name(), null, r.active() ? null : "RETIRED", null, null, null, null, null,
                "/app/claims/settings?type=" + r.id()))
            .toList();
    }

    private List<Hit> workflows(String like) {
        record Row(UUID id, String subjectType, String name, String description, boolean active) {
        }
        return jdbc.sql("""
                select id, subject_type, name, description, active from approval_flow
                where name ilike :q escape '\\' or description ilike :q escape '\\' order by name limit :n""")
            .param("q", like).param("n", PER_KIND).query(Row.class).list().stream()
            .map(r -> new Hit("WORKFLOW", r.id().toString(), r.name(), r.description(), r.active() ? null : "OFF",
                null, null, null, null, r.subjectType(),
                "/app/admin/workflows?" + forParam(r.subjectType()) + "open=" + r.id()))
            .toList();
    }

    // ---------- helpers ----------

    /** Opens the workflows page on the right subject. */
    private static String forParam(String subjectType) {
        return switch (subjectType) {
            case "CLAIM" -> "for=claims&";
            case "CORRECTION" -> "for=corrections&";
            case "OVERTIME" -> "for=overtime&";
            case "SHIFT_CHANGE" -> "for=shifts&";
            default -> "";
        };
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p.trim());
        return out.isEmpty() ? null : String.join(" · ", out);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
