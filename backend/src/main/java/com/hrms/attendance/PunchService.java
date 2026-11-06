package com.hrms.attendance;

import com.hrms.attendance.PunchController.ClockRequest;
import com.hrms.attendance.PunchController.ClockResponse;
import com.hrms.files.FileStore;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clock in and out. The person's active sites decide where and how (see {@link ClockRuleEngine}); a refused clock-in
 * isn't saved and answers 422 with a code and a plain reason. Times are the server's.
 */
@Service
class PunchService {

    /** Published after every saved punch; other modules (reporting, notification) listen. */
    public record PunchRecorded(UUID tenantId, UUID employeeId, UUID punchId, Punch.Type type, Punch.Result result) {
    }

    /** A site as the phone sees it, so it can check before sending (the server checks again). */
    record MySite(UUID id, String name, String address, double lat, double lng, int radiusM, List<String> methods,
                  String wifiMode, boolean remarksRequired, boolean selfieRequired, List<String> wifiBssids) {
    }

    /** The outcome of a test clock-in (nothing is saved). */
    record Check(Punch.Result result, String code, String message, String site, Integer distanceM, Integer radiusM, List<String> flags) {
    }

    private final PunchRepository punches;
    private final ClockRuleEngine rules;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final FileStore files;
    private final PeriodLocks locks;
    private final Clock clock;

    PunchService(PunchRepository punches, ClockRuleEngine rules, ApplicationEventPublisher events, JdbcClient jdbc, FileStore files,
                 PeriodLocks locks) {
        this.punches = punches;
        this.rules = rules;
        this.events = events;
        this.jdbc = jdbc;
        this.files = files;
        this.locks = locks;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    ClockResponse clock(UUID employeeId, UUID userId, ClockRequest req, String idempotencyKey) {
        // TODO(idempotency): look up idempotencyKey in Redis and return the stored response on retries.
        UUID tenantId = TenantContext.require();
        locks.requireOpenToday(employeeId);
        Instant now = Instant.now(clock);
        boolean selfie = req.selfieFileId() != null && checkSelfie(req.selfieFileId(), userId);
        String bssid = req.wifiBssid() == null ? null : SiteService.normaliseBssid(req.wifiBssid());

        var evidence = new ClockRuleEngine.Evidence(req.lat(), req.lng(), req.accuracyM(), bssid, req.clientTime(),
            req.mockLocation(), req.rootedOrJailbroken(), req.developerMode(), false,
            req.source() == null ? Punch.Source.MOBILE : req.source(), selfie, req.remarks());
        var decision = rules.evaluate(evidence, sitesOf(employeeId), now);

        if (decision.result() == Punch.Result.BLOCKED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, decision.code(), explain(decision))
                .with("site", decision.site()).with("distanceM", decision.distanceM()).with("radiusM", decision.radiusM());
        }

        var punch = new Punch(tenantId, employeeId, req.type(), now, req.clientTime(),
            req.lat() == null ? null : BigDecimal.valueOf(req.lat()),
            req.lng() == null ? null : BigDecimal.valueOf(req.lng()),
            req.accuracyM(), decision.siteId(), bssid,
            decision.flags().toArray(String[]::new), decision.result(),
            req.source() == null ? Punch.Source.MOBILE : req.source(), blankToNull(req.remarks()));
        punch.setSelfieFileId(selfie ? req.selfieFileId() : null);
        punches.save(punch);
        events.publishEvent(new PunchRecorded(tenantId, employeeId, punch.getId(), punch.getType(), punch.getResult()));

        return new ClockResponse(punch.getId(), punch.getResult(), now, punch.getSiteId(), decision.flags(),
            decision.distanceM(), null);
    }

    /** What would happen if this person clocked in with this evidence. Nothing is saved (for admins testing sites). */
    Check check(UUID employeeId, Double lat, Double lng, Integer accuracyM, String wifiBssid, boolean selfie, String remarks,
                boolean mockLocation) {
        String bssid = wifiBssid == null || wifiBssid.isBlank() ? null : SiteService.normaliseBssid(wifiBssid);
        var evidence = new ClockRuleEngine.Evidence(lat, lng, accuracyM, bssid, null, mockLocation, false, false, false,
            Punch.Source.MOBILE, selfie, remarks);
        var d = rules.evaluate(evidence, sitesOf(employeeId), Instant.now(clock));
        return new Check(d.result(), d.code(), d.result() == Punch.Result.BLOCKED ? explain(d) : null, d.site(), d.distanceM(),
            d.radiusM(), d.flags());
    }

