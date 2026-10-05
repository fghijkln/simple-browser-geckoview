package com.cue.simplebrowser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free safety tests for inspecting and retaining local MV3 ZIPs; these tests do not execute extensions. */
public final class Mv3ExtensionSmokeTest {
    private static final String MANIFEST = "{\"manifest_version\":3,\"name\":\"Local Test\","
            + "\"version\":\"1.2\",\"description\":\"test\","
            + "\"permissions\":[\"storage\",\"tabs\",\"scripting\",\"cookies\"],"
            + "\"host_permissions\":[\"https://*.example.org/*\"],"
            + "\"content_scripts\":[{\"matches\":[\"https://*.example.org/*\"],"
            + "\"js\":[\"content.js\"],\"css\":[\"style.css\"],\"run_at\":\"document_start\"}],"
            + "\"background\":{\"service_worker\":\"worker.js\"}}";

    private Mv3ExtensionSmokeTest() { }

    public static void main(String[] args) throws Exception {
        testManifestValidationAndMetadataOnlyReview();
        testSafeArchiveRetentionAndRemoval();
        System.out.println("PASS: MV3 manifest metadata review, bounded ZIP validation, path/traversal defense, "
                + "private retention/removal; no third-party extension execution is claimed or tested");
    }

    private static void testManifestValidationAndMetadataOnlyReview() {
        Mv3ExtensionManifest manifest = Mv3ExtensionManifest.parse(MANIFEST);
        check(manifest.name.equals("Local Test"), "manifest name should load for review");
        check(manifest.version.equals("1.2"), "manifest version should load for review");
        check(manifest.unsupportedPermissions.contains("cookies"), "unsupported permission must be reported");
        check(manifest.unsupportedFeatures.toString().contains("service_worker"),
                "service worker lifecycle must be marked unsupported");
        check(manifest.contentScripts.get(0).matches("https://news.example.org/page"),
                "declared match patterns may be inspected as metadata");
        check(!manifest.contentScripts.get(0).matches("http://news.example.org/page"),
                "HTTP must not match a declared HTTPS-only pattern");
        check(!manifest.contentScripts.get(0).matches("https://example.org.evil.test/page"),
                "suffix-lookalike host must not match metadata pattern");
        check(Mv3ExtensionManifest.parse(MANIFEST.replace("\"run_at\":\"document_start\"",
                "\"run_at\":\"document_start\",\"exclude_matches\":[\"https://bad.example.org/*\"]"))
                .unsupportedFeatures.toString().contains("exclude_matches"),
                "ignored content-script fields must be disclosed");
        rejects(() -> Mv3ExtensionManifest.parse(MANIFEST.replace("\"manifest_version\":3", "\"manifest_version\":2")),
                "Manifest V2 must reject");
        rejects(() -> Mv3ExtensionManifest.parse(MANIFEST.replace("\"scripting\",", "\"storage\",\"scripting\",")),
                "duplicate declared permissions must reject");
        rejects(() -> Mv3ExtensionManifest.parse(MANIFEST.replace("https://*.example.org/*", "http://*.example.org/*")),
                "non-HTTPS host permissions must reject");
        rejects(() -> Mv3ExtensionManifest.safePath("../escape.js", "resource"),
                "traversal resource path must reject");
        rejects(() -> Mv3ExtensionManifest.safePath("/absolute.js", "resource"),
                "absolute resource path must reject");
        rejects(() -> Mv3ExtensionManifest.safePath("a\\\\b.js", "resource"),
                "backslash path must reject");
        StringBuilder deeplyNested = new StringBuilder();
        for (int i = 0; i < 129; i++) deeplyNested.append('[');
        deeplyNested.append('0');
        for (int i = 0; i < 129; i++) deeplyNested.append(']');
        rejects(() -> MiniJson.parse(deeplyNested.toString()), "excessive JSON nesting must reject safely");
    }

