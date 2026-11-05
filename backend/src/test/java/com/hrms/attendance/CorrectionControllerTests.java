package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who may open a correction: its owner, or someone whose timesheets or approve scope covers the owner. */
class CorrectionControllerTests {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    private final CorrectionService service = mock(CorrectionService.class);
    private final AttendanceFacts facts = mock(AttendanceFacts.class);
    private final PermissionChecker perm = mock(PermissionChecker.class);
    private final CorrectionController controller = new CorrectionController(service, facts, perm);

    private static Jwt jwt(UUID employeeId) {
        var b = Jwt.withTokenValue("t").header("alg", "none").subject("user");
        if (employeeId != null) b.claim("employee_id", employeeId.toString());
        return b.build();
    }

    @BeforeEach
    void detail() {
        var c = new CorrectionService.Correction(ID, OWNER, "Aina", "E001", LocalDate.of(2026, 11, 4), null, LocalTime.of(18, 0),
            "Forgot", "PENDING", null, null, null, null, true, false);
        when(service.detail(ID)).thenReturn(new CorrectionService.Detail(c, new CorrectionService.Recorded(null, null, List.of()), List.of()));
    }

    private void refused(UUID me) {
        assertThatThrownBy(() -> controller.detail(ID, jwt(me)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CORRECTION_NOT_FOUND"));
    }

    @Test
    void theOwnerSeesIt() {
        assertThat(controller.detail(ID, jwt(OWNER)).correction().id()).isEqualTo(ID);
    }

    @Test
    void othersNeedAScopeThatCoversTheOwner() {
        refused(ME);

        when(perm.has("attendance:approve")).thenReturn(true);
        when(perm.scopeOf("attendance:approve")).thenReturn(Optional.of(DataScope.TEAM));
        when(facts.canSee(ME, DataScope.TEAM, OWNER)).thenReturn(false);
        refused(ME);

        when(facts.canSee(ME, DataScope.TEAM, OWNER)).thenReturn(true);
        assertThat(controller.detail(ID, jwt(ME)).correction().id()).isEqualTo(ID);
    }

    @Test
    void timesheetViewersSeeItToo() {
        when(perm.has("attendance:timesheets")).thenReturn(true);
        when(perm.scopeOf("attendance:timesheets")).thenReturn(Optional.of(DataScope.TENANT));
        when(facts.canSee(ME, DataScope.TENANT, OWNER)).thenReturn(true);
        assertThat(controller.detail(ID, jwt(ME)).correction().id()).isEqualTo(ID);
    }

    @Test
    void askingNeedsAnEmployeeRecord() {
        assertThatThrownBy(() -> controller.mine(jwt(null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NO_EMPLOYEE_RECORD"));
    }
}
