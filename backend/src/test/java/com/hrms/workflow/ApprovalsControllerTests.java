package com.hrms.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** Approving several at once: each is decided on its own, and one that fails doesn't stop the others. */
class ApprovalsControllerTests {

    private final WorkflowService workflow = mock(WorkflowService.class);
    private final PermissionChecker perm = mock(PermissionChecker.class);
    private final ApprovalsController controller = new ApprovalsController(workflow, perm);
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user")
        .claim("employee_id", UUID.randomUUID().toString()).build();

    @Test
    void approvesEachAndReportsTheOnesThatFailed() {
        when(perm.allScopes()).thenReturn(Map.of());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.CONFLICT, "REQUEST_DECIDED", "Someone has already decided this request."))
            .when(workflow).decide(any(), eq(b), eq(WorkflowService.Decision.APPROVE), any());

        var result = controller.approveMany(new ApprovalsController.ApproveMany(List.of(a, b, c, a), "OK"), jwt);

        assertThat(result.approved()).containsExactly(a, c);
        assertThat(result.failed()).singleElement().satisfies(f -> {
            assertThat(f.instanceId()).isEqualTo(b);
            assertThat(f.code()).isEqualTo("REQUEST_DECIDED");
        });
        verify(workflow).decide(any(), eq(a), eq(WorkflowService.Decision.APPROVE), eq("OK"));
    }

    @Test
    void needsSomethingAndNotTooMuch() {
        assertThatThrownBy(() -> controller.approveMany(new ApprovalsController.ApproveMany(List.of(), null), jwt))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOTHING_CHOSEN"));
        var many = Collections.nCopies(ApprovalsController.MAX_AT_ONCE + 1, 0).stream().map(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> controller.approveMany(new ApprovalsController.ApproveMany(many, null), jwt))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOO_MANY"));
    }
}
