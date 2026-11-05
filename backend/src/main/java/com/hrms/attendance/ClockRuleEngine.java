package com.hrms.attendance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a clock-in is ACCEPTED, FLAGGED (saved, for HR to review) or BLOCKED (refused), from the person's
 * sites and what the phone reported. The server is the authority; the app may run the same checks for instant feedback.
 *
 * <p>In order:
 * <ol>
 *   <li>No site assigned: blocked (ask HR).</li>
 *   <li>The phone reports a fake (mock) location: blocked.</li>
 *   <li>Kiosk clock-ins: blocked until kiosk devices can be registered.</li>
 *   <li>Location: at least one site must accept the phone, by its methods: inside the geofence (GEOFENCE), on one of
 *       its access points (WIFI), and with both on, either one or both (GPS_AND_WIFI). Sites that only allow the kiosk
 *       can't be used from a phone.</li>
 *   <li>The matched site's extra requirements: a selfie (SELFIE or FACE; FACE isn't matched yet, so it's flagged),
 *       and a remark when the site asks for one.</li>
 *   <li>Integrity signals that are flagged, never silently trusted: rooted phone, developer mode, clock drift,
 *       poor GPS accuracy.</li>
 * </ol>
 */
@Component
class ClockRuleEngine {

    /** A work site as the rule engine needs it. {@code methods}: GEOFENCE, WIFI, KIOSK, SELFIE, FACE. */
    record Site(UUID id, String name, double lat, double lng, int radiusM, Set<String> methods, Set<String> wifiBssids,
                boolean requireBoth, boolean remarksRequired) {
        boolean geofence() {
            return methods.contains("GEOFENCE");
        }

        boolean wifi() {
            return methods.contains("WIFI");
        }

        boolean usableFromPhone() {
            return geofence() || wifi();
        }

        boolean needsSelfie() {
            return methods.contains("SELFIE") || methods.contains("FACE");
        }
    }

    /** What the device reported. {@code wifiBssid} already normalised (lower case, colon-separated). */
    record Evidence(Double lat, Double lng, Integer accuracyM, String wifiBssid, Instant clientTime,
                    boolean mockLocation, boolean rootedOrJailbroken, boolean developerMode, boolean wfhDay,
                    Punch.Source source, boolean selfie, String remarks) {
    }

    /**
     * {@code code} names the reason when BLOCKED. {@code site}, {@code distanceM} and {@code radiusM} describe the
     * matched site, or the nearest one when blocked for being outside.
     */
    record Decision(Punch.Result result, UUID siteId, String site, List<String> flags, Integer distanceM, Integer radiusM, String code) {
        static Decision blocked(String code) {
            return new Decision(Punch.Result.BLOCKED, null, null, List.of(), null, null, code);
        }
    }

    static final int LOW_ACCURACY_M = 150;

    private final Duration maxClockDrift;

    ClockRuleEngine(@Value("${hrms.attendance.max-client-clock-drift-seconds:120}") long maxDriftSeconds) {
        this.maxClockDrift = Duration.ofSeconds(maxDriftSeconds);
    }

    Decision evaluate(Evidence e, List<Site> sites, Instant serverNow) {
        // 1–3: who and how.
        if (!e.wfhDay() && sites.isEmpty()) return Decision.blocked("ATTENDANCE_NO_SITE");
        if (e.mockLocation()) return Decision.blocked("ATTENDANCE_MOCK_LOCATION");
        if (e.source() == Punch.Source.KIOSK) return Decision.blocked("ATTENDANCE_KIOSK_NOT_SET_UP");

        // 4: location.
        Site matched = null;
        Integer matchedDistance = null;
        Site nearest = null;
        Integer nearestDistance = null;
        boolean inFenceButOffWifi = false;
        if (!e.wfhDay()) {
            boolean hasLocation = e.lat() != null && e.lng() != null;
            for (Site s : sites) {
                if (!s.usableFromPhone()) continue;
                Integer d = hasLocation ? (int) Math.round(distanceM(e.lat(), e.lng(), s.lat(), s.lng())) : null;
                boolean inFence = s.geofence() && d != null && d <= s.radiusM();
                boolean onWifi = s.wifi() && e.wifiBssid() != null && s.wifiBssids().contains(e.wifiBssid());
                boolean ok = s.geofence() && s.wifi()
                    ? (s.requireBoth() ? inFence && onWifi : inFence || onWifi)
                    : s.geofence() ? inFence : onWifi;
                if (s.geofence() && d != null && (nearestDistance == null || d < nearestDistance)) {
                    nearest = s;
                    nearestDistance = d;
                }
                if (ok) {
                    matched = s;
                    matchedDistance = d;
                    break;
                }
                if (inFence && s.requireBoth() && !onWifi) inFenceButOffWifi = true;
            }
            if (matched == null) {
                String code;
                if (sites.stream().noneMatch(Site::usableFromPhone)) code = "ATTENDANCE_KIOSK_ONLY";
                else if (inFenceButOffWifi) code = "ATTENDANCE_WIFI_REQUIRED";
                else if (sites.stream().anyMatch(Site::geofence) && !hasLocation) code = "ATTENDANCE_NO_LOCATION";
                else if (sites.stream().filter(Site::usableFromPhone).noneMatch(Site::geofence)) code = "ATTENDANCE_NOT_ON_WIFI";
                else code = "ATTENDANCE_OUTSIDE_SITE";
                return new Decision(Punch.Result.BLOCKED, null, nearest == null ? null : nearest.name(), List.of(),
                    nearestDistance, nearest == null ? null : nearest.radiusM(), code);
            }
        }

        // 5: the matched site's extra requirements.
        List<String> flags = new ArrayList<>();
        if (matched != null) {
            if (matched.needsSelfie() && !e.selfie()) {
                return new Decision(Punch.Result.BLOCKED, matched.id(), matched.name(), List.of(), matchedDistance, matched.radiusM(),
                    "ATTENDANCE_SELFIE_REQUIRED");
            }
            if (matched.remarksRequired() && (e.remarks() == null || e.remarks().isBlank())) {
                return new Decision(Punch.Result.BLOCKED, matched.id(), matched.name(), List.of(), matchedDistance, matched.radiusM(),
                    "ATTENDANCE_REMARK_REQUIRED");
            }
            if (matched.methods().contains("FACE")) flags.add("FACE_NOT_VERIFIED");
        }

        // 6: integrity signals: flag, never silently trust.
        if (e.rootedOrJailbroken()) flags.add("ROOTED_DEVICE");
        if (e.developerMode()) flags.add("DEVELOPER_MODE");
        if (e.clientTime() != null && Duration.between(e.clientTime(), serverNow).abs().compareTo(maxClockDrift) > 0) {
            flags.add("CLOCK_DRIFT");
        }
        if (e.accuracyM() != null && e.accuracyM() > LOW_ACCURACY_M) flags.add("LOW_GPS_ACCURACY");

        Punch.Result result = flags.isEmpty() ? Punch.Result.ACCEPTED : Punch.Result.FLAGGED;
        return new Decision(result, matched == null ? null : matched.id(), matched == null ? null : matched.name(), flags,
            matchedDistance, matched == null ? null : matched.radiusM(), null);
    }

    /** Haversine distance in metres. */
    static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
