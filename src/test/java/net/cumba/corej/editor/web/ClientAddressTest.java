package net.cumba.corej.editor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The per-client key of the rate limits (security review M4): the forwarded address only behind a
 * TRUSTED proxy, the right-most untrusted hop, IPv6 by its /64, and never a name lookup.
 */
@SuppressWarnings("PMD.AvoidUsingHardCodedIP") // documentation ranges (RFC 5737, RFC 3849)
class ClientAddressTest
{

    @Test
    void withoutTrustedProxiesThePeerIsTheClient()
    {
        ClientAddress c = new ClientAddress(List.of());
        assertEquals("ipv4:203.0.113.7", c.key("203.0.113.7", List.of("198.51.100.1")),
                "a forwarded header from an untrusted peer is the client's own claim");
    }


    @Test
    void behindATrustedProxyTheRightMostUntrustedHopIsTheClient()
    {
        ClientAddress c = new ClientAddress(List.of("172.16.0.0/12", "10.0.0.1"));
        // nginx appends the peer it saw: spoofed entries sit LEFT of it and are never believed
        assertEquals("ipv4:203.0.113.7", c.key("172.18.0.3", List.of("1.2.3.4, 203.0.113.7")));
        assertEquals("ipv4:203.0.113.7",
                c.key("172.18.0.3", List.of("1.2.3.4", "203.0.113.7, 10.0.0.1")),
                "a second trusted hop is skipped; several header lines are one list");
        assertEquals("ipv4:172.18.0.3", c.key("172.18.0.3", List.of()),
                "no header: the proxy itself");
        assertEquals("ipv4:172.18.0.3", c.key("172.18.0.3", List.of("evil.example.org")),
                "a name is no address: never resolved, never believed");
        assertEquals("ipv4:172.31.255.255", c.key("172.31.255.255", List.of()));
        assertEquals("ipv4:172.32.0.1", c.key("172.32.0.1", List.of("203.0.113.7")),
                "outside the /12: not trusted");
    }


    @Test
    void ipv6ClientsAreBucketedByTheirSlash64()
    {
        ClientAddress c = new ClientAddress(List.of("::1"));
        assertEquals("ipv6:20010db800010002::/64", c.key("2001:db8:1:2::1", List.of()));
        assertEquals("ipv6:20010db800010002::/64", c.key("2001:db8:1:2:ffff::9", List.of()),
                "a rotated interface id is the same client");
        assertEquals("ipv6:20010db800010003::/64", c.key("2001:db8:1:3::1", List.of()));
        assertEquals("ipv6:20010db800010002::/64", c.key("::1", List.of("[2001:db8:1:2::5]")));
    }


    @Test
    void theRequestIsReadThroughTheServletApi()
    {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.1");
        r.addHeader(ClientAddress.FORWARDED_FOR, "198.51.100.4");
        assertEquals("ipv4:198.51.100.4", new ClientAddress(List.of("10.0.0.0/8")).key(r));
        assertEquals("ipv4:10.0.0.1", new ClientAddress(List.of()).key(r));
    }


    @Test
    void aMisconfiguredProxyListFailsLoudly()
    {
        assertThrows(IllegalArgumentException.class, () -> ClientAddress.block("nginx"));
        assertThrows(IllegalArgumentException.class, () -> ClientAddress.block("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> ClientAddress.block("10.0.0.0/x"));
        assertNull(ClientAddress.literal("localhost"), "a name is never looked up");
    }
}
