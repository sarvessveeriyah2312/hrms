package com.hrms.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.Period;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The automatic part of an entitlement, before any manual figure from HR. Pure, so it's unit-tested.
 *
 * <ul>
 *   <li>Length of service is counted in whole years on 1 January of the leave year.</li>
 *   <li>The highest service tier reached replaces the default days.</li>
 *   <li>Pro-rating (when on) applies only in the year someone joins: days × months left including the
 *       joining month ÷ 12, rounded down to the nearest half day.</li>
 * </ul>
 */
final class EntitlementPolicy {

    record Tier(int minYears, BigDecimal days) {
    }

    record Result(BigDecimal days, String basis) {
    }

    private EntitlementPolicy() {
    }

    static Result compute(BigDecimal defaultDays, List<Tier> tiers, boolean proRate, LocalDate joinDate, int year) {
        if (joinDate != null && joinDate.getYear() > year) {
            return new Result(BigDecimal.ZERO, "Not joined yet in " + year);
        }
        int years = 0;
        if (joinDate != null) {
            LocalDate yearStart = LocalDate.of(year, 1, 1);
            years = joinDate.isAfter(yearStart) ? 0 : Period.between(joinDate, yearStart).getYears();
        }
        final int service = years;
        Tier tier = tiers.stream().filter(t -> t.minYears() <= service).max(Comparator.comparingInt(Tier::minYears)).orElse(null);
        BigDecimal days = tier == null ? defaultDays : tier.days();
        String basis = tier == null ? "Company default" : tier.minYears() + "+ years of service";

        if (proRate && joinDate != null && joinDate.getYear() == year) {
            int months = 13 - joinDate.getMonthValue();
            days = halfDayFloor(days.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12), 4, RoundingMode.DOWN));
            basis += ", pro-rated from " + monthName(joinDate.getMonth()) + " (" + months + " of 12 months)";
        }
        return new Result(days, basis);
    }

    /** Carried days after expiry: what's left of them, and how many were lost. */
    record Carried(BigDecimal usable, BigDecimal expired, LocalDate expiresOn) {
    }

    /**
     * Carried days are used first. Once the expiry date has passed (as of {@code asOf}), only the carried days
     * already taken or booked on leave starting on or before that date still count; the rest are lost.
     * {@code expiryMonth} null means carried days never expire.
     */
    static Carried carried(BigDecimal carried, Integer expiryMonth, int year, BigDecimal usedByExpiry, LocalDate asOf) {
        if (expiryMonth == null || carried.signum() == 0) return new Carried(carried, BigDecimal.ZERO, null);
        LocalDate expiresOn = java.time.YearMonth.of(year, expiryMonth).atEndOfMonth();
        if (!asOf.isAfter(expiresOn)) return new Carried(carried, BigDecimal.ZERO, expiresOn);
        BigDecimal usable = carried.min(usedByExpiry.max(BigDecimal.ZERO));
        return new Carried(usable, carried.subtract(usable), expiresOn);
    }

    record Earned(BigDecimal usable, BigDecimal expired) {
    }

    /**
     * Earned leave (e.g. time off for overtime) after expiry. Carried days are used first, then earned leave, then the
     * entitlement; so of the leave taken or booked on or before the latest expiry date ({@code usedByExpiry}), what's
     * beyond the carried days came out of earned leave before it expired. The rest of the expired leave is lost.
     */
    static Earned earned(BigDecimal total, BigDecimal expired, BigDecimal usedByExpiry, BigDecimal carriedUsable) {
        if (expired.signum() == 0) return new Earned(total, BigDecimal.ZERO);
        BigDecimal usedInTime = expired.min(usedByExpiry.subtract(carriedUsable).max(BigDecimal.ZERO));
        BigDecimal lost = expired.subtract(usedInTime);
        return new Earned(total.subtract(lost), lost);
    }

    /** Rounds down to the nearest 0.5. */
    static BigDecimal halfDayFloor(BigDecimal value) {
        return value.multiply(BigDecimal.TWO).setScale(0, RoundingMode.DOWN).divide(BigDecimal.TWO, 1, RoundingMode.UNNECESSARY);
    }

    private static String monthName(Month m) {
        return m.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