    private static void testSafeArchiveRetentionAndRemoval() throws Exception {
        LocalExtensionArchive.PackageData reviewed = LocalExtensionArchive.read(new ByteArrayInputStream(zip(
                new String[][]{{"manifest.json", MANIFEST}, {"content.js", "window.localScriptRan=true;"},
                        {"style.css", "body{outline:0}"}, {"worker.js", "self.onmessage=()=>{};"},
                        {"extra.js", "window.localExtra=true;"}})));
        check(reviewed.manifest.name.equals("Local Test"), "valid archive should parse for review");
        check(reviewed.manifest.resourcePaths.contains("worker.js"), "declared resource path should validate");
        java.nio.file.Path privateFiles = java.nio.file.Files.createTempDirectory("cue-extension-review-test");
        LocalExtensionArchive.Store store = new LocalExtensionArchive.Store(privateFiles.toFile());
        String retainedId = store.install(reviewed);
        java.io.File retainedDirectory = new java.io.File(privateFiles.toFile(), "extensions/" + retainedId);
        check(retainedDirectory.getCanonicalPath().startsWith(
                        new java.io.File(privateFiles.toFile(), "extensions").getCanonicalPath()
                                + java.io.File.separator),
                "retained files must remain under the private extension directory");
        check(store.list().size() == 1 && store.list().get(0).data.manifest.name.equals("Local Test"),
                "private store should revalidate and reload retained package metadata");
        check(store.remove(retainedId) && store.list().isEmpty(), "removal should delete the private package");
        java.nio.file.Files.deleteIfExists(new java.io.File(privateFiles.toFile(), "extensions").toPath());
        java.nio.file.Files.deleteIfExists(privateFiles);

        rejectsIo(() -> LocalExtensionArchive.read(new ByteArrayInputStream(zip(
                new String[][]{{"manifest.json", MANIFEST}, {"../evil.js", "no"}}))),
                "ZIP traversal entry must reject");
        rejectsIo(() -> LocalExtensionArchive.read(new ByteArrayInputStream(zip(
                new String[][]{{"manifest.json", MANIFEST}, {"/absolute.js", "no"}}))),
                "absolute ZIP entry must reject");
        rejectsIo(() -> LocalExtensionArchive.read(new ByteArrayInputStream(zip(
                new String[][]{{"manifest.json", MANIFEST}, {"content.js", "x"}}))),
                "missing referenced resources must reject");
        byte[] validArchive = zip(new String[][]{{"manifest.json", MANIFEST}, {"content.js", ""},
                {"style.css", ""}, {"worker.js", ""}});
        byte[] oversizedArchive = new byte[LocalExtensionArchive.MAX_ARCHIVE_BYTES + 1];
        System.arraycopy(validArchive, 0, oversizedArchive, 0, validArchive.length);
        java.util.Arrays.fill(oversizedArchive, validArchive.length, oversizedArchive.length, (byte) 'x');
        rejectsIo(() -> LocalExtensionArchive.read(new ByteArrayInputStream(oversizedArchive)),
                "trailing ZIP data must count toward the archive cap");
        char[] huge = new char[LocalExtensionArchive.MAX_ENTRY_BYTES + 1];
        java.util.Arrays.fill(huge, 'x');
        rejectsIo(() -> LocalExtensionArchive.read(new ByteArrayInputStream(zip(
                new String[][]{{"manifest.json", MANIFEST}, {"content.js", new String(huge)},
                        {"style.css", ""}, {"worker.js", ""}}))),
                "oversized expanded entry must reject");
        String[][] many = new String[LocalExtensionArchive.MAX_ENTRIES + 1][2];
        many[0] = new String[]{"manifest.json", "{\"manifest_version\":3,\"name\":\"Many\",\"version\":\"1\"}"};
        for (int i = 1; i < many.length; i++) many[i] = new String[]{"f" + i, ""};
        rejectsIo(() -> LocalExtensionArchive.read(new ByteArrayInputStream(zip(many))),
                "excessive ZIP entry count must reject");
    }

    private static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] item : entries) {
                zip.putNextEntry(new ZipEntry(item[0]));
                zip.write(item[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation op, String message) {
        try { op.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { }
        catch (Exception unexpected) { throw new AssertionError(message, unexpected); }
    }
    private static void rejectsIo(Operation op, String message) throws Exception {
        try { op.run(); throw new AssertionError(message); }
        catch (IOException expected) { }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
