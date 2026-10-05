package com.cue.simplebrowser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline source/policy checks only; does not create a Gecko runtime or contact a network. */
public final class WebRtcProtectionSmokeTest {
    private WebRtcProtectionSmokeTest() { }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "expected the project root");
        Path root = Path.of(args[0]);
        String adapter = read(root.resolve("app/src/main/java/com/cue/simplebrowser/GeckoViewBrowserAdapter.java"));
        String main = read(root.resolve("app/src/main/java/com/cue/simplebrowser/MainActivity.java"));
        String manifest = read(root.resolve("app/src/main/AndroidManifest.xml"));
        String gradle = read(root.resolve("app/build.gradle"));
        String strings = read(root.resolve("app/src/main/res/values/strings.xml"));

        check(WebRtcProtectionPolicy.enabledFromPreference(false, false),
                "missing user preference must default to protection enabled");
        check(!WebRtcProtectionPolicy.enabledFromPreference(true, false),
                "an explicit user opt-out must be retained");
        check(WebRtcProtectionPolicy.enabledFromPreference(true, true),
                "an explicit user opt-in must be retained");
        pass("default-on persisted WebRTC policy");

        require(adapter, "GeckoPreferenceController.setGeckoPref", "use the documented Gecko preference controller");
        require(adapter, "media.peerconnection.enabled", "set the Mozilla RTCPeerConnection gate");
        require(adapter, "!enabled", "protected mode must set the peer-connection preference false");
        require(adapter, "javascriptFallback", "expose and apply a fail-closed fallback if preference setting fails");
        require(adapter, "if (!webRtcPreferenceReady)", "gate URL loading until privacy settings resolve");
        require(adapter, "pendingUrl", "defer restored or requested pages until privacy policy resolves");
        pass("experimental native peer-connection preference and fail-closed navigation gate");

        require(adapter, "ContentBlocking.EtpLevel.STRICT", "enable native Strict ETP");
        require(adapter, "ContentBlocking.AntiTracking.STRICT", "enable native strict anti-tracking");
        require(adapter, "GeckoRuntimeSettings.HTTPS_ONLY", "enable GeckoView HTTPS-only");
        require(adapter, "GeckoRuntimeSettings.TRR_MODE_ONLY", "disable native Do53 fallback");
        require(adapter, "https://dns.quad9.net/dns-query", "use the selected Quad9 RFC8484 URI");
        require(adapter, "permissionCallback.reject()", "reject Android permission requests");
        require(adapter, "ContentPermission.VALUE_DENY", "deny Gecko content permissions");
        require(adapter, "mediaCallback.reject()", "reject all media requests");
        pass("strict ETP, HTTPS-only, Quad9 TRR-only, and unconditional permissions denial");

        check(manifest.contains("android:usesCleartextTraffic=\"false\""),
                "Android manifest must disallow cleartext traffic");
        check(!manifest.contains("android.permission.CAMERA") && !manifest.contains("android.permission.RECORD_AUDIO"),
                "manifest must not request camera or microphone access");
        check(gradle.contains("org.mozilla.geckoview:geckoview:157.0.20260924084938"),
                "build must be pinned to the audited GeckoView version");
        check(!gradle.toLowerCase().contains("cefrium") && !gradle.toLowerCase().contains("chromium"),
                "build must not declare a CEF/Chromium dependency");
        check(!main.contains("addJavascriptInterface") && !main.contains("android.webkit.WebView"),
                "application must not add WebView/JavaScript bridges");
        require(strings, "Experimental", "disclose experimental WebRTC API status to users");
        require(strings, "尚未在 Android 真机验证", "disclose device verification boundary");
        pass("no cleartext traffic, no camera/microphone grant, pinned engine and transparent limitations");
        System.out.println("PASS: source-only checks; no runtime, public detector, or external DNS query was used");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void require(String source, String needle, String message) {
        check(source.contains(needle), message);
    }

    private static void pass(String label) {
        System.out.println("PASS: " + label);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
