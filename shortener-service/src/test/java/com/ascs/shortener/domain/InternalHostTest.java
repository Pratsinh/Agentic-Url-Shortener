package com.ascs.shortener.domain;

import com.ascs.Verifies;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SSRF host check on its own. java.net.URI happens to reject some of these forms before they reach it,
 * but the guard must hold even if the parser changes, so it is verified directly.
 */
class InternalHostTest {

    @Test
    @Verifies({"AC-1.3"})
    void outOfRangeOctetsAndHexLabelsAreTreatedAsInternal() {
        for (String host : new String[]{"256.1.1.1", "1.256.1.1", "1.1.256.1", "1.1.1.256", "1.2.3.0x7f", "0x7f", "127.1"}) {
            assertTrue(UrlValidator.isInternalHost(host), host);
        }
        assertFalse(UrlValidator.isInternalHost("93.184.216.34"));
        assertFalse(UrlValidator.isInternalHost("example.com"));
    }
}
