package com.hrms.attendance;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work sites: where people may clock in (a geofence and/or office Wi-Fi), which clock-in methods each site
 * accepts, and who belongs to it. Managed with attendance:manage.
 */
@Service
class SiteService {

    static final Set<String> METHODS = Set.of("GEOFENCE", "WIFI", "SELFIE", "FACE", "KIOSK");
    static final int MIN_RADIUS = 25;
    static final int MAX_RADIUS = 1000;
    private static final Pattern BSSID = Pattern.compile("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");

    record Wifi(String bssid, String ssid, String label) {
    }

    record Summary(UUID id, String name, String address, String entity, boolean active, BigDecimal lat, BigDecimal lng,
                   int radiusM, List<String> methods, String wifiMode, int wifiCount, int people) {
    }

    record Detail(UUID id, String name, String address, UUID entityId, String entity, boolean active, BigDecimal lat,
                  BigDecimal lng, int radiusM, List<String> methods, String wifiMode, boolean remarksRequired,
                  List<Wifi> wifi, List<Member> members, Instant updatedAt, boolean hasPunches) {
    }

    record Member(UUID employeeId, String employeeNo, String fullName, String department, List<String> otherSites) {
    }

    record Input(String name, String address, UUID entityId, Boolean active, Double lat, Double lng, Integer radiusM,
                 List<String> methods, String wifiMode, Boolean remarksRequired, List<Wifi> wifi) {
    }

    record Entity(UUID id, String name) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    SiteService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------- reading ----------

