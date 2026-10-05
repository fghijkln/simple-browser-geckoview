package com.cue.simplebrowser;

/** Dependency-free checks for persisted GeckoView mobile/desktop request mode. */
public final class StoreAndSiteModeSmokeTest {
    private StoreAndSiteModeSmokeTest() { }

    public static void main(String[] args) {
        check(SiteMode.Mode.fromStoredValue(null) == SiteMode.Mode.PHONE,
                "missing saved mode should use GeckoView mobile mode");
        check(SiteMode.Mode.fromStoredValue("unknown") == SiteMode.Mode.PHONE,
                "unknown saved mode should fail back to mobile mode");
        SiteMode.Mode desktop = SiteMode.Mode.fromStoredValue(SiteMode.Mode.DESKTOP.storedValue());
        check(desktop == SiteMode.Mode.DESKTOP, "desktop mode should survive a preference round trip");
        check("gecko-desktop-mode".equals(desktop.userAgentOverride()),
                "desktop mode should select GeckoView's native desktop user-agent mode");
        check(SiteMode.Mode.PHONE.userAgentOverride().isEmpty(),
                "phone mode should select GeckoView's native mobile user-agent mode");
        check(desktop.toggled() == SiteMode.Mode.PHONE
                        && desktop.toggled().userAgentOverride().isEmpty(),
                "toggle back should restore GeckoView's mobile mode");
        System.out.println("PASS: persisted GeckoView mobile/desktop mode sentinel; no fabricated engine UA");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
