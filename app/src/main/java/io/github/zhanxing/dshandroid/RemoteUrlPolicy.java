package io.github.zhanxing.dshandroid;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** URL policy for the isolated remote WebView. It never interprets launch tokens. */
public final class RemoteUrlPolicy {
    private RemoteUrlPolicy() {}

    public static URI parse(String value, boolean allowHttp) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入主机打印的完整启动 URL");
        }
        final URI u;
        try {
            u = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 格式无效，请粘贴主机打印的完整地址");
        }
        String scheme = lower(u.getScheme());
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 HTTPS 或 HTTP 地址");
        }
        String host = canonicalHost(u.getHost());
        if (host.isEmpty() || u.getRawAuthority() == null || u.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("主机名或端口无效");
        }
        if (u.getRawUserInfo() != null) {
            throw new IllegalArgumentException("不要在 URL 中嵌入用户名和密码");
        }
        if (u.getPort() == 0 || u.getPort() > 65535 || u.getPort() < -1) {
            throw new IllegalArgumentException("端口必须为 1–65535");
        }
        // Fragments are intentionally accepted: older DSH clients may carry auth there.
        // safeBase/displayOrigin never retain or display them.
        if ("http".equals(scheme) && (!allowHttp || !isTrustedHttpHost(host))) {
            throw new IllegalArgumentException("HTTP 仅允许用户确认的私有 LAN/Tailscale 地址或明确本地域名");
        }
        return u;
    }

    /** A network address accepted for explicitly confirmed cleartext LAN/VPN use. */
    public static boolean isTrustedHttpHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = canonicalHost(host);
        if (h.indexOf(':') >= 0) return isTrustedIpv6(h);
        long v = ipv4(h);
        if (v >= 0) {
            long first = (v >>> 24) & 255;
            long second = (v >>> 16) & 255;
            return first == 10 || first == 127 || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127);
        }
        return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")
                || h.endsWith(".internal") || h.endsWith(".ts.net");
    }

    private static long ipv4(String h) {
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) return -1;
        long n = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') return -1;
            }
            if (part.length() > 1 && part.charAt(0) == '0') return -1;
            int x;
            try { x = Integer.parseInt(part); } catch (NumberFormatException e) { return -1; }
            if (x > 255) return -1;
            n = (n << 8) | x;
        }
        return n;
    }

    private static boolean isTrustedIpv6(String host) {
        String h = canonicalHost(host);
        if (h.indexOf('.') >= 0 || !h.matches("[0-9a-fA-F:]+")) return false;
        final InetAddress address;
        try {
            address = InetAddress.getByName(h);
        } catch (Exception e) {
            return false;
        }
        if (!(address instanceof Inet6Address)) return false;
        if (address.isLoopbackAddress()) return h.equalsIgnoreCase("::1");
        byte[] b = address.getAddress();
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return (first >= 0xfc && first <= 0xfd) || (first == 0xfe && (second & 0xc0) == 0x80);
    }

    public static boolean sameOrigin(URI a, URI b) {
        return a != null && b != null && !canonicalHost(a.getHost()).isEmpty()
                && !canonicalHost(b.getHost()).isEmpty() && a.getRawUserInfo() == null
                && b.getRawUserInfo() == null && lower(a.getScheme()).equals(lower(b.getScheme()))
                && canonicalHost(a.getHost()).equals(canonicalHost(b.getHost()))
                && effectivePort(a) == effectivePort(b);
    }

    public static boolean allowedResource(URI origin, URI candidate, boolean allowHttp) {
        if (!sameOrigin(origin, candidate)) return false;
        String scheme = lower(candidate.getScheme());
        return "https".equals(scheme) || ("http".equals(scheme)
                && allowHttp && isTrustedHttpHost(candidate.getHost()));
    }

    /** Removes query and fragment (which may contain the launch credential). */
    public static String safeBase(URI u) {
        String path = u.getRawPath();
        return lower(u.getScheme()) + "://" + authorityWithoutCredentials(u)
                + (path == null || path.isEmpty() ? "/" : path);
    }

    public static String displayOrigin(URI u) {
        return lower(u.getScheme()) + "://" + authorityWithoutCredentials(u);
    }

    private static String authorityWithoutCredentials(URI u) {
        String host = canonicalHost(u.getHost());
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        return host + (u.getPort() == -1 ? "" : ":" + u.getPort());
    }

    /** URI.getHost() can include brackets on Android/JVM; normalize both forms. */
    private static String canonicalHost(String h) {
        if (h == null) return "";
        String x = h.toLowerCase(Locale.ROOT);
        if (x.length() >= 2 && x.charAt(0) == '[' && x.charAt(x.length() - 1) == ']') {
            x = x.substring(1, x.length() - 1);
        }
        return x.endsWith(".") ? x.substring(0, x.length() - 1) : x;
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private static int effectivePort(URI u) {
        return u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
    }
}
