package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;

import java.net.URI;
import org.junit.Test;

public final class RemoteUrlPolicyTest {
    @Test public void httpsDropsQueryAndFragmentOnlyForDisplayPersistence() {
        URI u = RemoteUrlPolicy.parse("HTTPS://Host.Example:8443/ui/?launch=secret#auth", false);
        assertEquals("auth", u.getFragment());
        assertEquals("https://host.example:8443/ui/", RemoteUrlPolicy.safeBase(u));
        assertEquals("https://host.example:8443", RemoteUrlPolicy.displayOrigin(u));
    }

    @Test public void credentialsAreRejectedButFragmentsAreAccepted() {
        assertBad("https://user:pass@example.test/ui/?x=1", false);
        assertEquals("auth", RemoteUrlPolicy.parse("https://example.test/ui/#auth", false).getFragment());
    }

    @Test public void cleartextNeedsConfirmationAndPrivateAddress() {
        assertBad("http://192.168.1.5/ui/?token=x", false);
        assertEquals("http://192.168.1.5/ui/",
                RemoteUrlPolicy.safeBase(RemoteUrlPolicy.parse("http://192.168.1.5/ui/?token=x", true)));
        assertBad("http://8.8.8.8/ui/?token=x", true);
        assertTrue(RemoteUrlPolicy.isTrustedHttpHost("100.100.20.4"));
        assertTrue(RemoteUrlPolicy.isTrustedHttpHost("fd12::1"));
        assertTrue(RemoteUrlPolicy.isTrustedHttpHost("[fd12::1]"));
        assertTrue(RemoteUrlPolicy.isTrustedHttpHost("fe80::1"));
        assertTrue(RemoteUrlPolicy.isTrustedHttpHost("::1"));
        assertFalse(RemoteUrlPolicy.isTrustedHttpHost("::8.8.8.8"));
        assertFalse(RemoteUrlPolicy.isTrustedHttpHost("2001:db8::1"));
        assertTrue(RemoteUrlPolicy.isTrustedHttpHost("host.tailnet.ts.net"));
        assertFalse(RemoteUrlPolicy.isTrustedHttpHost("public.example"));
    }

    @Test public void ipv4IsStrictAndJvmIpv6AuthorityIsNotDoubleBracketed() {
        assertFalse(RemoteUrlPolicy.isTrustedHttpHost("192.168.001.5"));
        assertFalse(RemoteUrlPolicy.isTrustedHttpHost("+192.168.1.5"));
        URI u = RemoteUrlPolicy.parse("https://[fd12::1]:8443/ui/#auth", false);
        assertEquals("https://[fd12::1]:8443", RemoteUrlPolicy.displayOrigin(u));
        assertEquals("https://[fd12::1]:8443/ui/", RemoteUrlPolicy.safeBase(u));
    }

    @Test public void sameOriginIncludesSchemeAndEffectivePort() {
        URI a = URI.create("https://Example.test/start");
        assertTrue(RemoteUrlPolicy.sameOrigin(a, URI.create("https://example.test:443/other")));
        assertFalse(RemoteUrlPolicy.sameOrigin(a, URI.create("http://example.test/start")));
        assertFalse(RemoteUrlPolicy.sameOrigin(a, URI.create("https://example.test:8443/start")));
        assertFalse(RemoteUrlPolicy.allowedResource(a, URI.create("https://cdn.example/x"), false));
    }

    @Test public void resourcesMustRemainSameOriginAndHttpMustBeTrusted() {
        URI a = URI.create("http://192.168.1.5:8080/ui/");
        assertTrue(RemoteUrlPolicy.allowedResource(a, URI.create("http://192.168.1.5:8080/app.js"), true));
        assertFalse(RemoteUrlPolicy.allowedResource(a, URI.create("http://192.168.1.6:8080/app.js"), true));
        assertFalse(RemoteUrlPolicy.allowedResource(a, URI.create("https://192.168.1.5:8080/app.js"), true));
    }

    @Test public void fullHttpIpv6UrlsAreClassifiedWithoutLosingCredentials() {
        URI u = RemoteUrlPolicy.parse("http://[fd7a:115c:a1e0::123]:3080/?token=fixture#auth", true);
        assertEquals("http://[fd7a:115c:a1e0::123]:3080", RemoteUrlPolicy.displayOrigin(u));
        assertEquals("token=fixture", u.getRawQuery());
        assertEquals("auth", u.getFragment());
        assertBad("http://[2001:db8::1]:3080/", true);
        assertBad("http://[::ffff:8.8.8.8]/", true);
        assertBad("http://[::8.8.8.8]/", true);
        assertBad("http://[fd12::1]/", false);
    }

    @Test public void tailscaleAndPrivateIpv4BoundariesAreExact() {
        for (String host : new String[]{"10.0.0.1", "172.16.0.1", "172.31.255.254",
                "192.168.1.5", "100.64.0.1", "100.127.255.254"}) {
            assertNotNull(RemoteUrlPolicy.parse("http://" + host + ":3080/", true));
        }
        for (String host : new String[]{"172.15.255.255", "172.32.0.1", "100.63.255.255",
                "100.128.0.1", "192.168.001.5", "127.1", "2130706433", "0x7f000001"}) {
            assertBad("http://" + host + ":3080/", true);
        }
    }

    @Test public void invalidPortsAndNonWebSchemesAreRejected() {
        for (String value : new String[]{"https://host.test:0/", "https://host.test:65536/",
                "https://host.test:/", "https:///missing", "file:///tmp/secret",
                "javascript:alert(1)", "content://provider/document/x"}) assertBad(value, true);
        URI ftp = URI.create("ftp://192.168.1.5/");
        assertFalse(RemoteUrlPolicy.allowedResource(ftp, ftp, true));
        assertFalse(RemoteUrlPolicy.allowedResource(URI.create("https://host.test/"),
            URI.create("https://user@host.test/"), false));
    }

    private static void assertBad(String s, boolean http) {
        try { RemoteUrlPolicy.parse(s, http); fail("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
}
