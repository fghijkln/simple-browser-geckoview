package com.cue.simplebrowser;

/** Persistent default-on request for GeckoView's Experimental peer-connection preference. */
final class WebRtcProtectionPolicy {
    static final String PREFERENCE_KEY = "privacy.webrtc.protection.enabled";
    static final boolean DEFAULT_ENABLED = true;

    private WebRtcProtectionPolicy() { }

    static boolean enabledFromPreference(boolean hasStoredValue, boolean storedValue) {
        return hasStoredValue ? storedValue : DEFAULT_ENABLED;
    }
}
