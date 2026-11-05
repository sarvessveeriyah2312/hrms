package com.hrms.shared.mail;

/**
 * Where and how to send: an SMTP server and the sender shown to recipients.
 * {@code security}: NONE (plain, e.g. Mailpit), STARTTLS (usually port 587) or SSL (implicit TLS, usually 465).
 */
public record MailServer(String host, int port, String security, String username, String password,
                         String fromName, String fromEmail, String replyTo) {
}
