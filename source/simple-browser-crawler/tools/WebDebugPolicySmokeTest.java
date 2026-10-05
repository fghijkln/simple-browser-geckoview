package com.cue.simplebrowser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline checks for relaxed debug defaults with the requested data/privilege boundaries retained. */
public final class WebDebugPolicySmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("expected adapter, Android manifest, guard, extension manifest, main script, relay script, MainActivity");
        }
        check(WebDebugPolicy.REMOTE_DEBUGGING_DEFAULT, "remote debugging defaults enabled");
        check(WebDebugPolicy.IN_APP_CONSOLE_DEFAULT, "in-app Console defaults enabled");
        check(WebDebugPolicy.CONSOLE_RING_CAPACITY == 5_000, "default Console row capacity remains finite");
        check(Arrays.equals(WebDebugPolicy.consoleCapacityOptions(), new int[] {500, 1_000, 2_500, 5_000, 0}),
                "Console row count offers finite choices and explicit Unlimited");
        check(WebDebugPolicy.normalizeConsoleCapacity(2_500) == 2_500
                        && WebDebugPolicy.normalizeConsoleCapacity(4_000) == 5_000
                        && WebDebugPolicy.normalizeConsoleCapacity(0) == 0,
                "unsupported row choices normalize while zero remains Unlimited");
        check(Arrays.equals(WebDebugPolicy.consoleEntryLimitOptions(), new int[] {16_384, 65_536, 0})
                        && Arrays.equals(WebDebugPolicy.consoleRateLimitOptions(), new int[] {15, 60, 120, 0}),
                "entry size and event rate each provide explicit Unlimited choices");
        check(WebDebugPolicy.normalizeConsoleEntryLimit(0) == 0
                        && WebDebugPolicy.normalizeConsoleRateLimit(0) == 0
                        && WebDebugPolicy.consoleMaxArguments(0) == 0
                        && WebDebugPolicy.consoleMaxArgumentChars(0) == 0
                        && WebDebugPolicy.consoleMaxArguments(WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS) == 0
                        && WebDebugPolicy.consoleMaxArgumentChars(WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS)
                        == WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS,
                "argument count is not hidden behind a fixed cap; per-argument characters follow the chosen entry limit");
        check(WebDebugPolicy.remoteDebuggingEnabled(false, false), "missing remote preference uses relaxed default");
        check(WebDebugPolicy.inAppConsoleEnabled(false, false), "missing Console preference uses relaxed default");
        check(!WebDebugPolicy.remoteDebuggingEnabled(true, false), "explicit remote opt-out remains off");
        check(!WebDebugPolicy.inAppConsoleEnabled(true, false), "explicit Console opt-out remains off");
        check(WebDebugPolicy.remoteDebuggingEnabled(true, true), "remote mode supports explicit opt-in");
        check(WebDebugPolicy.inAppConsoleEnabled(true, true), "Console supports explicit opt-in");

        String adapter = read(args[0]);
        String mainActivity = read(args[6]);
        check(mainActivity.contains("showConsoleBufferLimitDialog")
                        && mainActivity.contains("CONSOLE_BUFFER_CAPACITY_PREFERENCE")
                        && mainActivity.contains("setSingleChoiceItems")
                        && mainActivity.contains("showConsoleEntryLimitDialog")
                        && mainActivity.contains("showConsoleRateLimitDialog")
                        && mainActivity.contains("showConsoleUnlimitedConfirmation")
                        && mainActivity.contains("webConsoleBuffer.capacity()"),
                "Settings exposes finite/unlimited row, entry, and rate policies with risk confirmation");
        check(adapter.contains(".remoteDebuggingEnabled(remoteDebuggingEnabled)"),
                "GeckoRuntime builder must use GeckoView's remote-debugging API");
        check(adapter.contains("REMOTE_DEBUGGING_DEFAULT") && mainActivity.contains("IN_APP_CONSOLE_DEFAULT"),
                "Runtime and MainActivity must consult their relaxed feature defaults");
        check(adapter.contains(".consoleOutput(false)"), "consoleOutput remains off to avoid logcat persistence");
        check(adapter.contains(".extensionsWebAPIEnabled(false)"), "Add-on Manager web API stays off");
        check(adapter.contains("configureConsoleExtension(runtime, consolePanelRequested)"),
                "extension install lifecycle remains gated by explicit panel-open request");
        String installLifecycle = adapter.substring(adapter.indexOf("private static void configureConsoleExtension("),
                adapter.indexOf("private static WebExtension findConsoleExtension("));
        check(installLifecycle.indexOf("if (enabled)")
                        < installLifecycle.indexOf("ensureBuiltIn(WebDebugPolicy.CONSOLE_EXTENSION_URI"),
                "extension installation remains nested below explicit panel-open request");
        check(mainActivity.contains("IN_APP_CONSOLE_PANEL_REQUEST_PREFERENCE")
                        && mainActivity.contains("pendingConsolePanelOpen")
                        && mainActivity.contains("requestConsolePanelOpen()"),
                "Open-panel action remains an explicit installation request");
        check(mainActivity.contains("shutdownForConsolePanelClose")
                        && mainActivity.contains("webConsoleBuffer.clear()"),
                "panel close clears memory, closes sessions, and uninstalls extension");
        check(adapter.contains("controller.uninstall(stale)")
                        && adapter.contains("shutdownForConsolePanelClose")
                        && adapter.contains("setMessageDelegate(extension, null"),
                "stale and active extension copies are removed with message delegates");
        check(adapter.contains("sender.isTopLevel()") && adapter.contains("sender.session == session")
                        && adapter.contains("WebDebugPolicy.sameHttpOrigin"),
                "native channel validates extension sender, top-level frame, active session, and origin");
        check(adapter.contains("payload.opt(\"category\")")
                        && adapter.contains("payload.opt(\"level\")")
                        && adapter.contains("payload.opt(\"argumentCount\")")
                        && adapter.contains("payload.opt(\"content\")")
                        && adapter.contains("currentConsoleEntryLimit()")
                        && adapter.contains("state.put(\"maxEntryChars\""),
                "native parser and extension use the selected raw-text limits");
        check(!adapter.contains("evaluateJS") && !adapter.contains("window.eval")
                        && !mainActivity.contains("addJavascriptInterface"),
                "no generic JS bridge or native evaluation is added");

        String extension = read(args[3]);
        check(extension.contains("\"world\": \"MAIN\"")
                        && extension.contains("\"world\": \"ISOLATED\""),
                "scripts use explicit MAIN/ISOLATED worlds");
        check(extension.contains("\"all_frames\": false"), "only top-level frames may be instrumented");
        check(extension.contains("\"http://*/*\"") && extension.contains("\"https://*/*\""),
                "all HTTP(S) hosts remain explicit and install prompt is retained");
        for (String forbidden : Arrays.asList("\"cookies\"", "\"webRequest\"", "\"tabs\"")) {
            check(!extension.contains(forbidden), "unneeded WebExtension permission present: " + forbidden);
        }
        check(extension.contains("nativeMessagingFromContent") && extension.contains("geckoViewAddons"),
                "GeckoView native content-script messaging permissions are explicit");

        String mainScript = read(args[4]);
        String relayScript = read(args[5]);
        check(mainScript.contains("addEventListener(\"error\"")
                        && mainScript.contains("addEventListener(\"unhandledrejection\"")
                        && mainScript.contains("removeEventListener(\"error\"")
                        && mainScript.contains("removeEventListener(\"unhandledrejection\"")
                        && mainScript.contains("[\"log\", \"warn\", \"error\"]")
                        && mainScript.contains("formatArguments") && mainScript.contains("value.stack"),
                "capture includes console arguments and explicit Error text/stack only while active");
        for (String forbidden : Arrays.asList("document.querySelector", "document.forms", "document.cookie",
                "requestBody", "responseBody", "XMLHttpRequest", "fetch(")) {
            check(!mainScript.contains(forbidden) && !relayScript.contains(forbidden),
                    "capture scripts must not read DOM/form/cookie/network payload: " + forbidden);
        }
        check(!mainScript.contains("timestamp:") && !relayScript.contains("timestamp:")
                        && !mainScript.contains("url:") && !relayScript.contains("url:"),
                "capture records contain no app-generated wall time or page URL field");
        check(relayScript.contains("connectNative(\"browser\")") && relayScript.contains("captureActive")
                        && relayScript.contains("maxEntryChars > 0 && data.content.length > maxEntryChars")
                        && mainScript.contains("maxEntryChars === 0"),
                "native relay remains opt-in and validates finite caps while passing the explicit Unlimited mode");

        String manifest = read(args[1]);
        Set<String> permissions = matches(manifest, "<uses-permission\\s+android:name=\"([^\"]+)\"");
        Set<String> expectedPermissions = new HashSet<>(Arrays.asList(
                "android.permission.INTERNET", "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"));
        check(permissions.equals(expectedPermissions), "Android manifest permissions changed: " + permissions);
        check(!manifest.contains("android:debuggable=\"true\""), "release manifest must not enable debuggable");
        Set<String> declaredComponents = componentNames(manifest);
        check(declaredComponents.equals(new HashSet<>(Arrays.asList(
                ".MainActivity", ".ProfileRestartActivity", ".DnsVpnService"))),
                "Android component allowlist changed: " + declaredComponents);

        String guard = read(args[2]);
        check(guard.contains("EXPECTED_SYSTEM_PERMISSIONS")
                        && guard.contains("APPROVED_DANGEROUS_PERMISSIONS"), "privilege guard allowlist remains present");
        check(!guard.contains("android.permission.ACCESS_WIFI_STATE")
                        && !guard.contains("android.permission.CHANGE_WIFI_STATE"),
                "debugging must not add Wi-Fi permissions");
        System.out.println("Web debug policy smoke test: PASS (default-enabled Gecko API, explicit install prompt, top-frame/raw text only, finite and Unlimited row/entry/rate controls, unchanged Android privileges)");
    }

    private static Set<String> componentNames(String xml) {
        Set<String> values = new HashSet<>();
        Matcher matcher = Pattern.compile("<(?:activity|service|receiver|provider)\\s+android:name=\"([^\"]+)\"").matcher(xml);
        while (matcher.find()) values.add(matcher.group(1));
        return values;
    }

    private static Set<String> matches(String text, String regex) {
        Set<String> values = new HashSet<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) values.add(matcher.group(1));
        return values;
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
