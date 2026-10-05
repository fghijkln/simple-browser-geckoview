package com.cue.simplebrowser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline checks for opt-in debug features, metadata-only capture, and unchanged Android privileges. */
public final class WebDebugPolicySmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("expected adapter, Android manifest, guard, extension manifest, main script, relay script, MainActivity");
        }
        check(!WebDebugPolicy.REMOTE_DEBUGGING_DEFAULT, "remote debugging must default off");
        check(!WebDebugPolicy.IN_APP_CONSOLE_DEFAULT, "in-app console authorization must default off");
        check(WebDebugPolicy.CONSOLE_RING_CAPACITY == 500, "metadata ring must remain capped at 500 events");
        check(WebDebugPolicy.CONSOLE_MAX_ARGUMENTS == 64, "argument count must be bounded");
        check(!WebDebugPolicy.remoteDebuggingEnabled(false, true), "missing remote preference is not consent");
        check(!WebDebugPolicy.inAppConsoleEnabled(false, true), "missing console preference is not consent");
        check(!WebDebugPolicy.remoteDebuggingEnabled(true, false), "explicit remote opt-out remains off");
        check(!WebDebugPolicy.inAppConsoleEnabled(true, false), "explicit console opt-out remains off");
        check(WebDebugPolicy.remoteDebuggingEnabled(true, true), "remote mode supports explicit opt-in");
        check(WebDebugPolicy.inAppConsoleEnabled(true, true), "console authorization can be explicitly opted in");

        String adapter = read(args[0]);
        String mainActivity = read(args[6]);
        check(adapter.contains(".remoteDebuggingEnabled(remoteDebuggingEnabled)"),
                "GeckoRuntime builder must use gated remote debugging");
        check(adapter.contains("debugPreferences.contains(WebDebugPolicy.REMOTE_DEBUGGING_PREFERENCE)"),
                "missing remote preference must not count as consent");
        check(adapter.contains(".consoleOutput(false)"), "consoleOutput must stay off to avoid logcat persistence");
        check(adapter.contains(".extensionsWebAPIEnabled(false)"), "Add-on Manager web API must stay off");
        check(adapter.contains("configureConsoleExtension(runtime, consolePanelRequested)"),
                "extension lifecycle must be controlled by one-shot explicit panel-open request, not stored consent");
        String installLifecycle = adapter.substring(adapter.indexOf("private static void configureConsoleExtension("),
                adapter.indexOf("private static WebExtension findConsoleExtension("));
        check(installLifecycle.indexOf("if (enabled)")
                        < installLifecycle.indexOf("ensureBuiltIn(WebDebugPolicy.CONSOLE_EXTENSION_URI"),
                "extension installation must be nested below panel-open request");
        check(mainActivity.contains("IN_APP_CONSOLE_PANEL_REQUEST_PREFERENCE")
                        && mainActivity.contains("pendingConsolePanelOpen")
                        && mainActivity.contains("requestConsolePanelOpen()"),
                "explicit Open-panel action must create the one-shot installation request");
        check(mainActivity.contains("shutdownForConsolePanelClose")
                        && mainActivity.contains("webConsoleBuffer.clear()"),
                "panel close must clear memory, close sessions, and uninstall extension");
        check(adapter.contains("controller.uninstall(stale)")
                        && adapter.contains("shutdownForConsolePanelClose")
                        && adapter.contains("setMessageDelegate(extension, null"),
                "stale and active extension copies must be removed with session delegates");
        check(adapter.contains("sender.isTopLevel()") && adapter.contains("sender.session == session")
                        && adapter.contains("WebDebugPolicy.sameHttpOrigin"),
                "native channel must validate extension sender, frame, session, and origin");
        check(adapter.contains("payload.opt(\"category\")")
                        && adapter.contains("payload.opt(\"level\")")
                        && adapter.contains("payload.opt(\"argumentCount\")")
                        && !adapter.contains("payload.optString(\"message\"")
                        && !adapter.contains("payload.opt(\"stack\")"),
                "native parser must read only fixed metadata and argument count");
        check(!adapter.contains("evaluateJS") && !adapter.contains("window.eval"),
                "do not use general-purpose JS injection");

        String extension = read(args[3]);
        check(extension.contains("\"world\": \"MAIN\"")
                        && extension.contains("\"world\": \"ISOLATED\""),
                "scripts must use explicit MAIN/ISOLATED worlds");
        check(extension.contains("\"all_frames\": false"), "only top-level frames may be instrumented");
        check(extension.contains("\"http://*/*\"") && extension.contains("\"https://*/*\""),
                "all HTTP(S) hosts must be explicitly represented for injection");
        for (String forbidden : Arrays.asList("\"cookies\"", "\"webRequest\"", "\"tabs\"")) {
            check(!extension.contains(forbidden), "unneeded WebExtension permission present: " + forbidden);
        }
        check(extension.contains("nativeMessagingFromContent") && extension.contains("geckoViewAddons"),
                "GeckoView native content-script messaging permissions must be explicit");

        String mainScript = read(args[4]);
        String relayScript = read(args[5]);
        check(mainScript.contains("addEventListener(\"error\"")
                        && mainScript.contains("addEventListener(\"unhandledrejection\"")
                        && mainScript.contains("removeEventListener(\"error\"")
                        && mainScript.contains("removeEventListener(\"unhandledrejection\"")
                        && mainScript.contains("[\"log\", \"warn\", \"error\"]")
                        && mainScript.contains("args.length"),
                "MAIN world observes only requested levels and emits argument counts");
        for (String forbidden : Arrays.asList("event.message", "event.reason", "event.error", ".stack",
                ".filename", "data.message", "message:", "url:", "timestamp:")) {
            check(!mainScript.contains(forbidden) && !relayScript.contains(forbidden),
                    "capture scripts must not inspect/emit page text or source metadata: " + forbidden);
        }
        check(relayScript.contains("connectNative(\"browser\")")
                        && relayScript.contains("captureActive")
                        && relayScript.contains("argumentCount")
                        && relayScript.contains("MAX_ARGUMENTS"),
                "native relay must be opt-in and forward bounded metadata only");

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
                        && guard.contains("APPROVED_DANGEROUS_PERMISSIONS"), "privilege guard allowlist missing");
        check(!guard.contains("android.permission.ACCESS_WIFI_STATE")
                        && !guard.contains("android.permission.CHANGE_WIFI_STATE"),
                "debugging must not add Wi-Fi permissions");
        System.out.println("Web debug policy smoke test: PASS (both features default off; explicit panel request gates install; metadata-only capture; Android privileges unchanged)");
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
