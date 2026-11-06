package com.hrms.leave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hrms.leave.EntitlementPolicy.Tier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntitlementPolicyTests {

    private static final BigDecimal FOURTEEN = BigDecimal.valueOf(14);
    private static final List<Tier> TIERS = List.of(new Tier(2, BigDecimal.valueOf(16)), new Tier(5, BigDecimal.valueOf(18)));

    @Test
    void defaultWhenNoTierReached() {
        var r = EntitlementPolicy.compute(FOURTEEN, TIERS, false, LocalDate.of(2025, 3, 1), 2026);
        assertEquals(0, FOURTEEN.compareTo(r.days()));
        assertEquals("Company default", r.basis());
    }

    @Test
    void highestTierReachedOnFirstOfJanuary() {
        // Joined 2 Jan 2021: on 1 Jan 2026 that's 4 full years -> the 2-year tier, not 5.
        assertEquals(0, BigDecimal.valueOf(16).compareTo(EntitlementPolicy.compute(FOURTEEN, TIERS, false, LocalDate.of(2021, 1, 2), 2026).days()));
        // Joined 1 Jan 2021: exactly 5 years.
        var r = EntitlementPolicy.compute(FOURTEEN, TIERS, false, LocalDate.of(2021, 1, 1), 2026);
        assertEquals(0, BigDecimal.valueOf(18).compareTo(r.days()));
        assertEquals("5+ years of service", r.basis());
    }

    @Test
    void proRatesOnlyInTheJoiningYear() {
        // Joined in July: 6 of 12 months -> 7 days.
        var r = EntitlementPolicy.compute(FOURTEEN, List.of(), true, LocalDate.of(2026, 7, 20), 2026);
        assertEquals(0, BigDecimal.valueOf(7).compareTo(r.days()));
        assertTrue(r.basis().contains("pro-rated from July"));
        // Joined in March: 10 of 12 -> 11.67 -> rounded down to 11.5.
        assertEquals(new BigDecimal("11.5"), EntitlementPolicy.compute(FOURTEEN, List.of(), true, LocalDate.of(2026, 3, 1), 2026).days());
        // The next year is full.
        assertEquals(0, FOURTEEN.compareTo(EntitlementPolicy.compute(FOURTEEN, List.of(), true, LocalDate.of(2026, 3, 1), 2027).days()));
    }

    @Test
    void noProRatingWhenSwitchedOff() {
        assertEquals(0, FOURTEEN.compareTo(EntitlementPolicy.compute(FOURTEEN, List.of(), false, LocalDate.of(2026, 7, 1), 2026).days()));
    }

    @Test
    void nothingBeforeJoining() {
        assertEquals(0, BigDecimal.ZERO.compareTo(EntitlementPolicy.compute(FOURTEEN, List.of(), true, LocalDate.of(2027, 2, 1), 2026).days()));
    }

    @Test
    void carriedDaysCountUntilTheyExpire() {
        var five = BigDecimal.valueOf(5);
        var before = EntitlementPolicy.carried(five, 3, 2026, BigDecimal.ZERO, LocalDate.of(2026, 3, 31));
        assertEquals(0, five.compareTo(before.usable()));
        assertEquals(LocalDate.of(2026, 3, 31), before.expiresOn());
        // After 31 March only the 2 carried days used by then remain; 3 are lost.
        var after = EntitlementPolicy.carried(five, 3, 2026, BigDecimal.valueOf(2), LocalDate.of(2026, 4, 1));
        assertEquals(0, BigDecimal.valueOf(2).compareTo(after.usable()));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(after.expired()));
        // Used more than carried: all carried days were used, none lost.
        assertEquals(0, BigDecimal.ZERO.compareTo(EntitlementPolicy.carried(five, 3, 2026, BigDecimal.TEN, LocalDate.of(2026, 6, 1)).expired()));
        // No expiry month: never lost.
        assertEquals(0, five.compareTo(EntitlementPolicy.carried(five, null, 2026, BigDecimal.ZERO, LocalDate.of(2026, 12, 31)).usable()));
    }

    @Test
    void earnedLeaveExpiresOnlyWhenUnused() {
        BigDecimal one = BigDecimal.ONE, two = BigDecimal.valueOf(2);
        // Nothing expired yet: all of it counts.
        assertEquals(0, two.compareTo(EntitlementPolicy.earned(two, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).usable()));
        // 1 day expired, half a day of leave taken before then: half a day is lost.
        var e = EntitlementPolicy.earned(two, one, new BigDecimal("0.5"), BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("1.5").compareTo(e.usable()));
        assertEquals(0, new BigDecimal("0.5").compareTo(e.expired()));
        // Leave taken before expiry came out of carried days first (2 carried), so none of the earned day was used.
        assertEquals(0, one.compareTo(EntitlementPolicy.earned(two, one, two, two).expired()));
        // Took more than the expired day: nothing lost.
        assertEquals(0, BigDecimal.ZERO.compareTo(EntitlementPolicy.earned(two, one, BigDecimal.TEN, BigDecimal.ZERO).expired()));
    }

    @Test
    void halfDayFloor() {
        assertEquals(new BigDecimal("2.5"), EntitlementPolicy.halfDayFloor(new BigDecimal("2.99")));
        assertEquals(new BigDecimal("3.0"), EntitlementPolicy.halfDayFloor(new BigDecimal("3.0")));
    }
}
