package com.hrms.notification;

import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your own notification settings (email and push on or off per kind of notification; in-app always on) and the
 * devices push notifications go to. Always the token's user.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationSettingsController {

    record Preference(String kind, boolean email, boolean push) {
    }

    record Device(String token, String platform, String model) {
    }

    private static final Set<String> PLATFORMS = Set.of("IOS", "ANDROID", "WEB");

    private final JdbcClient jdbc;

    NotificationSettingsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/preferences")
    List<Preference> preferences(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Preference> saved = jdbc.sql("select kind, email, push from notification_preference where user_id = :user")
            .param("user", user(jwt)).query(Preference.class).list().stream()
            .collect(Collectors.toMap(Preference::kind, Function.identity()));
        return Arrays.stream(Notifications.Kind.values())
            .map(k -> saved.getOrDefault(k.name(), new Preference(k.name(), true, true))).toList();
    }

    @PutMapping("/preferences")
    @Transactional
    List<Preference> save(@RequestBody List<Preference> body, @AuthenticationPrincipal Jwt jwt) {
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "PREFERENCES_REQUIRED", "Send the settings to save.");
        for (Preference p : body) {
            try {
                Notifications.Kind.valueOf(p.kind());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_KIND", "Unknown notification: " + p.kind());
            }
            jdbc.sql("""
                    insert into notification_preference (tenant_id, user_id, kind, email, push) values (:tenant, :user, :kind, :email, :push)
                    on conflict (user_id, kind) do update set email = excluded.email, push = excluded.push, updated_at = now()""")
                .param("tenant", TenantContext.require()).param("user", user(jwt)).param("kind", p.kind())
                .param("email", p.email()).param("push", p.push()).update();
        }
        return preferences(jwt);
    }

    /** The mobile app registers its push token after sign-in (and again when it changes). */
    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void register(@RequestBody Device body, @AuthenticationPrincipal Jwt jwt) {
        if (body == null || body.token() == null || body.token().isBlank() || body.token().length() > 4096) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOKEN_REQUIRED", "Send the device's push token.");
        }
        String platform = body.platform() == null ? "ANDROID" : body.platform().toUpperCase();
        if (!PLATFORMS.contains(platform)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_PLATFORM", "Platform must be IOS, ANDROID or WEB.");
        String model = body.model() == null ? null : body.model().substring(0, Math.min(body.model().length(), 100));
        int updated = jdbc.sql("update device set platform = :platform, model = :model, last_seen_at = now() where user_id = :user and push_token = :token")
            .param("platform", platform).param("model", model).param("user", user(jwt)).param("token", body.token()).update();
        if (updated == 0) {
            jdbc.sql("""
                    insert into device (tenant_id, user_id, platform, model, push_token, last_seen_at)
                    values (:tenant, :user, :platform, :model, :token, now())""")
                .param("tenant", TenantContext.require()).param("user", user(jwt)).param("platform", platform)
                .param("model", model).param("token", body.token()).update();
        }
    }

    /** On sign-out the app forgets its token here, so pushes stop. */
    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(@RequestParam String token, @AuthenticationPrincipal Jwt jwt) {
        jdbc.sql("update device set push_token = null where user_id = :user and push_token = :token")
            .param("user", user(jwt)).param("token", token).update();
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
