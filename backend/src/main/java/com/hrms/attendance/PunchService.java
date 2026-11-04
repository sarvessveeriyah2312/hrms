package com.hrms.attendance;

import com.hrms.attendance.PunchController.ClockRequest;
import com.hrms.attendance.PunchController.ClockResponse;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PunchService {

    /** Published after every saved punch; other modules (reporting, notification) listen. */
    public record PunchRecorded(UUID tenantId, UUID employeeId, UUID punchId, Punch.Type type, Punch.Result result) {
    }

    private final PunchRepository punches;
    private final ClockRuleEngine rules;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PunchService(PunchRepository punches, ClockRuleEngine rules, ApplicationEventPublisher events) {
        this.punches = punches;
        this.rules = rules;
        this.events = events;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    ClockResponse clock(UUID employeeId, ClockRequest req, String idempotencyKey) {
        // TODO(idempotency): look up idempotencyKey in Redis and return the stored response on retries.
        UUID tenantId = TenantContext.require();
        Instant now = Instant.now(clock);

        // TODO(org module): load the employee's assigned sites and today's WFH status.
        List<ClockRuleEngine.Site> sites = List.of();
        boolean wfhDay = false;

        var evidence = new ClockRuleEngine.Evidence(req.lat(), req.lng(), req.accuracyM(), req.wifiBssid(),
            req.clientTime(), req.mockLocation(), req.rootedOrJailbroken(), req.developerMode(), wfhDay);
        var decision = rules.evaluate(evidence, sites, now);

        if (decision.result() == Punch.Result.BLOCKED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, decision.code(),
                "You are " + decision.distanceM() + " m from the nearest allowed site.");
        }

        var punch = new Punch(tenantId, employeeId, req.type(), now, req.clientTime(),
            req.lat() == null ? null : BigDecimal.valueOf(req.lat()),
            req.lng() == null ? null : BigDecimal.valueOf(req.lng()),
            req.accuracyM(), decision.siteId(), req.wifiBssid(),
            decision.flags().toArray(String[]::new), decision.result(),
            req.source() == null ? Punch.Source.MOBILE : req.source(), req.remarks());
        punches.save(punch);
        events.publishEvent(new PunchRecorded(tenantId, employeeId, punch.getId(), punch.getType(), punch.getResult()));

        return new ClockResponse(punch.getId(), punch.getResult(), now, punch.getSiteId(), decision.flags(),
            decision.distanceM(), null);
    }
}
