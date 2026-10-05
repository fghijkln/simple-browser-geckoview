#!/usr/bin/env python3
"""Static regression checks for the manual Android permission policy."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
JAVA_ROOT = ROOT / "app/src/main/java"
ANDROID = "http://schemas.android.com/apk/res/android"

root = ET.parse(MANIFEST).getroot()
declared = {
    item.attrib[f"{{{ANDROID}}}name"]
    for item in root.findall("uses-permission")
}
expected = {
    "android.permission.INTERNET",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
}
assert declared == expected, f"Unexpected Manifest permissions: {sorted(declared ^ expected)}"
features = {
    item.attrib[f"{{{ANDROID}}}name"]: item.attrib.get(f"{{{ANDROID}}}required", "true")
    for item in root.findall("uses-feature")
}
optional_media_features = {
    "android.hardware.camera.any",
    "android.hardware.camera",
    "android.hardware.camera.autofocus",
    "android.hardware.microphone",
}
assert all(features.get(name) == "false" for name in optional_media_features), (
    f"Camera/microphone features must remain optional: {features}"
)

forbidden = {
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.BODY_SENSORS",
    "android.permission.BODY_SENSORS_BACKGROUND",
}
assert not (declared & forbidden), f"Unexpected broad/unsupported permissions: {sorted(declared & forbidden)}"

request_api = re.compile(
    r"(?:\bActivityCompat\s*\.\s*requestPermissions\s*\(|"
    r"\b(?:[A-Za-z_$][\w$]*\s*\.\s*)?requestPermissions\s*\(|"
    r"\bActivityResultContracts\s*\.\s*RequestPermission\b|"
    r"\brequestPermission\s*\()"
)
for path in JAVA_ROOT.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    assert not request_api.search(text), f"Runtime permission request API found in {path}"

adapter = (JAVA_ROOT / "com/cue/simplebrowser/GeckoViewBrowserAdapter.java").read_text(encoding="utf-8")
assert "missingAndroidPermissionLabel(permissions)" in adapter
assert "permissionCallback.reject()" in adapter
assert "permissionCallback.grant()" in adapter
assert "ContentPermission.VALUE_DENY" in adapter
assert "mediaCallback.reject()" in adapter
assert "hasLocationPermission()" in adapter

main = (JAVA_ROOT / "com/cue/simplebrowser/MainActivity.java").read_text(encoding="utf-8")
assert "Intent.ACTION_OPEN_DOCUMENT" in main
assert "REQUEST_SELECT_WALLPAPER" not in main
assert "WallpaperPickerFlow" not in main
assert "REQUEST_IMPORT_EXTENSION_ZIP" in main

print("PASS: exact optional permission allowlist; no broad media/sensor permissions")
print("PASS: camera and microphone hardware features are explicitly optional")
print("PASS: no Android dangerous-permission request API in application Java source")
print("PASS: GeckoView requests are checked; missing/unsupported cases can be denied")
print("PASS: generic file paths retain the system document picker; no wallpaper picker path remains")
