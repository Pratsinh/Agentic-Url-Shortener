package com.ascs.shortener.domain;

import com.ascs.shortener.domain.ShortenerException.InvalidRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates URLs accepted for shortening. Security posture:
 * only http/https, no embedded credentials, and no internal/loopback/private/link-local hosts
 * (SSRF + open-redirect-into-intranet guard). No DNS resolution is performed, so DNS rebinding
 * is NOT covered here (documented limitation).
 */
public class UrlValidator {

    public static final int MAX_LENGTH = 2048;
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern QUAD = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** @return the trimmed, validated URL */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) throw invalid("url is required");
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) throw invalid("url exceeds " + MAX_LENGTH + " characters");

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("url is malformed");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw invalid("only http and https urls are allowed");
        }
        if (uri.getUserInfo() != null) throw invalid("credentials in url are not allowed");

        String host = uri.getHost();
        if (host == null) throw invalid("url must contain a host"); // URI reports an absent or unparseable host as null
        if (isInternalHost(host)) throw invalid("internal or private hosts are not allowed");
        return url;
    }

    /** Package-private for direct tests: java.net.URI already rejects some of these forms, this check must not rely on it. */
    static boolean isInternalHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.startsWith("[")) return true; // IPv6 literals rejected outright (simple, safe)

        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".localdomain")) {
            return true;
        }

        String[] labels = host.split("\\.");
        String tld = labels[labels.length - 1];
        boolean numericHost = DIGITS.matcher(tld).matches() || tld.startsWith("0x");
        if (!numericHost) return false; // a real DNS name: TLDs are never numeric

        var m = QUAD.matcher(host);
        if (!m.matches()) return true; // decimal/hex/short-form IP tricks (e.g. 2130706433, 0x7f.1, 127.1)
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        int c = Integer.parseInt(m.group(3)), d = Integer.parseInt(m.group(4));
        if (a > 255 || b > 255 || c > 255 || d > 255) return true;
        return a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 169 && b == 254)             // link-local, incl. cloud metadata 169.254.169.254
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127); // CGNAT
    }

    private static InvalidRequest invalid(String msg) {
        return new InvalidRequest(msg);
    }
}
