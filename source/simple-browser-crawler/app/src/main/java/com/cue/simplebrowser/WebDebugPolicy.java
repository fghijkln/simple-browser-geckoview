package com.cue.simplebrowser;

import java.net.URI;
import java.util.Locale;

/** Security gates and bounded-data rules for optional web debugging features. */
final class WebDebugPolicy {
    static final String REMOTE_DEBUGGING_PREFERENCE = "developer_remote_debug_usb_v1";
    static final String IN_APP_CONSOLE_PREFERENCE = "developer_in_app_console_v1";
    static final String IN_APP_CONSOLE_PANEL_REQUEST_PREFERENCE = "developer_in_app_console_panel_request_v1";
    static final String IN_APP_CONSOLE_CLEANUP_FAILED_PREFERENCE = "developer_in_app_console_cleanup_failed";
    static final String CONSOLE_BUFFER_CAPACITY_PREFERENCE = "developer_console_buffer_capacity_v1";
    static final String CONSOLE_ENTRY_LIMIT_PREFERENCE = "developer_console_entry_limit_v2";
    static final String CONSOLE_RATE_LIMIT_PREFERENCE = "developer_console_rate_limit_v2";
    static final boolean REMOTE_DEBUGGING_DEFAULT = true;
    static final boolean IN_APP_CONSOLE_DEFAULT = true;
    static final String CONSOLE_EXTENSION_ID = "cue-web-console@cue.im";
    static final String CONSOLE_EXTENSION_URI = "resource://android/assets/web_console/";
    static final String CONSOLE_NATIVE_APP = "browser";
    static final int CONSOLE_RING_CAPACITY = 5000;
    static final int CONSOLE_MAX_ARGUMENTS = 0;
    static final int CONSOLE_MAX_ENTRY_CHARS = 16_384;
    static final int CONSOLE_MAX_ARGUMENT_CHARS = CONSOLE_MAX_ENTRY_CHARS;
    static final int CONSOLE_MAX_EVENTS_PER_SECOND = 60;
    private static final int[] CONSOLE_CAPACITY_OPTIONS = {500, 1_000, 2_500, 5_000, 0};
    private static final int[] CONSOLE_ENTRY_LIMIT_OPTIONS = {16_384, 65_536, 0};
    private static final int[] CONSOLE_RATE_LIMIT_OPTIONS = {15, 60, 120, 0};

    private WebDebugPolicy() {}

    static int[] consoleCapacityOptions() {
        return CONSOLE_CAPACITY_OPTIONS.clone();
    }

    static int[] consoleEntryLimitOptions() { return CONSOLE_ENTRY_LIMIT_OPTIONS.clone(); }

    static int[] consoleRateLimitOptions() { return CONSOLE_RATE_LIMIT_OPTIONS.clone(); }

    static int normalizeConsoleCapacity(int requested) {
        for (int option : CONSOLE_CAPACITY_OPTIONS) {
            if (requested == option) return option;
        }
        return CONSOLE_RING_CAPACITY;
    }

    static int normalizeConsoleEntryLimit(int requested) {
        for (int option : CONSOLE_ENTRY_LIMIT_OPTIONS) if (requested == option) return option;
        return CONSOLE_MAX_ENTRY_CHARS;
    }

    static int normalizeConsoleRateLimit(int requested) {
        for (int option : CONSOLE_RATE_LIMIT_OPTIONS) if (requested == option) return option;
        return CONSOLE_MAX_EVENTS_PER_SECOND;
    }

    static int consoleMaxArguments(int entryLimit) {
        return 0;
    }

    static int consoleMaxArgumentChars(int entryLimit) {
        return entryLimit;
    }

    static int consoleCapacityOptionIndex(int requested) {
        int normalized = normalizeConsoleCapacity(requested);
        for (int index = 0; index < CONSOLE_CAPACITY_OPTIONS.length; index++) {
            if (CONSOLE_CAPACITY_OPTIONS[index] == normalized) return index;
        }
        return CONSOLE_CAPACITY_OPTIONS.length - 1;
    }

    static int consoleEntryLimitOptionIndex(int requested) {
        int normalized = normalizeConsoleEntryLimit(requested);
        for (int index = 0; index < CONSOLE_ENTRY_LIMIT_OPTIONS.length; index++) {
            if (CONSOLE_ENTRY_LIMIT_OPTIONS[index] == normalized) return index;
        }
        return CONSOLE_ENTRY_LIMIT_OPTIONS.length - 1;
    }

    static int consoleRateLimitOptionIndex(int requested) {
        int normalized = normalizeConsoleRateLimit(requested);
        for (int index = 0; index < CONSOLE_RATE_LIMIT_OPTIONS.length; index++) {
            if (CONSOLE_RATE_LIMIT_OPTIONS[index] == normalized) return index;
        }
        return CONSOLE_RATE_LIMIT_OPTIONS.length - 1;
    }

    /** New/legacy profiles use the relaxed default unless the user has explicitly disabled it. */
    static boolean remoteDebuggingEnabled(boolean hasExplicitPreference, boolean storedValue) {
        return hasExplicitPreference ? storedValue : REMOTE_DEBUGGING_DEFAULT;
    }

    /** New/legacy profiles use the relaxed default unless the user has explicitly disabled it. */
    static boolean inAppConsoleEnabled(boolean hasExplicitPreference, boolean storedValue) {
        return hasExplicitPreference ? storedValue : IN_APP_CONSOLE_DEFAULT;
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
