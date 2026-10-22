package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.domain.ShortenerException;
import com.ascs.shortener.domain.UrlValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    private void assertRejected(String url) {
        assertThrows(ShortenerException.InvalidRequest.class, () -> validator.validate(url));
    }

    @Test
    @Verifies({"AC-1.1"})
    void acceptsAndTrimsHttpAndHttpsUrls() {
        assertEquals("https://example.com/a?b=c#d", validator.validate("  https://example.com/a?b=c#d "));
        assertEquals("http://sub.example.org", validator.validate("http://sub.example.org"));
    }

    @Test
    @Verifies({"AC-1.2"})
    void rejectsMissingBlankOrOversizedInput() {
        assertRejected(null);
        assertRejected("   ");
        assertRejected("https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH));
    }

    @Test
    @Verifies({"AC-1.2"})
    void rejectsNonHttpSchemesAndMalformedUrls() {
        assertRejected("ftp://example.com");
        assertRejected("javascript:alert(1)");
        assertRejected("data:text/html,<script>1</script>");
        assertRejected("example.com/no-scheme");
        assertRejected("http://");
        assertRejected("http://exa mple.com");
    }

    @Test
    @Verifies({"AC-1.2"})
    void rejectsEmbeddedCredentials() {
        assertRejected("https://user:pass@example.com");
    }

    @Test
    @Verifies({"AC-1.3"})
    void rejectsLoopbackPrivateAndMetadataTargets() {
        assertRejected("http://localhost/admin");
        assertRejected("http://LOCALHOST./admin");
        assertRejected("http://app.internal/x");
        assertRejected("http://printer.local/x");
        assertRejected("http://127.0.0.1/");
        assertRejected("http://10.1.2.3/");
        assertRejected("http://172.16.0.1/");
        assertRejected("http://172.31.255.255/");
        assertRejected("http://192.168.1.1/");
        assertRejected("http://169.254.169.254/latest/meta-data");
        assertRejected("http://0.0.0.0/");
        assertRejected("http://[::1]/");
    }

    @Test
    @Verifies({"AC-1.3"})
    void rejectsObfuscatedIpForms() {
        assertRejected("http://2130706433/");   // decimal 127.0.0.1
        assertRejected("http://0x7f.0.0.1/");   // hex octet
        assertRejected("http://127.1/");        // short form
        assertRejected("http://999.1.1.1/");    // not a valid quad
        assertRejected("http://1.999.1.1/");
        assertRejected("http://1.1.999.1/");
        assertRejected("http://1.1.1.999/");
        assertRejected("http://1.2.3.0x7f/");   // hex final label
    }

    @Test
    @Verifies({"AC-1.3"})
    void everyInternalSuffixAndRangeBoundaryIsHonoured() {
        assertRejected("http://api.localhost/");
        assertRejected("http://nas.localdomain/");
        assertRejected("http://100.64.0.1/");    // CGNAT start
        assertRejected("http://100.127.255.1/"); // CGNAT end
        assertRejected("http://224.0.0.1/");     // multicast and above
        for (String ok : new String[]{"http://100.63.0.1/", "http://100.128.0.1/", "http://169.253.1.1/",
                "http://172.15.0.1/", "http://172.32.0.1/", "http://192.167.1.1/", "http://223.255.255.255/",
                "http://11.0.0.1/", "http://126.0.0.1/", "http://192.169.0.1/", "http://169.0.0.1/", "http://100.0.0.1/"}) {
            assertDoesNotThrow(() -> validator.validate(ok), ok);
        }
    }

    @Test
    @Verifies({"AC-1.3"})
    void acceptsPublicIpsAndPrivateLookalikes() {
        assertEquals("http://8.8.8.8/", validator.validate("http://8.8.8.8/"));
        assertEquals("http://172.32.0.1/", validator.validate("http://172.32.0.1/")); // just outside 172.16/12
        assertEquals("https://facade.de/", validator.validate("https://facade.de/")); // hex-looking letters, real TLD
    }
}