    /** The person's active sites, with what the phone needs to check them. */
    List<MySite> mySites(UUID employeeId) {
        return sitesOf(employeeId).stream().map(s -> {
            String address = jdbc.sql("select address from site where id = :id").param("id", s.id()).query(String.class).optional().orElse(null);
            return new MySite(s.id(), s.name(), address, s.lat(), s.lng(), s.radiusM(), List.copyOf(s.methods()),
                s.requireBoth() ? "GPS_AND_WIFI" : "GPS_OR_WIFI", s.remarksRequired(), s.needsSelfie(), List.copyOf(s.wifiBssids()));
        }).toList();
    }

    // ---------- internals ----------

    List<ClockRuleEngine.Site> sitesOf(UUID employeeId) {
        return jdbc.sql("""
                select s.id, s.name, s.lat, s.lng, s.radius_m, s.methods, s.wifi_mode, s.remarks_required,
                       array(select w.bssid from site_wifi w where w.site_id = s.id) as bssids
                from site s join employee_site es on es.site_id = s.id
                where es.employee_id = :emp and s.active
                order by s.name""")
            .param("emp", employeeId)
            .query((rs, i) -> new ClockRuleEngine.Site(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getInt("radius_m"),
                Set.of((String[]) rs.getArray("methods").getArray()),
                new HashSet<>(List.of((String[]) rs.getArray("bssids").getArray())),
                "GPS_AND_WIFI".equals(rs.getString("wifi_mode")), rs.getBoolean("remarks_required")))
            .list();
    }

    /** The selfie must be the person's own upload, an image, and not already used for another clock-in. */
    private boolean checkSelfie(UUID fileId, UUID userId) {
        var meta = files.meta(fileId).orElseThrow(() ->
            new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FILE", "The selfie didn't upload. Take it again."));
        if (userId == null || !meta.ownerUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_FILE", "The selfie must be one you took.");
        }
        if (!meta.contentType().startsWith("image/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELFIE_NOT_IMAGE", "The selfie must be a photo.");
        }
        boolean used = jdbc.sql("select exists (select 1 from punch where selfie_file_id = :file)").param("file", fileId)
            .query(Boolean.class).single();
        if (used) throw new ApiException(HttpStatus.CONFLICT, "SELFIE_REUSED", "That selfie was already used. Take a new one.");
        return true;
    }

    /** A plain reason for a refused clock-in. */
    static String explain(ClockRuleEngine.Decision d) {
        String site = d.site() == null ? "your site" : d.site();
        return switch (d.code() == null ? "" : d.code()) {
            case "ATTENDANCE_NO_SITE" -> "You're not assigned to a work site yet, so you can't clock in. Ask HR to add you to one.";
            case "ATTENDANCE_MOCK_LOCATION" -> "Your phone is reporting a fake location. Turn off any location-faking app and try again.";
            case "ATTENDANCE_KIOSK_NOT_SET_UP" -> "Kiosk clock-ins aren't set up yet.";
            case "ATTENDANCE_KIOSK_ONLY" -> "Your site only allows clocking in on its kiosk.";
            case "ATTENDANCE_WIFI_REQUIRED" -> "You're at " + site + ", but it also needs you to be on its office Wi-Fi.";
            case "ATTENDANCE_NO_LOCATION" -> "Your location wasn't sent. Turn on location for the app and try again.";
            case "ATTENDANCE_NOT_ON_WIFI" -> "Connect to your site's office Wi-Fi to clock in.";
            case "ATTENDANCE_OUTSIDE_SITE" -> d.distanceM() == null
                ? "You're not at any of your sites."
                : "You're " + d.distanceM() + " m from " + site + ". Clocking in needs you within " + d.radiusM() + " m.";
            case "ATTENDANCE_SELFIE_REQUIRED" -> site + " needs a selfie with every clock-in.";
            case "ATTENDANCE_REMARK_REQUIRED" -> site + " needs a remark with every clock-in.";
            default -> "You can't clock in right now.";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
