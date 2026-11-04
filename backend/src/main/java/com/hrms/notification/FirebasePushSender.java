package com.hrms.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import java.io.IOException;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Push via Firebase Cloud Messaging (Android, and iOS through APNs). Enabled with
 * {@code hrms.firebase.enabled=true}; credentials come from GOOGLE_APPLICATION_CREDENTIALS.
 */
@Component
@ConditionalOnProperty(name = "hrms.firebase.enabled", havingValue = "true")
class FirebasePushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushSender.class);

    private final FirebaseMessaging messaging;

    FirebasePushSender(@Value("${hrms.firebase.project-id:}") String projectId) throws IOException {
        FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault());
        if (!projectId.isBlank()) {
            options.setProjectId(projectId);
        }
        FirebaseApp app = FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp(options.build()) : FirebaseApp.getInstance();
        this.messaging = FirebaseMessaging.getInstance(app);
    }

    @Override
    public void send(PushMessage m) {
        var data = new HashMap<String, String>(m.data() == null ? java.util.Map.of() : m.data());
        data.put("event", m.event());
        data.put("deep_link", m.deepLink());
        try {
            messaging.send(Message.builder()
                .setToken(m.deviceToken())
                .setNotification(Notification.builder().setTitle(m.title()).setBody(m.body()).build())
                .putAllData(data)
                .build());
        } catch (FirebaseMessagingException e) {
            // Never log the token or message body: they can identify a person.
            log.warn("FCM send failed: {}", e.getMessagingErrorCode());
        }
    }
}
