package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who may open whose timesheet. The @PreAuthorize permission gates are Spring's; this covers the scope checks inside. */
class TimesheetControllerTests {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final TimesheetService service = mock(TimesheetService.class);
    private final AttendanceFacts facts = mock(AttendanceFacts.class);
    private final PermissionChecker perm = mock(PermissionChecker.class);
    private final TimesheetController controller = new TimesheetController(service, facts, perm);

    private static Jwt jwt(UUID employeeId) {
        var b = Jwt.withTokenValue("t").header("alg", "none").subject("user");
        if (employeeId != null) b.claim("employee_id", employeeId.toString());
        return b.build();
    }

    private static void assertApiError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void myTimesheetNeedsAnEmployeeRecord() {
        assertApiError(() -> controller.mine(null, null, jwt(null)), HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD");
        controller.mine(null, null, jwt(ME));
        verify(service).sheet(ME, null, null);
    }

    @Test
    void anyoneMayOpenTheirOwnById() {
        when(perm.has("attendance:timesheets")).thenReturn(false);
        controller.person(ME, null, null, jwt(ME));
        verify(service).sheet(ME, null, null);
    }

    @Test
    void someoneElsesNeedsSeeTimesheets() {
        when(perm.has("attendance:timesheets")).thenReturn(false);
        when(facts.canSee(any(), any(), any())).thenReturn(true);
        assertApiError(() -> controller.person(OTHER, null, null, jwt(ME)), HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
        verify(service, never()).sheet(any(), any(), any());
    }

    @Test
    void peopleOutsideTheScopeLookMissing() {
        when(perm.has("attendance:timesheets")).thenReturn(true);
        when(perm.scopeOf("attendance:timesheets")).thenReturn(Optional.of(DataScope.TEAM));
        when(facts.canSee(ME, DataScope.TEAM, OTHER)).thenReturn(false);
        assertApiError(() -> controller.person(OTHER, null, null, jwt(ME)), HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
        verify(service, never()).sheet(any(), any(), any());
    }

    @Test
    void peopleInsideTheScopeOpen() {
        when(perm.has("attendance:timesheets")).thenReturn(true);
        when(perm.scopeOf("attendance:timesheets")).thenReturn(Optional.of(DataScope.DEPARTMENT));
        when(facts.canSee(ME, DataScope.DEPARTMENT, OTHER)).thenReturn(true);
        controller.person(OTHER, null, null, jwt(ME));
        verify(service).sheet(OTHER, null, null);
    }

    @Test
    void teamUsesTheTimesheetsScopeAndWorksWithoutAnEmployeeRecord() {
        when(perm.scopeOf("attendance:timesheets")).thenReturn(Optional.of(DataScope.TENANT));
        controller.team(null, null, jwt(null));
        verify(service).team(null, DataScope.TENANT, null, null);

        when(perm.scopeOf("attendance:timesheets")).thenReturn(Optional.empty());
        controller.team(null, null, jwt(ME));
        verify(service).team(ME, DataScope.SELF, null, null);
    }
}
