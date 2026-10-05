#!/usr/bin/env python3
"""Static regression checks for the app-local privilege guard and GeckoView isolation policy."""
from pathlib import Path
import os
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID}}}"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
JAVA = ROOT / "app/src/main/java/com/cue/simplebrowser"

manifest = ET.parse(MANIFEST).getroot()
source_permissions = {p.get(A + "name") for p in manifest.findall("uses-permission")}
expected_source_permissions = {
    "android.permission.INTERNET",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
}
assert source_permissions == expected_source_permissions, (
    f"unexpected source permission set: {sorted(source_permissions ^ expected_source_permissions)}"
)
app = manifest.find("application")
assert app is not None
assert app.get(A + "name") == ".PrivilegeGuardApplication", "Application guard is not installed"

activities = {x.get(A + "name"): x for x in app.findall("activity")}
services = {x.get(A + "name"): x for x in app.findall("service")}
assert set(activities) == {".MainActivity", ".ProfileRestartActivity"}
assert activities[".MainActivity"].get(A + "exported") == "true"
assert activities[".ProfileRestartActivity"].get(A + "exported") == "false"
assert activities[".ProfileRestartActivity"].get(A + "process") == ":profile_restart"
assert set(services) == {".DnsVpnService"}
assert services[".DnsVpnService"].get(A + "permission") == "android.permission.BIND_VPN_SERVICE"
assert services[".DnsVpnService"].get(A + "exported") == "true"
assert services[".DnsVpnService"].get(A + "foregroundServiceType") == "systemExempted"

forbidden_permissions = {
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.ACCESS_NOTIFICATION_POLICY",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_DEVICE_ADMIN",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.BODY_SENSORS",
    "android.permission.BODY_SENSORS_BACKGROUND",
}
assert not (source_permissions & forbidden_permissions), (
    f"forbidden source permissions: {sorted(source_permissions & forbidden_permissions)}"
)

application = (JAVA / "PrivilegeGuardApplication.java").read_text(encoding="utf-8")
guard = (JAVA / "BrowserPrivilegeGuard.java").read_text(encoding="utf-8")
adapter = (JAVA / "GeckoViewBrowserAdapter.java").read_text(encoding="utf-8")
vpn = (JAVA / "DnsVpnService.java").read_text(encoding="utf-8")
assert "BrowserPrivilegeGuard.verifyOrThrow(this)" in application
assert "onActivityPreResumed" in application
for token in (
    "Process.myUid()", "Process.isIsolated()", "Application.getProcessName()",
    "/proc/self/status", "CapEff", "CapPrm", "CapInh", "CapAmb",
    "PROTECTION_DANGEROUS", "checkSelfPermission", "DevicePolicyManager",
    "isDeviceOwnerApp", "isProfileOwnerApp", "isAdminActive",
    "AccessibilityService.SERVICE_INTERFACE", "ENABLED_ACCESSIBILITY_SERVICES",
    "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "MANAGE_EXTERNAL_STORAGE",
    "REQUEST_INSTALL_PACKAGES", "PACKAGE_USAGE_STATS", "permissionToOp", "checkOpNoThrow",
    "canDrawOverlays", "canWrite", "isExternalStorageManager",
    "canRequestPackageInstalls", "APPROVED_DANGEROUS_PERMISSIONS",
):
    assert token in guard, f"guard missing expected check: {token}"
assert ".fissionEnabled(true)" in adapter
assert ".isolatedProcessEnabled(true)" in adapter
assert "setWebContentIsolationStrategy(" in adapter
assert "GeckoRuntimeSettings.STRATEGY_ISOLATE_EVERYTHING" in adapter
assert "BrowserPrivilegeGuard.verifyOrThrow(this)" in vpn

# If Gradle has already produced the merged manifest, verify the dependency-merged allowlist too.
merged_arg = os.environ.get("MERGED_MANIFEST")
if len(sys.argv) > 1:
    merged_arg = sys.argv[1]
