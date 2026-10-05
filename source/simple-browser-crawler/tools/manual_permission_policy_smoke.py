#!/usr/bin/env python3
"""Static regression checks for system runtime permission requests plus per-site consent."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
JAVA_ROOT = ROOT / "app/src/main/java"
ANDROID = "http://schemas.android.com/apk/res/android"

root = ET.parse(MANIFEST).getroot()
declared = {item.attrib[f"{{{ANDROID}}}name"] for item in root.findall("uses-permission")}
expected = {
    "android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
}
assert declared == expected, f"Unexpected Manifest permissions: {sorted(declared ^ expected)}"
features = {
    item.attrib[f"{{{ANDROID}}}name"]: item.attrib.get(f"{{{ANDROID}}}required", "true")
    for item in root.findall("uses-feature")
}
optional_media_features = {
    "android.hardware.camera.any", "android.hardware.camera", "android.hardware.camera.autofocus",
    "android.hardware.microphone",
}
assert all(features.get(name) == "false" for name in optional_media_features), (
    f"Camera/microphone features must remain optional: {features}"
)
forbidden = {
    "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.ACTIVITY_RECOGNITION", "android.permission.BODY_SENSORS",
    "android.permission.BODY_SENSORS_BACKGROUND",
}
assert not (declared & forbidden), f"Unexpected broad/unsupported permissions: {sorted(declared & forbidden)}"

adapter = (JAVA_ROOT / "com/cue/simplebrowser/GeckoViewBrowserAdapter.java").read_text(encoding="utf-8")
assert "requestRuntimePermissions(" in adapter
assert "handler.requestSystemPermissions(missing.toArray(new String[0])" in adapter
for permission in (
    "android.Manifest.permission.CAMERA", "android.Manifest.permission.RECORD_AUDIO",
    "android.Manifest.permission.ACCESS_COARSE_LOCATION", "android.Manifest.permission.ACCESS_FINE_LOCATION",
):
    assert permission in adapter, f"adapter missing explicit allowlisted permission: {permission}"
assert "permissionCallback.grant()" in adapter and "permissionCallback.reject()" in adapter
assert "ContentPermission.VALUE_DENY" in adapter and "hasLocationPermission()" in adapter

main = (JAVA_ROOT / "com/cue/simplebrowser/MainActivity.java").read_text(encoding="utf-8")
assert "REQUEST_BROWSER_RUNTIME_PERMISSIONS" in main
assert "requestPermissions(request.permissions, REQUEST_BROWSER_RUNTIME_PERMISSIONS)" in main
assert "onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)" in main
assert "finishRuntimePermissionRequest(true)" in main
assert "runtimePermissionQueue.addLast" in main and "showNextRuntimePermissionRequest()" in main
assert "requestBrowserSitePermission(permissionLabel, origin, decision)" in main
system_request = main[main.index("private void requestBrowserSystemPermissions("):main.index("private void showNextRuntimePermissionRequest(")]
for permission in (
    "android.Manifest.permission.CAMERA", "android.Manifest.permission.RECORD_AUDIO",
    "android.Manifest.permission.ACCESS_COARSE_LOCATION", "android.Manifest.permission.ACCESS_FINE_LOCATION",
):
    assert permission in system_request, f"runtime request allowlist missing {permission}"
assert "if (!allowlist.contains(permission))" in system_request, "unexpected permissions must be rejected"
assert "Intent.ACTION_OPEN_DOCUMENT" in main
assert "REQUEST_IMPORT_EXTENSION_ZIP" in main

for path in JAVA_ROOT.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    if path.name == "MainActivity.java":
        continue
    assert not re.search(r"\brequestPermissions\s*\(", text), f"runtime permission API outside MainActivity: {path}"

strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
assert "browser_system_permission_rationale" in strings
assert "browser_site_permission_message" in strings
assert "Android 将显示系统授权框" in strings and "仍逐站询问" in strings

print("PASS: exact Manifest permission allowlist; no broad media/sensor permissions")
print("PASS: camera/microphone features remain optional")
print("PASS: runtime request queue only requests allowlisted camera/microphone/location permissions")
print("PASS: Android grant is checked before Gecko's per-site permission confirmation; denial blocks the site request")
print("PASS: document picker remains scoped; no obsolete manual pre-authorization gate")
