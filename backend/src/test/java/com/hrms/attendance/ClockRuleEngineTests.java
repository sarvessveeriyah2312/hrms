package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClockRuleEngineTests {

    private final ClockRuleEngine engine = new ClockRuleEngine(120);
    private final Instant now = Instant.parse("2027-03-01T01:00:00Z");
    private static final String AP = "aa:bb:cc:dd:ee:ff";

    private static ClockRuleEngine.Site site(String name, Set<String> methods, boolean both, boolean remarks) {
        return new ClockRuleEngine.Site(UUID.randomUUID(), name, 3.1300, 101.6700, 100, methods, Set.of(AP), both, remarks);
    }

    private final ClockRuleEngine.Site office = site("Office", Set.of("GEOFENCE", "WIFI"), false, false);

    private ClockRuleEngine.Evidence ev(Double lat, Double lng, String wifi) {
        return new ClockRuleEngine.Evidence(lat, lng, 10, wifi, now, false, false, false, false, Punch.Source.MOBILE, false, null);
    }

    private ClockRuleEngine.Evidence inside() {
        return ev(3.1302, 101.6701, null);
    }

    private ClockRuleEngine.Evidence farAway() {
        return ev(3.1400, 101.6700, null);
    }

    @Test
    void insideTheGeofenceIsAccepted() {
        var d = engine.evaluate(inside(), List.of(office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.ACCEPTED);
        assertThat(d.siteId()).isEqualTo(office.id());
        assertThat(d.site()).isEqualTo("Office");
    }

    @Test
    void outsideIsBlockedWithTheNearestSiteDistanceAndRadius() {
        var d = engine.evaluate(farAway(), List.of(office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.BLOCKED);
        assertThat(d.code()).isEqualTo("ATTENDANCE_OUTSIDE_SITE");
        assertThat(d.site()).isEqualTo("Office");
        assertThat(d.distanceM()).isGreaterThan(1000);
        assertThat(d.radiusM()).isEqualTo(100);
        assertThat(PunchService.explain(d)).contains("m from Office").contains("within 100 m");
    }

    @Test
    void officeWifiIsEnoughWhenEitherIsAllowed() {
        assertThat(engine.evaluate(ev(3.1400, 101.6700, AP), List.of(office), now).result()).isEqualTo(Punch.Result.ACCEPTED);
    }

    @Test
    void whenBothAreNeededTheGeofenceAloneIsNotEnough() {
        var strict = site("Lab", Set.of("GEOFENCE", "WIFI"), true, false);
        assertThat(engine.evaluate(inside(), List.of(strict), now).code()).isEqualTo("ATTENDANCE_WIFI_REQUIRED");
        assertThat(engine.evaluate(ev(3.1302, 101.6701, AP), List.of(strict), now).result()).isEqualTo(Punch.Result.ACCEPTED);
        assertThat(engine.evaluate(ev(3.1400, 101.6700, AP), List.of(strict), now).result()).isEqualTo(Punch.Result.BLOCKED);
    }

    @Test
    void wifiOnlySitesIgnoreTheGeofence() {
        var wifiOnly = site("Store", Set.of("WIFI"), false, false);
        assertThat(engine.evaluate(inside(), List.of(wifiOnly), now).code()).isEqualTo("ATTENDANCE_NOT_ON_WIFI");
        assertThat(engine.evaluate(ev(null, null, AP), List.of(wifiOnly), now).result()).isEqualTo(Punch.Result.ACCEPTED);
    }

    @Test
    void anyOfSeveralSitesWill() {
        var far = new ClockRuleEngine.Site(UUID.randomUUID(), "Far", 1.30, 103.80, 100, Set.of("GEOFENCE"), Set.of(), false, false);
        var d = engine.evaluate(inside(), List.of(far, office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.ACCEPTED);
        assertThat(d.site()).isEqualTo("Office");
    }

    @Test
    void refusesWithoutASiteWithFakeGpsFromAKioskOrWithoutLocation() {
        assertThat(engine.evaluate(inside(), List.of(), now).code()).isEqualTo("ATTENDANCE_NO_SITE");
        var mock = new ClockRuleEngine.Evidence(3.1302, 101.6701, 10, null, now, true, false, false, false, Punch.Source.MOBILE, false, null);
        assertThat(engine.evaluate(mock, List.of(office), now).code()).isEqualTo("ATTENDANCE_MOCK_LOCATION");
        var kiosk = new ClockRuleEngine.Evidence(3.1302, 101.6701, 10, null, now, false, false, false, false, Punch.Source.KIOSK, false, null);
        assertThat(engine.evaluate(kiosk, List.of(office), now).code()).isEqualTo("ATTENDANCE_KIOSK_NOT_SET_UP");
        assertThat(engine.evaluate(inside(), List.of(site("Gate", Set.of("KIOSK"), false, false)), now).code()).isEqualTo("ATTENDANCE_KIOSK_ONLY");
        assertThat(engine.evaluate(ev(null, null, null), List.of(office), now).code()).isEqualTo("ATTENDANCE_NO_LOCATION");
    }

    @Test
    void selfieAndRemarkRequirementsComeFromTheMatchedSite() {
        var selfieSite = site("Warehouse", Set.of("GEOFENCE", "SELFIE"), false, true);
        assertThat(engine.evaluate(inside(), List.of(selfieSite), now).code()).isEqualTo("ATTENDANCE_SELFIE_REQUIRED");
        var withSelfie = new ClockRuleEngine.Evidence(3.1302, 101.6701, 10, null, now, false, false, false, false, Punch.Source.MOBILE, true, null);
        assertThat(engine.evaluate(withSelfie, List.of(selfieSite), now).code()).isEqualTo("ATTENDANCE_REMARK_REQUIRED");
        var complete = new ClockRuleEngine.Evidence(3.1302, 101.6701, 10, null, now, false, false, false, false, Punch.Source.MOBILE, true, "Night shift");
        assertThat(engine.evaluate(complete, List.of(selfieSite), now).result()).isEqualTo(Punch.Result.ACCEPTED);
    }

    @Test
    void faceSitesAreFlaggedUntilFaceMatchingExists() {
        var faceSite = site("Vault", Set.of("GEOFENCE", "FACE"), false, false);
        var withSelfie = new ClockRuleEngine.Evidence(3.1302, 101.6701, 10, null, now, false, false, false, false, Punch.Source.MOBILE, true, null);
        var d = engine.evaluate(withSelfie, List.of(faceSite), now);
        assertThat(d.result()).isEqualTo(Punch.Result.FLAGGED);
        assertThat(d.flags()).containsExactly("FACE_NOT_VERIFIED");
    }

    @Test
    void integritySignalsAreFlaggedNotBlocked() {
        var e = new ClockRuleEngine.Evidence(3.1302, 101.6701, 400, null, now.minusSeconds(600), false, true, true, false, Punch.Source.MOBILE, false, null);
        var d = engine.evaluate(e, List.of(office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.FLAGGED);
        assertThat(d.flags()).containsExactlyInAnyOrder("ROOTED_DEVICE", "DEVELOPER_MODE", "CLOCK_DRIFT", "LOW_GPS_ACCURACY");
    }
}