if merged_arg:
    merged_path = Path(merged_arg)
else:
    merged_path = ROOT.parent / f"{ROOT.name}-build/gradle/app/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
if merged_path.exists():
    merged = ET.parse(merged_path).getroot()
    merged_permissions = {p.get(A + "name") for p in merged.findall("uses-permission")}
    expected_merged_permissions = expected_source_permissions | {
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "com.cue.simplebrowser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }
    assert merged_permissions == expected_merged_permissions, (
        f"unexpected merged permission set: {sorted(merged_permissions ^ expected_merged_permissions)}"
    )
    dynamic_permission = next(
        p for p in merged.findall("permission")
        if p.get(A + "name") == "com.cue.simplebrowser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    )
    assert dynamic_permission.get(A + "protectionLevel") == "signature"
    merged_app = merged.find("application")
    assert merged_app is not None
    merged_services = merged_app.findall("service")
    assert len(merged_services) == 90, f"unexpected merged service count: {len(merged_services)}"
    assert all(
        s.get(A + "exported") == "false"
        for s in merged_services
        if s.get(A + "name") != "com.cue.simplebrowser.DnsVpnService"
    ), "a non-VPN merged service is unexpectedly exported"
    vpn_service = next(
        s for s in merged_services
        if s.get(A + "name") == "com.cue.simplebrowser.DnsVpnService"
    )
    assert vpn_service.get(A + "exported") == "true"
    assert vpn_service.get(A + "permission") == "android.permission.BIND_VPN_SERVICE"
    assert not any(
        s.get(A + "permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
        for s in merged_services
    ), "merged manifest unexpectedly declares an AccessibilityService"
    assert not any(
        s.get(A + "permission") == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        for s in merged_services
    ), "merged manifest unexpectedly declares a NotificationListenerService"
    assert sum(s.get(A + "isolatedProcess") == "true" for s in merged_services) == 41
    merged_activities = {a.get(A + "name"): a for a in merged_app.findall("activity")}
    assert set(merged_activities) == {
        "com.cue.simplebrowser.MainActivity",
        "com.cue.simplebrowser.ProfileRestartActivity",
        "com.google.android.gms.common.api.GoogleApiActivity",
    }
    assert merged_activities["com.cue.simplebrowser.MainActivity"].get(A + "exported") == "true"
    assert merged_activities["com.cue.simplebrowser.ProfileRestartActivity"].get(A + "exported") == "false"
    assert merged_activities["com.google.android.gms.common.api.GoogleApiActivity"].get(A + "exported") == "false"
    merged_receivers = merged_app.findall("receiver")
    assert len(merged_receivers) == 1
    assert merged_receivers[0].get(A + "name") == "androidx.profileinstaller.ProfileInstallReceiver"
    assert merged_receivers[0].get(A + "exported") == "true"
    assert merged_receivers[0].get(A + "permission") == "android.permission.DUMP"
    providers = {p.get(A + "name"): p for p in merged_app.findall("provider")}
    assert set(providers) == {
        "org.mozilla.gecko.GeckoClipboardContentProvider",
        "androidx.startup.InitializationProvider",
    }
    clipboard_provider = providers["org.mozilla.gecko.GeckoClipboardContentProvider"]
    assert clipboard_provider.get(A + "exported") == "true"
    assert clipboard_provider.get(A + "grantUriPermissions") == "true"
    assert providers["androidx.startup.InitializationProvider"].get(A + "exported") == "false"
    print(f"PASS: dependency-merged manifest allowlist ({merged_path})")
else:
    print("NOTE: merged manifest not found; source manifest and Java policy checks still ran")

print("PASS: source permission/component allowlist; approved manual media/location permissions retained")
print("PASS: fail-closed app startup/resume and DNS VPN service guards")
print("PASS: own UID/CapEff, device policy, accessibility, notification-listener and special-access checks")
print("PASS: GeckoView Fission and supported isolated content-process settings are explicit")
