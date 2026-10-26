package com.aloute.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void trustsForwardedChainOnlyWhenImmediatePeerIsTrusted() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8,127.0.0.1/32");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "198.51.100.27, 10.0.0.6");

        assertEquals("198.51.100.27", resolver.resolve(request));
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedCaller() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.90");

        assertEquals("198.51.100.10", resolver.resolve(request));
    }
    @Test
    void ignoresSpoofedLeftmostForwardedAddressWhenTrustedProxyChainIsPresent() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        // A caller may supply the leftmost value; the trusted edge proxy appends the actual client
        // and a second trusted hop. Resolve from the nearest hop outward and stop at the first
        // untrusted address, not at the attacker-controlled leftmost value.
        request.addHeader("X-Forwarded-For", "203.0.113.77, 198.51.100.27, 10.0.0.6");

        assertEquals("198.51.100.27", resolver.resolve(request));
    }

    @Test
    void refusesInvalidTrustedProxyConfiguration() {
        assertThrows(IllegalStateException.class, () -> new ClientIpResolver("10.0.0.0/99"));
    }

}