    List<Summary> list() {
        return jdbc.sql("""
                select s.id, s.name, s.address, l.name as entity, s.active, s.lat, s.lng, s.radius_m, s.methods, s.wifi_mode,
                       (select count(*) from site_wifi w where w.site_id = s.id)::int as wifi_count,
                       (select count(*) from employee_site es join employee e on e.id = es.employee_id
                        where es.site_id = s.id and e.status = 'ACTIVE')::int as people
                from site s join legal_entity l on l.id = s.entity_id
                order by s.active desc, s.name""")
            .query((rs, i) -> new Summary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getString("entity"), rs.getBoolean("active"), rs.getBigDecimal("lat"), rs.getBigDecimal("lng"),
                rs.getInt("radius_m"), List.of((String[]) rs.getArray("methods").getArray()), rs.getString("wifi_mode"),
                rs.getInt("wifi_count"), rs.getInt("people")))
            .list();
    }

    Detail find(UUID id) {
        Detail d = jdbc.sql("""
                select s.id, s.name, s.address, s.entity_id, l.name as entity, s.active, s.lat, s.lng, s.radius_m, s.methods,
                       s.wifi_mode, s.remarks_required, s.updated_at,
                       exists (select 1 from punch p where p.site_id = s.id) as has_punches
                from site s join legal_entity l on l.id = s.entity_id where s.id = :id""")
            .param("id", id)
            .query((rs, i) -> new Detail(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getObject("entity_id", UUID.class), rs.getString("entity"), rs.getBoolean("active"), rs.getBigDecimal("lat"),
                rs.getBigDecimal("lng"), rs.getInt("radius_m"), List.of((String[]) rs.getArray("methods").getArray()),
                rs.getString("wifi_mode"), rs.getBoolean("remarks_required"), List.of(), List.of(),
                rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("has_punches")))
            .optional().orElseThrow(SiteService::notFound);
        List<Wifi> wifi = jdbc.sql("select bssid, ssid, label from site_wifi where site_id = :id order by coalesce(ssid, ''), bssid")
            .param("id", id).query(Wifi.class).list();
        return new Detail(d.id(), d.name(), d.address(), d.entityId(), d.entity(), d.active(), d.lat(), d.lng(), d.radiusM(),
            d.methods(), d.wifiMode(), d.remarksRequired(), wifi, members(id), d.updatedAt(), d.hasPunches());
    }

    List<Member> members(UUID siteId) {
        record Row(UUID employeeId, String employeeNo, String fullName, String department, String otherSites) {
        }
        return jdbc.sql("""
                select e.id as employee_id, e.employee_no, e.full_name, d.name as department,
                       (select string_agg(o.name, '|' order by o.name) from employee_site x join site o on o.id = x.site_id
                        where x.employee_id = e.id and x.site_id <> :site) as other_sites
                from employee_site es
                join employee e on e.id = es.employee_id
                left join department d on d.id = e.department_id
                where es.site_id = :site and e.status = 'ACTIVE'
                order by e.full_name""")
            .param("site", siteId).query(Row.class).list().stream()
            .map(r -> new Member(r.employeeId(), r.employeeNo(), r.fullName(), r.department(),
                r.otherSites() == null ? List.of() : List.of(r.otherSites().split("\\|"))))
            .toList();
    }

    List<Entity> entities() {
        return jdbc.sql("select id, name from legal_entity order by created_at").query(Entity.class).list();
    }

    // ---------- changing ----------

    @Transactional
    Detail create(UUID actor, Input in) {
        Valid v = validate(in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into site (id, tenant_id, entity_id, name, address, lat, lng, radius_m, methods, wifi_mode, remarks_required, active)
                    values (:id, :tenant, :entity, :name, :address, :lat, :lng, :radius, cast(:methods as text[]), :mode, :remarks, :active)""")
                .param("id", id).param("tenant", TenantContext.require()).param("entity", v.entityId()).param("name", v.name())
                .param("address", v.address()).param("lat", v.lat()).param("lng", v.lng()).param("radius", v.radiusM())
                .param("methods", arrayLiteral(v.methods())).param("mode", v.wifiMode()).param("remarks", v.remarksRequired())
                .param("active", v.active()).update();
        } catch (DuplicateKeyException e) {
            throw nameTaken(v.name());
        }
        writeWifi(id, v.wifi());
        audit.record(actor, "SITE_CREATED", "site", id, Map.of("name", v.name()));
        return find(id);
    }

    @Transactional
    Detail update(UUID actor, UUID id, Input in) {
        find(id);
        Valid v = validate(in);
        try {
            jdbc.sql("""
                    update site set entity_id = :entity, name = :name, address = :address, lat = :lat, lng = :lng,
                           radius_m = :radius, methods = cast(:methods as text[]), wifi_mode = :mode,
                           remarks_required = :remarks, active = :active, updated_at = now()
                    where id = :id""")
                .param("entity", v.entityId()).param("name", v.name()).param("address", v.address()).param("lat", v.lat())
                .param("lng", v.lng()).param("radius", v.radiusM()).param("methods", arrayLiteral(v.methods()))
                .param("mode", v.wifiMode()).param("remarks", v.remarksRequired()).param("active", v.active())
                .param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw nameTaken(v.name());
        }
        writeWifi(id, v.wifi());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", v.name());
        after.put("radiusM", v.radiusM());
        after.put("methods", v.methods());
        after.put("wifi", v.wifi().size());
        after.put("active", v.active());
        audit.record(actor, "SITE_UPDATED", "site", id, after);
        return find(id);
    }

    /** Deletes a site nobody has clocked in at; otherwise it must be retired (switched off) to keep the history. */
    @Transactional
    void delete(UUID actor, UUID id) {
        Detail d = find(id);
        if (d.hasPunches()) {
            throw new ApiException(HttpStatus.CONFLICT, "SITE_IN_USE",
                "People have clocked in at " + d.name() + ", so it can't be deleted. Switch it off instead to keep the history.");
        }
        jdbc.sql("delete from site where id = :id").param("id", id).update();
        audit.record(actor, "SITE_DELETED", "site", id, Map.of("name", d.name()));
    }

    /** Adds people to a site (already-added ones are skipped). */
    @Transactional
    List<Member> addMembers(UUID actor, UUID siteId, List<UUID> employeeIds) {
        Detail d = find(siteId);
        Set<UUID> ids = new LinkedHashSet<>(employeeIds == null ? List.of() : employeeIds);
        if (ids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose at least one person.");
        if (ids.size() > 1000) throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY", "Add up to 1,000 people at a time.");
        int known = jdbc.sql("select count(*) from employee where id in (:ids) and status = 'ACTIVE'")
            .param("ids", ids).query(Integer.class).single();
        if (known != ids.size()) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_EMPLOYEE", "Some of those people don't exist or have left.");
        UUID tenant = TenantContext.require();
        int added = 0;
        for (UUID e : ids) {
            added += jdbc.sql("""
                    insert into employee_site (tenant_id, employee_id, site_id) values (:tenant, :emp, :site)
                    on conflict do nothing""")
                .param("tenant", tenant).param("emp", e).param("site", siteId).update();
        }
        audit.record(actor, "SITE_MEMBERS_ADDED", "site", siteId, Map.of("site", d.name(), "added", added));
        return members(siteId);
    }

    @Transactional
    List<Member> removeMembers(UUID actor, UUID siteId, List<UUID> employeeIds) {
        Detail d = find(siteId);
        if (employeeIds == null || employeeIds.isEmpty()) return members(siteId);
        int removed = jdbc.sql("delete from employee_site where site_id = :site and employee_id in (:ids)")
            .param("site", siteId).param("ids", employeeIds).update();
        audit.record(actor, "SITE_MEMBERS_REMOVED", "site", siteId, Map.of("site", d.name(), "removed", removed));
        return members(siteId);
    }

    // ---------- validation ----------

    private record Valid(String name, String address, UUID entityId, boolean active, BigDecimal lat, BigDecimal lng, int radiusM,
                         List<String> methods, String wifiMode, boolean remarksRequired, List<Wifi> wifi) {
    }

    private Valid validate(Input in) {
        if (in == null) throw invalid("name", "Fill in the site details.");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 80) throw invalid("name", "Give the site a name of up to 80 characters.");
        String address = in.address() == null || in.address().isBlank() ? null : in.address().trim();
        if (address != null && address.length() > 300) throw invalid("address", "Keep the address under 300 characters.");

        UUID entity = in.entityId();
        if (entity == null) {
            entity = jdbc.sql("select id from legal_entity order by created_at limit 1").query(UUID.class).optional()
                .orElseThrow(() -> invalid("entityId", "Set up a legal entity first."));
        } else if (!jdbc.sql("select exists (select 1 from legal_entity where id = :id)").param("id", entity).query(Boolean.class).single()) {
            throw invalid("entityId", "That legal entity doesn't exist.");
        }

        if (in.lat() == null || in.lng() == null || in.lat() < -90 || in.lat() > 90 || in.lng() < -180 || in.lng() > 180
            || (in.lat() == 0 && in.lng() == 0)) {
            throw invalid("location", "Put the site on the map.");
        }
        int radius = in.radiusM() == null ? 100 : in.radiusM();
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw invalid("radiusM", "The geofence radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS + " m.");
        }

        List<String> methods = new ArrayList<>(new LinkedHashSet<>(in.methods() == null ? List.of() : in.methods()));
        if (!METHODS.containsAll(methods)) throw invalid("methods", "Unknown clock-in method.");
        if (!(methods.contains("GEOFENCE") || methods.contains("WIFI") || methods.contains("KIOSK"))) {
            throw invalid("methods", "Allow at least one way to prove someone is at the site: geofence, office Wi-Fi or the kiosk.");
        }
        methods.sort(java.util.Comparator.comparing(List.of("GEOFENCE", "WIFI", "KIOSK", "SELFIE", "FACE")::indexOf));
        String mode = "GPS_AND_WIFI".equals(in.wifiMode()) ? "GPS_AND_WIFI" : "GPS_OR_WIFI";

        List<Wifi> wifi = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Wifi w : in.wifi() == null ? List.<Wifi>of() : in.wifi()) {
            String bssid = normaliseBssid(w.bssid());
            if (bssid == null) throw invalid("wifi", "\"" + w.bssid() + "\" isn't an access point address. It looks like 3c:84:6a:12:9f:01.");
            if (!seen.add(bssid)) continue;
            wifi.add(new Wifi(bssid, trimTo(w.ssid(), 32), trimTo(w.label(), 80)));
        }
        if (wifi.size() > 50) throw invalid("wifi", "Add up to 50 access points per site.");
        if (methods.contains("WIFI") && wifi.isEmpty()) {
            throw invalid("wifi", "Add the office Wi-Fi access points, or switch off the Office Wi-Fi method.");
        }
        return new Valid(name, address, entity, in.active() == null || in.active(), coord(in.lat()), coord(in.lng()), radius,
            methods, mode, in.remarksRequired() != null && in.remarksRequired(), wifi);
    }

    /** Accepts 3C-84-6A-12-9F-01, 3c:84:6a:12:9f:01 or 3c846a129f01. */
    static String normaliseBssid(String raw) {
        if (raw == null) return null;
        String hex = raw.trim().toLowerCase().replaceAll("[\\s:.-]", "");
        if (!hex.matches("^[0-9a-f]{12}$")) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) out.append(':');
            out.append(hex, i, i + 2);
        }
        String b = out.toString();
        return BSSID.matcher(b).matches() ? b : null;
    }

    private void writeWifi(UUID siteId, List<Wifi> wifi) {
        jdbc.sql("delete from site_wifi where site_id = :site").param("site", siteId).update();
        UUID tenant = TenantContext.require();
        for (Wifi w : wifi) {
            jdbc.sql("insert into site_wifi (tenant_id, site_id, bssid, ssid, label) values (:tenant, :site, :bssid, :ssid, :label)")
                .param("tenant", tenant).param("site", siteId).param("bssid", w.bssid()).param("ssid", w.ssid())
                .param("label", w.label()).update();
        }
    }

    private static BigDecimal coord(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String arrayLiteral(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SITE", message).with("fields", Map.of(field, message));
    }

    private static ApiException nameTaken(String name) {
        return new ApiException(HttpStatus.CONFLICT, "SITE_NAME_TAKEN", "There's already a site called " + name + ".")
            .with("fields", Map.of("name", "There's already a site called " + name + "."));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "That site doesn't exist.");
    }
}
