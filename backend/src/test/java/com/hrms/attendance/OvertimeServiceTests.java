package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** What an overtime request must say, checked against the person's local "today": Thursday 5 Nov 2026. */
class OvertimeServiceTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate JOINED = LocalDate.of(2026, 1, 1);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private static OvertimeService.Input input(LocalDate date, String hours, String rate, String reason) {
        return new OvertimeService.Input(date, hours == null ? null : new BigDecimal(hours), rate == null ? null : new BigDecimal(rate), reason);
    }

    private static void refused(OvertimeService.Input in, String code) {
        assertThatThrownBy(() -> OvertimeService.check(in, JOINED, TODAY))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static void accepted(OvertimeService.Input in) {
        assertThatCode(() -> OvertimeService.check(in, JOINED, TODAY)).doesNotThrowAnyException();
    }

    @Test
    void acceptsQuarterHours() {
        accepted(input(YESTERDAY, "2.5", null, "Stock count"));
        accepted(input(TODAY, "0.25", "1.5", "Server move"));
        accepted(input(YESTERDAY, "1.75", "1.5", "x"));
    }

    @Test
    void needsADayHoursARateAndAReason() {
        refused(input(null, "2", "1.5", "x"), "DATE_REQUIRED");
        refused(input(YESTERDAY, null, "1.5", "x"), "HOURS_REQUIRED");
        refused(input(YESTERDAY, "0", "1.5", "x"), "HOURS_REQUIRED");
        refused(input(YESTERDAY, "2", "1.5", " "), "REASON_REQUIRED");
        refused(input(YESTERDAY, "2", "1.5", "x".repeat(OvertimeService.MAX_REASON + 1)), "REASON_TOO_LONG");
    }

    @Test
    void hoursComeInQuartersUpToADay() {
        refused(input(YESTERDAY, "1.1", "1.5", "x"), "BAD_HOURS");
        refused(input(YESTERDAY, "24.25", "1.5", "x"), "BAD_HOURS");
        accepted(input(YESTERDAY, "24", "1.5", "x"));
    }

    @Test
    void onlyDaysThatHappenedAndNotTooLongAgo() {
        accepted(input(TODAY.plusDays(OvertimeService.MAX_AHEAD_DAYS), "2", "1.5", "x"));   // planned, approved before it's worked
        refused(input(TODAY.plusDays(OvertimeService.MAX_AHEAD_DAYS + 1), "2", "1.5", "x"), "DATE_TOO_FAR");
        refused(input(TODAY.minusDays(OvertimeService.MAX_AGE_DAYS + 1), "2", "1.5", "x"), "OVERTIME_TOO_OLD");
        accepted(input(TODAY.minusDays(OvertimeService.MAX_AGE_DAYS), "2", "1.5", "x"));
        assertThatThrownBy(() -> OvertimeService.check(input(YESTERDAY, "2", "1.5", "x"), TODAY, TODAY))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BEFORE_JOINING"));
    }

    @Test
    void aDayCantAddUpToMoreThan24Hours() {
        assertThatCode(() -> OvertimeService.checkDayTotal(new BigDecimal("20"), new BigDecimal("4"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> OvertimeService.checkDayTotal(new BigDecimal("20"), new BigDecimal("4.25")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOO_MANY_HOURS"));
    }

    @Test
    void readsWellInTheInbox() {
        assertThat(OvertimeApprovals.detail(new BigDecimal("2.50"), new BigDecimal("1.5"), "PAY", "WORKING", " Stock count "))
            .isEqualTo("2.5 h at 1.5× · Stock count");
        assertThat(OvertimeApprovals.detail(new BigDecimal("3.00"), new BigDecimal("2"), "PAY", "REST", null)).isEqualTo("3 h at 2.0× (rest day)");
        assertThat(OvertimeApprovals.detail(new BigDecimal("4"), new BigDecimal("1.75"), "TIME_OFF", "HOLIDAY", null))
            .isEqualTo("4 h as time off at 1.75× (public holiday)");
    }
}
