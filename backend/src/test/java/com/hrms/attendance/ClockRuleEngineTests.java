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
    private final ClockRuleEngine.Site office =
        new ClockRuleEngine.Site(UUID.randomUUID(), 3.1300, 101.6700, 100, Set.of("aa:bb:cc:dd:ee:ff"), false);

    private ClockRuleEngine.Evidence at(double lat, double lng) {
        return new ClockRuleEngine.Evidence(lat, lng, 10, null, now, false, false, false, false);
    }

    @Test
    void insideGeofenceIsAccepted() {
        var d = engine.evaluate(at(3.1302, 101.6701), List.of(office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.ACCEPTED);
        assertThat(d.siteId()).isEqualTo(office.id());
    }

    @Test
    void outsideGeofenceIsBlockedWithDistance() {
        var d = engine.evaluate(at(3.1400, 101.6700), List.of(office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.BLOCKED);
        assertThat(d.distanceM()).isGreaterThan(1000);
    }

    @Test
    void approvedWifiAllowsClockInOutsideFence() {
        var e = new ClockRuleEngine.Evidence(3.1400, 101.6700, 10, "AA:BB:CC:DD:EE:FF", now, false, false, false, false);
        assertThat(engine.evaluate(e, List.of(office), now).result()).isEqualTo(Punch.Result.ACCEPTED);
    }

    @Test
    void mockLocationAndClockDriftAreFlaggedNotBlocked() {
        var e = new ClockRuleEngine.Evidence(3.1302, 101.6701, 10, null, now.minusSeconds(600), true, false, false, false);
        var d = engine.evaluate(e, List.of(office), now);
        assertThat(d.result()).isEqualTo(Punch.Result.FLAGGED);
        assertThat(d.flags()).containsExactlyInAnyOrder("MOCK_LOCATION", "CLOCK_DRIFT");
    }
}
