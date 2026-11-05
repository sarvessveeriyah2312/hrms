package com.hrms.shared.mail;

import com.hrms.shared.web.ApiException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Checks a company's SMTP settings before they're saved or used. Because an admin decides where the server
 * connects, only mail ports are allowed and hosts that resolve to private, local or link-local addresses are
 * refused (so the page can't be used to probe the internal network), unless hrms.mail.allow-private-hosts is on.
 */
@Component
public class MailGuard {

    static final Set<Integer> PORTS = Set.of(25, 465, 587, 2525);
    /** Mailpit and similar local test servers; only with private hosts allowed. */
    static final int LOCAL_TEST_PORT = 1025;
    private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,253}\\.[A-Za-z]{2,}$");

    private final boolean allowPrivate;

    MailGuard(@Value("${hrms.mail.allow-private-hosts:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public boolean allowsPrivateHosts() {
        return allowPrivate;
    }

    /** Normalised host, or a 400 naming the field. */
    public String host(String value) {
        String h = value == null ? "" : value.trim().toLowerCase();
        if (h.contains("://") || h.contains("/") || h.contains(":")) {
            throw invalid("host", "Enter just the server name, e.g. smtp.office365.com (no http:// or port).");
        }
        try {
            h = IDN.toASCII(h);
        } catch (IllegalArgumentException e) {
            throw invalid("host", "That isn't a valid server name.");
        }
        if (!HOST.matcher(h).matches()) throw invalid("host", "That isn't a valid server name.");
        return h;
    }

    public int port(Integer value) {
        if (value == null) throw invalid("port", "Enter the port: usually 587, or 465 for SSL.");
        if (PORTS.contains(value) || (allowPrivate && value == LOCAL_TEST_PORT)) return value;
        throw invalid("port", "Use a mail port: 587 (STARTTLS), 465 (SSL), 25 or 2525.");
    }

    public String security(String value) {
        String s = value == null ? "STARTTLS" : value.trim().toUpperCase();
        if (!Set.of("NONE", "STARTTLS", "SSL").contains(s)) throw invalid("security", "Choose None, STARTTLS or SSL/TLS.");
        return s;
    }

    public String email(String field, String value, boolean required) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            if (required) throw invalid(field, "Enter an email address.");
            return null;
        }
        if (!EMAIL.matcher(v).matches()) throw invalid(field, "That isn't a valid email address.");
        return v;
    }

    /** Called right before connecting: the name must resolve, and (in production) only to public addresses. */
    public void reachable(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw invalid("host", "Couldn't find a server called " + host + ". Check the spelling.");
        }
        if (allowPrivate) return;
        for (InetAddress a : addresses) {
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isMulticastAddress() || isUniqueLocalV6(a) || isCarrierNat(a)) {
                throw invalid("host", "That server is on a private network address, which isn't allowed. Use your provider's public SMTP server.");
            }
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAIL_SETTING", message).with("fields", Map.of(field, message));
    }
}
