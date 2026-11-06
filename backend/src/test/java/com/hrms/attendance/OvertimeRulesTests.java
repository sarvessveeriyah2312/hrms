package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Overtime rules: which one applies, how hours become leave, when it expires, and paid vs time off. */
class OvertimeRulesTests {

    private static final UUID LEAVE = UUID.randomUUID();
    private static final UUID ENTITY = UUID.randomUUID();
    private static final UUID DEPT = UUID.randomUUID();

    private static OvertimeRules.Rule rule(String scope, UUID entity, UUID dept, String comp, String conversion, String unit,
                                           String hoursPerUnit, boolean applyRate, String expiry, Integer days) {
        return new OvertimeRules.Rule(UUID.randomUUID(), scope, entity, null, dept, null, comp, conversion, unit,
            new BigDecimal(hoursPerUnit), applyRate, "PAY".equals(comp) ? null : LEAVE, "Replacement leave", expiry, days, null,
            new BigDecimal("1.5"), new BigDecimal("2.0"), new BigDecimal("3.0"), "AUTO", false);
    }

    /** Your company's rule: banked, 4 hours = half a day, rate ignored. */
    private static final OvertimeRules.Rule HALF_DAY_PER_4H = rule("COMPANY", null, null, "TIME_OFF", "BANKED", "HALF_DAY", "4", false, "NONE", null);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void bankedHoursAddUpAndTheRestWaits() {
        var first = OvertimeRules.convert(HALF_DAY_PER_4H, d("2"), null, d("0"));
        assertThat(first.days()).isEqualByComparingTo("0");
        assertThat(first.bankAfter()).isEqualByComparingTo("2");

        var second = OvertimeRules.convert(HALF_DAY_PER_4H, d("2.5"), null, first.bankAfter());
        assertThat(second.days()).isEqualByComparingTo("0.5");
        assertThat(second.bankAfter()).isEqualByComparingTo("0.5");

        var long_ = OvertimeRules.convert(HALF_DAY_PER_4H, d("9"), null, d("0"));
        assertThat(long_.days()).isEqualByComparingTo("1.0");
        assertThat(long_.bankAfter()).isEqualByComparingTo("1");
    }

    @Test
    void perRequestHoursConvertOnTheirOwnAndShortHoursAreNotGiven() {
        var r = rule("COMPANY", null, null, "TIME_OFF", "PER_REQUEST", "HALF_DAY", "4", false, "NONE", null);
        var short_ = OvertimeRules.convert(r, d("3.5"), null, d("10"));
        assertThat(short_.days()).isEqualByComparingTo("0");
        assertThat(short_.lostHours()).isEqualByComparingTo("3.5");
        assertThat(short_.bankAfter()).isEqualByComparingTo("10");   // the bank isn't used
        assertThat(OvertimeRules.convert(r, d("6"), null, d("0")).days()).isEqualByComparingTo("0.5");
    }

    @Test
    void fullDaysAndTheRate() {
        var fullDays = rule("COMPANY", null, null, "TIME_OFF", "BANKED", "FULL_DAY", "8", true, "NONE", null);
        var holiday = OvertimeRules.convert(fullDays, d("4"), d("2.0"), d("0"));
        assertThat(holiday.countedHours()).isEqualByComparingTo("8");
        assertThat(holiday.days()).isEqualByComparingTo("1");
        // The rate is ignored when the rule doesn't apply it.
        assertThat(OvertimeRules.convert(HALF_DAY_PER_4H, d("4"), d("2.0"), d("0")).countedHours()).isEqualByComparingTo("4");
    }

