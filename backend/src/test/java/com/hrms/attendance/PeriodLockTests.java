package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hrms.shared.web.ApiException;
import com.hrms.workflow.ApprovalSubject.Outcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A locked month refuses approvals that would change it; rejecting still works. And the report's numbers. */
class PeriodLockTests {

    private static final UUID ID = UUID.randomUUID();
    private static final LocalDate OCT = LocalDate.of(2026, 10, 14);

    @Test
    void theMessageNamesTheMonthAndWhatCantChange() {
        var e = PeriodLocks.locked(YearMonth.of(2026, 10), "overtime");
        assertThat(e.code()).isEqualTo("PERIOD_LOCKED");
        assertThat(e.getMessage()).isEqualTo("October 2026 is locked for payroll, so its overtime can't change. Ask HR to unlock it.");
    }

    private static OvertimeService.Overtime overtime() {
        return new OvertimeService.Overtime(ID, UUID.randomUUID(), "Aina", "E001", OCT, new BigDecimal("2"), new BigDecimal("1.5"),
            "x", "PENDING", null, null, null, null, true, false, "PAY", null, null, null, "WORKING", false);
    }

    @Test
    void approvingOvertimeInALockedMonthIsRefusedBeforeAnythingChanges() {
        JdbcClient jdbc = mock(JdbcClient.class);
        OvertimeService service = mock(OvertimeService.class);
        PeriodLocks locks = mock(PeriodLocks.class);
        when(service.find(ID)).thenReturn(Optional.of(overtime()));
        doThrow(PeriodLocks.locked(YearMonth.of(2026, 10), "overtime")).when(locks).requireOpen(eq(OCT), any());
        var approvals = new OvertimeApprovals(jdbc, service, mock(OvertimeTimeOff.class), locks);

        assertThatThrownBy(() -> approvals.onFinished(ID, Outcome.APPROVED, null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PERIOD_LOCKED"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void approvingACorrectionInALockedMonthIsRefused() {
        JdbcClient jdbc = mock(JdbcClient.class);
        CorrectionService service = mock(CorrectionService.class);
        PeriodLocks locks = mock(PeriodLocks.class);
        when(service.find(ID)).thenReturn(Optional.of(new CorrectionService.Correction(ID, UUID.randomUUID(), "Aina", "E001", OCT,
            null, java.time.LocalTime.of(18, 0), "x", "PENDING", null, null, null, null, true, false)));
        doThrow(PeriodLocks.locked(YearMonth.of(2026, 10), "attendance")).when(locks).requireOpen(eq(OCT), any());
        var approvals = new CorrectionApprovals(jdbc, service, locks);

        assertThatThrownBy(() -> approvals.onFinished(ID, Outcome.APPROVED, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void reportNumbers() {
        assertThat(AttendanceReport.minutes(new BigDecimal("2.25"))).isEqualTo(135);
        assertThat(AttendanceReportController.hours(450)).isEqualTo("7.5");
        assertThat(AttendanceReportController.hours(0)).isEqualTo("0");
        assertThat(AttendanceReportController.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(AttendanceReportController.cell("Ops, KL")).isEqualTo("\"Ops, KL\"");
    }
}
