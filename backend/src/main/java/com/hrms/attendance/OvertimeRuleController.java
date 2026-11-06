package com.hrms.attendance;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Overtime rules (paid or time off, and how hours become leave), company-wide or per entity or department. attendance:manage. */
@RestController
@RequestMapping("/api/v1/attendance/overtime-rules")
@PreAuthorize("@perm.has('attendance:manage')")
class OvertimeRuleController {

    private final OvertimeRules rules;

    OvertimeRuleController(OvertimeRules rules) {
        this.rules = rules;
    }

    @GetMapping
    OvertimeRules.Settings settings() {
        return rules.settings();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OvertimeRules.Rule create(@RequestBody OvertimeRules.RuleInput in, @AuthenticationPrincipal Jwt jwt) {
        return rules.save(actor(jwt), null, in);
    }

    @PutMapping("/{id}")
    OvertimeRules.Rule update(@PathVariable UUID id, @RequestBody OvertimeRules.RuleInput in, @AuthenticationPrincipal Jwt jwt) {
        return rules.save(actor(jwt), id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        rules.delete(actor(jwt), id);
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
