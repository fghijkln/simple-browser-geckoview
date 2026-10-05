package com.cue.simplebrowser;

import java.net.URI;
import java.util.Locale;

/** Security gates and bounded-data rules for optional web debugging features. */
final class WebDebugPolicy {
    static final String REMOTE_DEBUGGING_PREFERENCE = "developer_remote_debug_usb_v1";
    static final String IN_APP_CONSOLE_PREFERENCE = "developer_in_app_console_v1";
    static final String IN_APP_CONSOLE_PANEL_REQUEST_PREFERENCE = "developer_in_app_console_panel_request_v1";
    static final String IN_APP_CONSOLE_CLEANUP_FAILED_PREFERENCE = "developer_in_app_console_cleanup_failed";
    static final boolean REMOTE_DEBUGGING_DEFAULT = false;
    static final boolean IN_APP_CONSOLE_DEFAULT = false;
    static final String CONSOLE_EXTENSION_ID = "cue-web-console@cue.im";
    static final String CONSOLE_EXTENSION_URI = "resource://android/assets/web_console/";
    static final String CONSOLE_NATIVE_APP = "browser";
    static final int CONSOLE_RING_CAPACITY = 500;
    static final int CONSOLE_MAX_ARGUMENTS = 64;
    static final int CONSOLE_MAX_EVENTS_PER_SECOND = 60;

    private WebDebugPolicy() {}

    /** A missing preference is never treated as consent. */
    static boolean remoteDebuggingEnabled(boolean hasExplicitPreference, boolean storedValue) {
        return hasExplicitPreference && storedValue;
    }

    /** A missing preference is never treated as consent. */
    static boolean inAppConsoleEnabled(boolean hasExplicitPreference, boolean storedValue) {
        return hasExplicitPreference && storedValue;
    }

    /** Compare only an HTTP(S) origin; paths, queries, and fragments are never retained. */
    static boolean sameHttpOrigin(String senderUrl, String currentUrl) {
        try {
            URI sender = URI.create(senderUrl);
            URI current = URI.create(currentUrl);
            String senderScheme = normalizedScheme(sender);
            String currentScheme = normalizedScheme(current);
            if (senderScheme == null || !senderScheme.equals(currentScheme)) return false;
            if (sender.getRawUserInfo() != null || current.getRawUserInfo() != null) return false;
            String senderHost = sender.getHost();
            String currentHost = current.getHost();
            if (senderHost == null || currentHost == null
                    || !senderHost.equalsIgnoreCase(currentHost)) return false;
            return effectivePort(sender, senderScheme) == effectivePort(current, currentScheme);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String normalizedScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme) ? scheme : null;
    }

    private static int effectivePort(URI uri, String scheme) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equals(scheme) ? 443 : 80;
    }
}
