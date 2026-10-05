package com.cue.simplebrowser;

/** Persistent GeckoView mobile/desktop user-agent mode. */
final class SiteMode {
    static final String PREFERENCE_KEY = "website_site_mode";
    static final String STORED_PHONE = "phone";
    static final String STORED_DESKTOP = "desktop";

    private static final String DESKTOP_MODE_SENTINEL = "gecko-desktop-mode";

    enum Mode {
        PHONE(STORED_PHONE),
        DESKTOP(STORED_DESKTOP);

        private final String storedValue;

        Mode(String storedValue) { this.storedValue = storedValue; }

        String storedValue() { return storedValue; }

        Mode toggled() { return this == PHONE ? DESKTOP : PHONE; }

        /** Returns a mode sentinel; GeckoView supplies its own engine-specific user agent. */
        String userAgentOverride() {
            return this == DESKTOP ? DESKTOP_MODE_SENTINEL : "";
        }

        static Mode fromStoredValue(String value) {
            return STORED_DESKTOP.equals(value) ? DESKTOP : PHONE;
        }
    }

    private SiteMode() { }
}
