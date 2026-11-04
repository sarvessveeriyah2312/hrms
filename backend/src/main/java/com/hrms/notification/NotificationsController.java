package com.hrms.notification;

import com.hrms.shared.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own notifications. The recipient is always the token's user. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationsController {

    record Inbox(int unread, List<Notifications.Item> items) {
    }

    private final Notifications notifications;

    NotificationsController(Notifications notifications) {
        this.notifications = notifications;
    }

    private static UUID me(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    Inbox list(@RequestParam(defaultValue = "30") int limit, @AuthenticationPrincipal Jwt jwt) {
        int n = Math.max(1, Math.min(limit, 100));
        return new Inbox(notifications.unread(me(jwt)), notifications.forUser(me(jwt), n));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (!notifications.markRead(me(jwt), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "That notification doesn't exist.");
        }
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void readAll(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(me(jwt));
    }
}
