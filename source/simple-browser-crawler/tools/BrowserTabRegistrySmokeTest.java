package com.cue.simplebrowser;

/** Focused dependency-free regression checks for real tab state and lifecycle selection. */
public final class BrowserTabRegistrySmokeTest {
    private BrowserTabRegistrySmokeTest() { }

    public static void main(String[] args) {
        BrowserTabRegistry registry = new BrowserTabRegistry();
        BrowserTabRegistry.Tab first = registry.create("first");
        first.url = "https://one.example/";
        first.lastRequestedUrl = first.url;
        first.title = "One";
        first.canGoBack = true;
        first.progress = 73;

        BrowserTabRegistry.Tab second = registry.create("second");
        second.url = "https://two.example/path";
        second.lastRequestedUrl = second.url;
        second.title = "Two";
        second.failed = true;
        second.errorMessage = "network error";

        check(registry.size() == 2, "new tabs must create separate entries");
        check(registry.selected() == second, "new tab should become active");
        check(registry.find("first").url.equals("https://one.example/"),
                "a second tab must not overwrite the first tab's URL");
        check(registry.find("first").canGoBack && registry.find("first").progress == 73,
                "back and progress state should remain attached to the first tab");
        check(registry.find("second").failed && registry.find("second").errorMessage.equals("network error"),
                "load failure state should remain attached to the second tab");

        check(registry.select("first"), "existing tab should be selectable");
        check(registry.selected() == first, "selection should switch to the requested tab");
        check(registry.close("first"), "existing tab should close");
        check(registry.size() == 1 && registry.selected() == second,
                "closing the selected tab should select its right neighbor");
        check(!registry.close("first"), "closing an unknown tab should be harmless");
        check(registry.close("second") && registry.size() == 0 && registry.selected() == null,
                "closing the final tab should leave no stale selection for the UI to replace");
        BrowserTabRegistry.Tab replacement = registry.create("replacement");
        check(registry.selected() == replacement && registry.size() == 1,
                "a replacement tab can be created after the final close");
        try {
            registry.create("replacement");
            throw new AssertionError("duplicate IDs must be rejected");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: separate tab URL/title/loading state, switching, close selection, final-close replacement, and duplicate-ID rejection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
