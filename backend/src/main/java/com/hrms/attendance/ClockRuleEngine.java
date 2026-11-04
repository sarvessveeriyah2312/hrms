package com.hrms.attendance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a punch is ACCEPTED, FLAGGED or BLOCKED (PRD section 4, BRD rules R-04 to R-07).
 * Only location blocks a punch by default; integrity signals flag it for HR review.
 * The server is always the authority; the app runs the same checks only for instant feedback.
 */
@Component
class ClockRuleEngine {

    /** A work site as the rule engine needs it. */
    record Site(java.util.UUID id, double lat, double lng, int radiusM, Set<String> wifiBssids, boolean requireBoth) {
    }

    /** What the device reported. */
    record Evidence(Double lat, Double lng, Integer accuracyM, String wifiBssid, Instant clientTime,
                    boolean mockLocation, boolean rootedOrJailbroken, boolean developerMode, boolean wfhDay) {
    }

    record Decision(Punch.Result result, java.util.UUID siteId, List<String> flags, Integer distanceM, String code) {
    }

    private final Duration maxClockDrift;

    ClockRuleEngine(@Value("${hrms.attendance.max-client-clock-drift-seconds:120}") long maxDriftSeconds) {
        this.maxClockDrift = Duration.ofSeconds(maxDriftSeconds);
    }

    Decision evaluate(Evidence e, List<Site> allowedSites, Instant serverNow) {
        List<String> flags = new ArrayList<>();

        // 1. Location: inside any assigned site's geofence or on its Wi-Fi (unless today is WFH).
        Site matched = null;
        Integer bestDistance = null;
        if (!e.wfhDay()) {
            for (Site s : allowedSites) {
                Integer d = (e.lat() != null && e.lng() != null) ? (int) Math.round(distanceM(e.lat(), e.lng(), s.lat(), s.lng())) : null;
                boolean inFence = d != null && d <= s.radiusM();
                boolean onWifi = e.wifiBssid() != null && s.wifiBssids().contains(e.wifiBssid().toLowerCase());
                boolean ok = s.requireBoth() ? (inFence && onWifi) : (inFence || onWifi);
                if (d != null && (bestDistance == null || d < bestDistance)) {
                    bestDistance = d;
                }
                if (ok) {
                    matched = s;
                    break;
                }
            }
            if (matched == null && !allowedSites.isEmpty()) {
                return new Decision(Punch.Result.BLOCKED, null, List.of(), bestDistance, "ATTENDANCE_OUTSIDE_SITE");
            }
        }

        // 2. Integrity signals: flag, never silently trust.
        if (e.mockLocation()) flags.add("MOCK_LOCATION");
        if (e.rootedOrJailbroken()) flags.add("ROOTED_DEVICE");
        if (e.developerMode()) flags.add("DEVELOPER_MODE");
        if (e.clientTime() != null && Duration.between(e.clientTime(), serverNow).abs().compareTo(maxClockDrift) > 0) {
            flags.add("CLOCK_DRIFT");
        }
        if (e.accuracyM() != null && e.accuracyM() > 150) flags.add("LOW_GPS_ACCURACY");

        Punch.Result result = flags.isEmpty() ? Punch.Result.ACCEPTED : Punch.Result.FLAGGED;
        return new Decision(result, matched == null ? null : matched.id(), flags, bestDistance, null);
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
