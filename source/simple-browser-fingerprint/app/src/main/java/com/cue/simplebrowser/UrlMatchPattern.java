package com.cue.simplebrowser;

import java.net.URI;
import java.util.Locale;

/** HTTPS-only host and path matcher; intentionally supports only the MVP subset. */
final class UrlMatchPattern {
    private final String source;
    private final String host;
    private final boolean anyHost;
    private final boolean subdomains;

    private UrlMatchPattern(String source, String host, boolean anyHost, boolean subdomains) {
        this.source = source;
        this.host = host;
        this.anyHost = anyHost;
        this.subdomains = subdomains;
    }

    static UrlMatchPattern parse(String value) {
        if (value == null || !value.startsWith("https://") || !value.endsWith("/*")) {
            throw new IllegalArgumentException("Only HTTPS host patterns ending in /* are supported: " + value);
        }
        String authority = value.substring("https://".length(), value.length() - 2);
        if (authority.isEmpty() || authority.indexOf('/') >= 0 || authority.indexOf('@') >= 0
                || authority.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Invalid HTTPS match-pattern host: " + value);
        }
        if ("*".equals(authority)) {
            return new UrlMatchPattern(value, "", true, false);
        }
        boolean wildcardSubdomains = authority.startsWith("*.");
        String rawHost = wildcardSubdomains ? authority.substring(2) : authority;
        if (rawHost.isEmpty() || rawHost.contains("*") || rawHost.contains("%")
                || rawHost.startsWith(".") || rawHost.endsWith(".")) {
            throw new IllegalArgumentException("Invalid HTTPS match-pattern host: " + value);
        }
        try {
            URI parsed = URI.create("https://" + rawHost + "/");
            if (!rawHost.equalsIgnoreCase(parsed.getHost()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid HTTPS match-pattern host: " + value);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid HTTPS match-pattern host: " + value, exception);
        }
        return new UrlMatchPattern(value, rawHost.toLowerCase(Locale.ROOT), false, wildcardSubdomains);
    }

    boolean matches(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                return false;
            }
            String candidate = uri.getHost().toLowerCase(Locale.ROOT);
            if (anyHost) {
                return true;
            }
            return host.equals(candidate) || (subdomains && candidate.endsWith("." + host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    String source() {
        return source;
    }
}
