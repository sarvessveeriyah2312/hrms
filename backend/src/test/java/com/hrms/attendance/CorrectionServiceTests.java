package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** What a correction request must say, checked against the person's local "now": Thursday 5 Nov 2026, 10:00. */
class CorrectionServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final LocalDate JOINED = LocalDate.of(2026, 1, 1);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 4);

    private static CorrectionService.Input input(LocalDate date, String in, String out, String reason) {
        return new CorrectionService.Input(date, in == null ? null : LocalTime.parse(in), out == null ? null : LocalTime.parse(out), reason);
    }

    private static void refused(CorrectionService.Input in, String code) {
        ThrowingCallable call = () -> CorrectionService.check(in, JOINED, NOW);
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void acceptsAForgottenClockOut() {
        assertThatCode(() -> CorrectionService.check(input(YESTERDAY, null, "18:00", "Forgot to clock out"), JOINED, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> CorrectionService.check(input(NOW.toLocalDate(), "09:00", null, "Phone was flat"), JOINED, NOW)).doesNotThrowAnyException();
    }

    @Test
    void needsADayATimeAndAReason() {
        refused(input(null, "09:00", null, "x"), "DATE_REQUIRED");
        refused(input(YESTERDAY, null, null, "x"), "TIMES_REQUIRED");
        refused(input(YESTERDAY, "09:00", null, " "), "REASON_REQUIRED");
        refused(input(YESTERDAY, "09:00", null, "x".repeat(CorrectionService.MAX_REASON + 1)), "REASON_TOO_LONG");
    }

    @Test
    void theClockOutComesAfterTheClockIn() {
        refused(input(YESTERDAY, "18:00", "09:00", "x"), "BAD_TIMES");
        refused(input(YESTERDAY, "09:00", "09:00", "x"), "BAD_TIMES");
    }

    @Test
    void onlyDaysThatHappenedAndNotTooLongAgo() {
        refused(input(NOW.toLocalDate().plusDays(1), "09:00", null, "x"), "DATE_IN_FUTURE");
        refused(input(NOW.toLocalDate(), null, "18:00", "x"), "TIME_IN_FUTURE");
        refused(input(NOW.toLocalDate().minusDays(CorrectionService.MAX_AGE_DAYS + 1), "09:00", null, "x"), "CORRECTION_TOO_OLD");
        assertThatCode(() -> CorrectionService.check(input(NOW.toLocalDate().minusDays(CorrectionService.MAX_AGE_DAYS), "09:00", null, "x"), JOINED, NOW))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> CorrectionService.check(input(YESTERDAY, "09:00", null, "x"), NOW.toLocalDate(), NOW))
            .isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("BEFORE_JOINING"));
    }
}
