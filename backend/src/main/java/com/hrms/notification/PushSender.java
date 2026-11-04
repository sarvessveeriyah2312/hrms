package com.hrms.notification;

import java.util.Map;

/**
 * Sends a push notification to one device. Implementations: Firebase Cloud Messaging
 * ({@link FirebasePushSender}) or a logging stub for local development.
 * Every message carries a typed event and a deep link; clients never route by message text.
 */
public interface PushSender {

    record PushMessage(String deviceToken, String title, String body, String event, String deepLink,
                       Map<String, String> data) {
    }

    void send(PushMessage message);
}
