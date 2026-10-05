package com.cue.simplebrowser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Local-only metadata and directory lifecycle tests; these do not emulate the Gecko native runtime. */
public final class BrowserProfileStoreSmokeTest {
    private BrowserProfileStoreSmokeTest() { }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("browser-profile-store-").toFile();
        try {
            BrowserProfileStore store = new BrowserProfileStore(root);
            store.initialize("默认环境", "zh-Hans-CN", "Asia/Shanghai");
            BrowserProfileStore.Profile first = store.currentProfile();
            check(store.listProfiles().size() == 1, "fresh installation must create one fallback profile");
            check(first.id.matches("[0-9a-f-]{36}"), "profile IDs must be random UUID values");
            check(first.configurationSummary().contains("informational snapshot only")
                            || first.templateSource.contains("informational snapshot only"),
                    "template source and non-override semantics must be recorded");
            pass("schema-versioned zero-profile fallback and visible template provenance");

            BrowserProfileStore.Profile second = store.createProfile("工作 / 个人", "en-US", "UTC");
            File firstDirectory = store.profileDirectory(first.id);
            File secondDirectory = store.profileDirectory(second.id);
            check(!firstDirectory.equals(secondDirectory), "profiles must use different UUID directories");
            check(firstDirectory.getCanonicalPath().startsWith(new File(root, "fingerprint-profiles").getCanonicalPath()),
                    "Gecko profile path must remain below app-private files root");
            check(!firstDirectory.getName().contains(first.name), "display name must not become a path segment");
            Files.write(new File(firstDirectory, "cookies.sqlite").toPath(), "first-cookie".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(new File(firstDirectory, "storage/default").toPath());
            Files.write(new File(firstDirectory, "storage/default/site-data.sqlite").toPath(),
                    "first-origin-storage".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(new File(secondDirectory, "cache2").toPath());
            Files.write(new File(secondDirectory, "cookies.sqlite").toPath(), "second-cookie".getBytes(StandardCharsets.UTF_8));
            check(!read(new File(firstDirectory, "cookies.sqlite")).equals(read(new File(secondDirectory, "cookies.sqlite"))),
                    "profile data fixtures must remain independent");
            pass("two profiles have distinct app-private storage roots");

            store.renameProfile(first.id, "../../outside/自定义名字");
            check(store.profileDirectory(first.id).equals(firstDirectory), "renaming must not change storage path");
            boolean rejected = false;
            try { store.profileDirectory("../../etc/passwd"); }
            catch (IOException expected) { rejected = true; }
            check(rejected, "caller-controlled profile paths must be rejected");
            String metadata = read(new File(root, "profile-manager/profiles.json"));
            check(metadata.contains("\"schemaVersion\":1"), "metadata must include a schema version");
            check(metadata.contains("../../outside/自定义名字"), "human-readable names remain metadata only");
            pass("path traversal is rejected and names stay out of filesystem paths");

            store.selectProfile(second.id);
            store.requestDelete(first.id);
            check(new File(firstDirectory, "cookies.sqlite").exists(),
                    "delete tombstone must be committed before directory removal");
            BrowserProfileStore afterCrash = new BrowserProfileStore(root);
            afterCrash.initialize("默认环境", "zh-Hans-CN", "Asia/Shanghai");
            check(afterCrash.pendingDeleteIds().contains(first.id),
                    "startup must expose the pending ID so app-owned profile metadata can be cleared first");
            afterCrash.completePendingDeletions();
            check(!firstDirectory.exists(), "startup recovery must remove a pending profile tree");
            check(afterCrash.currentProfile().id.equals(second.id), "recovery must keep the selected profile");
            check(afterCrash.listProfiles().size() == 1, "deleted profile must disappear from metadata");
            pass("crash-safe delete tombstone removes cookies/cache/site storage and metadata");

            BrowserProfileStore.Profile third = afterCrash.createProfile("另一个环境", "fr-FR", "Europe/Paris");
            afterCrash.selectProfile(third.id);
            afterCrash.requestDelete(second.id);
            afterCrash.completePendingDeletions();
            check(!secondDirectory.exists(), "full recursive profile deletion must remove all nested data");
            check(afterCrash.listProfiles().size() == 1 && afterCrash.currentProfile().id.equals(third.id),
                    "a non-current delete must preserve the active fallback");
            BrowserProfileStore.Profile rollback = afterCrash.createProfile("切换失败回滚", "en", "UTC");
            afterCrash.requestDelete(rollback.id);
            afterCrash.cancelPendingDelete(rollback.id);
            check(afterCrash.listProfiles().size() == 2
                            && new File(afterCrash.profileDirectory(rollback.id), "").isDirectory(),
                    "failed restart coordination must be able to restore profile metadata and storage");
            boolean activeDeletionRejected = false;
            try { afterCrash.requestDelete(third.id); }
            catch (IOException expected) { activeDeletionRejected = true; }
            check(activeDeletionRejected, "the active profile cannot be deleted before switching");
            pass("recursive deletion and at-least-one-profile invariant");

            File malformedRoot = Files.createTempDirectory("browser-profile-empty-").toFile();
            try {
                Files.createDirectories(new File(malformedRoot, "profile-manager").toPath());
                Files.write(new File(malformedRoot, "profile-manager/profiles.json").toPath(),
                        "{\"schemaVersion\":1,\"currentProfileId\":null,\"profiles\":[]}".getBytes(StandardCharsets.UTF_8));
                BrowserProfileStore fallback = new BrowserProfileStore(malformedRoot);
                fallback.initialize("安全回退", "en", "UTC");
                check(fallback.listProfiles().size() == 1, "empty valid metadata must recover a usable default profile");
                check(fallback.currentProfile().name.equals("安全回退"), "fallback must be visibly named");
            } finally {
                deleteTree(malformedRoot);
            }
            pass("valid but empty metadata recovers a default profile");

            File orphanRoot = Files.createTempDirectory("browser-profile-orphan-").toFile();
            try {
                File orphan = new File(new File(orphanRoot, "fingerprint-profiles"), UUID.randomUUID().toString());
                check(orphan.mkdirs(), "test orphan directory must be created");
                Files.write(new File(orphan, "data.sqlite").toPath(), new byte[]{1});
                BrowserProfileStore orphanStore = new BrowserProfileStore(orphanRoot);
                orphanStore.initialize("默认环境", "en", "UTC");
                check(!orphan.exists(), "uncommitted UUID directory must be cleaned after a creation crash");
            } finally {
                deleteTree(orphanRoot);
            }
            pass("orphaned incomplete profile creation is reconciled");
            System.out.println("PASS: metadata/directory lifecycle only; GeckoView runtime filesystem isolation requires Android device verification");
        } finally {
            deleteTree(root);
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(String label) { System.out.println("PASS: " + label); }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) throw new IOException("Could not clean test fixture: " + file.getName());
    }
}