    @Test
    void expiry() {
        LocalDate earned = LocalDate.of(2026, 11, 6);
        assertThat(OvertimeRules.expiresOn(HALF_DAY_PER_4H, earned)).isNull();
        assertThat(OvertimeRules.expiresOn(rule("COMPANY", null, null, "TIME_OFF", "BANKED", "HALF_DAY", "4", false, "DAYS", 90), earned))
            .isEqualTo(LocalDate.of(2027, 2, 4));
        assertThat(OvertimeRules.expiresOn(rule("COMPANY", null, null, "TIME_OFF", "BANKED", "HALF_DAY", "4", false, "YEAR_END", null), earned))
            .isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void departmentBeatsEntityBeatsCompanyBeatsPaid() {
        var company = rule("COMPANY", null, null, "PAY", "BANKED", "HALF_DAY", "4", false, "NONE", null);
        var entity = rule("ENTITY", ENTITY, null, "TIME_OFF", "BANKED", "HALF_DAY", "4", false, "NONE", null);
        var dept = rule("DEPARTMENT", null, DEPT, "CHOICE", "BANKED", "HALF_DAY", "4", false, "NONE", null);
        List<OvertimeRules.Rule> all = List.of(company, entity, dept);

        assertThat(OvertimeRules.pick(all, ENTITY, DEPT)).isEqualTo(dept);
        assertThat(OvertimeRules.pick(all, ENTITY, UUID.randomUUID())).isEqualTo(entity);
        assertThat(OvertimeRules.pick(all, UUID.randomUUID(), null)).isEqualTo(company);
        assertThat(OvertimeRules.pick(List.of(), ENTITY, DEPT)).isEqualTo(OvertimeRules.DEFAULT);
        assertThat(OvertimeRules.DEFAULT.allowsTimeOff()).isFalse();
    }

    private static OvertimeService.Input asking(String compensation) {
        return new OvertimeService.Input(LocalDate.of(2026, 11, 4), d("4"), null, "x", compensation);
    }

    private static void refused(OvertimeRules.Rule r, String compensation, String code) {
        assertThatThrownBy(() -> OvertimeService.compensation(r, asking(compensation)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void paidTimeOffOrTheEmployeesChoice() {
        assertThat(OvertimeService.compensation(OvertimeRules.DEFAULT, asking(null))).isEqualTo("PAY");
        refused(OvertimeRules.DEFAULT, "TIME_OFF", "PAY_ONLY");

        assertThat(OvertimeService.compensation(HALF_DAY_PER_4H, asking(null))).isEqualTo("TIME_OFF");
        refused(HALF_DAY_PER_4H, "PAY", "TIME_OFF_ONLY");

        var choice = rule("COMPANY", null, null, "CHOICE", "BANKED", "HALF_DAY", "4", false, "NONE", null);
        assertThat(OvertimeService.compensation(choice, asking("time_off"))).isEqualTo("TIME_OFF");
        assertThat(OvertimeService.compensation(choice, asking("PAY"))).isEqualTo("PAY");
        refused(choice, null, "COMPENSATION_REQUIRED");
        refused(choice, "CASH", "BAD_COMPENSATION");
    }

    @Test
    void theRateIsNeededWhenPaidOrAppliedToTimeOff() {
        assertThat(OvertimeService.rateNeeded(HALF_DAY_PER_4H, "PAY")).isTrue();
        assertThat(OvertimeService.rateNeeded(HALF_DAY_PER_4H, "TIME_OFF")).isFalse();
        var withRate = rule("COMPANY", null, null, "TIME_OFF", "BANKED", "HALF_DAY", "4", true, "NONE", null);
        assertThat(OvertimeService.rateNeeded(withRate, "TIME_OFF")).isTrue();
        // Time off without a rate passes the checks.
        OvertimeService.check(asking("TIME_OFF"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 5));
    }

    private static OvertimeRules.Rule rates(String mode, String working, String rest, String holiday) {
        return new OvertimeRules.Rule(null, "COMPANY", null, null, null, null, "PAY", "BANKED", "HALF_DAY", d("4"), false,
            null, null, "NONE", null, null, d(working), d(rest), d(holiday), mode, false);
    }

    @Test
    void automaticRatesComeFromTheKindOfDay() {
        var r = rates("AUTO", "1.5", "1.75", "3");
        assertThat(OvertimeRules.rateFor(r, OvertimeRules.DayKind.WORKING, null)).isEqualByComparingTo("1.5");
        assertThat(OvertimeRules.rateFor(r, OvertimeRules.DayKind.REST, null)).isEqualByComparingTo("1.75");
        // What the employee sends is ignored.
        assertThat(OvertimeRules.rateFor(r, OvertimeRules.DayKind.HOLIDAY, d("1"))).isEqualByComparingTo("3");
    }

    @Test
    void employeesPickOneOfTheRulesRates() {
        var r = rates("EMPLOYEE", "1.5", "2", "3");
        assertThat(OvertimeRules.rateFor(r, OvertimeRules.DayKind.WORKING, d("2.0"))).isEqualByComparingTo("2");
        assertThat(OvertimeRules.rateFor(r, OvertimeRules.DayKind.REST, null)).isEqualByComparingTo("2");   // none picked: the day's
        assertThatThrownBy(() -> OvertimeRules.rateFor(r, OvertimeRules.DayKind.WORKING, d("2.5")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BAD_RATE"));
    }

    @Test
    void ratesAreOneToFiveInQuarters() {
        assertThat(OvertimeRules.validRate(d("1"))).isTrue();
        assertThat(OvertimeRules.validRate(d("1.75"))).isTrue();
        assertThat(OvertimeRules.validRate(d("5.0"))).isTrue();
        assertThat(OvertimeRules.validRate(d("0.5"))).isFalse();
        assertThat(OvertimeRules.validRate(d("1.1"))).isFalse();
        assertThat(OvertimeRules.validRate(d("5.25"))).isFalse();
    }

    @Test
    void theKindOfDay() {
        var week = java.util.EnumSet.range(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY);
        assertThat(OvertimeService.kindOf(false, week, LocalDate.of(2026, 11, 6))).isEqualTo(OvertimeRules.DayKind.WORKING);   // Fri
        assertThat(OvertimeService.kindOf(false, week, LocalDate.of(2026, 11, 7))).isEqualTo(OvertimeRules.DayKind.REST);      // Sat
        assertThat(OvertimeService.kindOf(true, week, LocalDate.of(2026, 11, 7))).isEqualTo(OvertimeRules.DayKind.HOLIDAY);
    }

    @Test
    void preApprovalMeansAskingBeforeTheDay() {
        var r = new OvertimeRules.Rule(null, "COMPANY", null, null, null, null, "PAY", "BANKED", "HALF_DAY", d("4"), false,
            null, null, "NONE", null, null, d("1.5"), d("2"), d("3"), "AUTO", true);
        LocalDate today = LocalDate.of(2026, 11, 5);
        OvertimeService.checkPreApproval(r, today, today);
        OvertimeService.checkPreApproval(r, today.plusDays(3), today);
        assertThatThrownBy(() -> OvertimeService.checkPreApproval(r, today.minusDays(1), today))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PRE_APPROVAL_REQUIRED"));
        // Without the rule, a past day is fine.
        OvertimeService.checkPreApproval(OvertimeRules.DEFAULT, today.minusDays(1), today);
    }
}
