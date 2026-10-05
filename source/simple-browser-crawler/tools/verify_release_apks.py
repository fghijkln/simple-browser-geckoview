#!/usr/bin/env python3
"""Verify release APK metadata, signatures, alignment, native payload and hashes."""
import hashlib
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

APK_DIR = Path(sys.argv[1] if len(sys.argv) > 1 else "build/outputs/apk/release").resolve()
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "/home/ubuntu/.local/share/android-sdk")))
BUILD_TOOLS = Path(os.environ.get("ANDROID_BUILD_TOOLS", SDK / "build-tools/37.0.0"))
AAPT = BUILD_TOOLS / "aapt"
APKSIGNER = BUILD_TOOLS / "apksigner"
ZIPALIGN = BUILD_TOOLS / "zipalign"
EXPECTED_ABIS = {"arm64-v8a", "x86_64"}


def run(args):
    return subprocess.run([str(a) for a in args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def scan_zip(apk, expected_abi):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        bad_names = [name for name in names if re.search(r"(^|/)(libcef\.so|libchromium[^/]*\.so|libchrome\.so)$|(^|/)org/(chromium|cef)/", name, re.I)]
        assert not bad_names, f"Chromium/CEF-like entry names: {bad_names}"
        native = sorted(name for name in names if name.startswith("lib/") and name.endswith(".so"))
        abis = {name.split("/", 2)[1] for name in native if name.count("/") >= 2}
        assert abis == {expected_abi}, f"expected only {expected_abi}, found {sorted(abis)}"
        assert any(name.endswith("/libxul.so") for name in native), "Mozilla Gecko libxul.so missing"
        assert any(name.endswith("/libmozglue.so") for name in native), "Mozilla Gecko libmozglue.so missing"
        assert not any(re.search(r"cef|chromium|chrome", name.rsplit("/", 1)[-1], re.I) for name in native), "Chromium/CEF native library detected"
        dex_names = sorted(name for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name))
        forbidden = (b"org/chromium/", b"org/cef/", b"libcef.so", b"libchromium.so", b"libchrome.so")
        dex_hits = []
        for dex_name in dex_names:
            data = archive.read(dex_name)
            for token in forbidden:
                if token.lower() in data.lower():
                    dex_hits.append((dex_name, token.decode("ascii")))
        assert not dex_hits, f"Chromium/CEF descriptors or library markers in DEX: {dex_hits}"
        native_hits = []
        native_tokens = (b"libcef.so", b"org/chromium/", b"org/cef/")
        for name in native:
            rolling = b""
            with archive.open(name) as stream:
                while True:
                    block = stream.read(1024 * 1024)
                    if not block:
                        break
                    sample = rolling + block
                    for token in native_tokens:
                        if token.lower() in sample.lower():
                            native_hits.append((name, token.decode("ascii")))
                    rolling = sample[-64:]
        assert not native_hits, f"Chromium/CEF markers in native ELF payload: {native_hits}"
        return native, dex_names


def main():
    assert APK_DIR.is_dir(), f"APK directory missing: {APK_DIR}"
    apks = sorted(APK_DIR.glob("app-*-release.apk"))
    assert len(apks) == 2, f"expected exactly two ABI APKs, found {len(apks)}"
    found_abis = set()
    lines = [f"APK directory: {APK_DIR}", ""]
    for apk in apks:
        expected_abi = "arm64-v8a" if "arm64-v8a" in apk.name else "x86_64" if "x86_64" in apk.name else None
        assert expected_abi in EXPECTED_ABIS, f"Unexpected APK name: {apk.name}"
        assert expected_abi not in found_abis, f"Duplicate ABI output: {expected_abi}"
        found_abis.add(expected_abi)
        badging = run([AAPT, "dump", "badging", apk])
        package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
        assert package, "aapt did not report package/version"
        assert package.groups() == ("com.cue.simplebrowser", "18", "2.8.1"), f"Unexpected app metadata: {package.groups()}"
        abi_badging = re.search(r"^native-code: (.*)$", badging, re.M)
        assert abi_badging and expected_abi in abi_badging.group(1), f"aapt ABI mismatch for {apk.name}"
        align = run([ZIPALIGN, "-c", "-p", "4", apk])
        page_align = run([ZIPALIGN, "-c", "-P", "16", "4", apk])
        signature = run([APKSIGNER, "verify", "--verbose", "--print-certs", apk])
        assert "Verifies" in signature, f"APK signature verification failed: {apk.name}"
        assert "Android Debug" in signature or "CN=Android Debug" in signature, "Expected an Android debug certificate"
        assert "Verified using v3 scheme (APK Signature Scheme v3): true" in signature, "APK v3 signature is missing"
        certificate = re.search(r"certificate SHA-256 digest:\s*([0-9a-f:]+)", signature, re.I)
        assert certificate, "APK signer certificate SHA-256 was not reported"
        certificate_sha256 = certificate.group(1).replace(":", "").lower()
        assert certificate_sha256 == "e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd", \
            f"APK signer does not match the retained v0.4 debug certificate: {certificate_sha256}"
        zip_test = run(["unzip", "-t", apk])
        assert "No errors detected" in zip_test, f"ZIP integrity test failed: {apk.name}"
        native, dex = scan_zip(apk, expected_abi)
        digest = sha256(apk)
        lines.extend([
            f"APK: {apk}",
            f"Size bytes: {apk.stat().st_size}",
            f"SHA-256: {digest}",
            f"Package/version: {package.group(1)} {package.group(3)} (versionCode {package.group(2)})",
            f"ABI: {expected_abi}",
            f"Signing certificate SHA-256: {certificate_sha256} (v0.4-compatible Android Debug key)",
            "Signature schemes: " + "; ".join(line.strip() for line in signature.splitlines() if "Verified using" in line),
            "ZIP alignment: 4-byte native alignment PASS; 16-KB page alignment PASS",
            f"Mozilla Gecko native libraries ({len(native)}): " + ", ".join(name.rsplit("/", 1)[-1] for name in native),
            f"DEX files: {', '.join(dex)}",
            "Chromium/CEF DEX/native marker scan: no matches",
            "",
        ])
    assert found_abis == EXPECTED_ABIS, f"Missing ABI output: {EXPECTED_ABIS - found_abis}"
    output = "\n".join(lines)
    print(output)
    return output


if __name__ == "__main__":
    main()
