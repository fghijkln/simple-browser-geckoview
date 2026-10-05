package com.cue.simplebrowser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * App-private browser profile metadata and Gecko profile directory manager.
 * Profile display names are metadata only; every on-disk path is derived from a random UUID.
 */
final class BrowserProfileStore {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_PROFILES = 100;
    static final int MAX_NAME_LENGTH = 48;

    private static final String METADATA_NAME = "profiles.json";
    private static final String BACKUP_NAME = "profiles.json.bak";
    private static final String TEMP_NAME = "profiles.json.tmp";
    private static final String ACTIVE = "active";
    private static final String DELETING = "deleting";

    static final class Profile {
        final String id;
        final String name;
        final long createdAtMillis;
        final String userAgentTemplate;
        final String localeSnapshot;
        final String timezoneSnapshot;
        final String templateSource;
        final boolean deleting;

        Profile(String id, String name, long createdAtMillis, String userAgentTemplate,
                String localeSnapshot, String timezoneSnapshot, String templateSource,
                boolean deleting) {
            this.id = id;
            this.name = name;
            this.createdAtMillis = createdAtMillis;
            this.userAgentTemplate = userAgentTemplate;
            this.localeSnapshot = localeSnapshot;
            this.timezoneSnapshot = timezoneSnapshot;
            this.templateSource = templateSource;
            this.deleting = deleting;
        }

        Profile withName(String nextName) {
            return new Profile(id, nextName, createdAtMillis, userAgentTemplate,
                    localeSnapshot, timezoneSnapshot, templateSource, deleting);
        }

        Profile asDeleting() {
            return new Profile(id, name, createdAtMillis, userAgentTemplate,
                    localeSnapshot, timezoneSnapshot, templateSource, true);
        }

        String configurationSummary() {
            return "UA 模板：" + userAgentTemplate
                    + "\nLocale 快照：" + localeSnapshot
                    + "\n时区快照：" + timezoneSnapshot
                    + "\n来源：" + templateSource
                    + "\n所有环境共同使用 GeckoView 严格跟踪防护、HTTPS-only、Quad9 DoH-only、无媒体权限和 WebRTC 实验性偏好。"
                    + "\n这些配置不会伪造设备硬件特征；硬件/系统构建、屏幕与字体等引擎可见特征仍来自同一台 Android 设备。";
        }
    }

    private static final class State {
        final List<Profile> profiles;
        final String currentProfileId;

        State(List<Profile> profiles, String currentProfileId) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.currentProfileId = currentProfileId;
        }

        State withProfiles(List<Profile> nextProfiles, String nextCurrentId) {
            return new State(nextProfiles, nextCurrentId);
        }
    }

    private final File metadataDirectory;
    private final File metadataFile;
    private final File backupFile;
    private final File tempFile;
    private final File profileDataRoot;
    private State state;
    private boolean metadataRecovered;

    BrowserProfileStore(File appPrivateFilesDirectory) throws IOException {
        if (appPrivateFilesDirectory == null) throw new IOException("App-private files directory is required");
        File privateRoot = appPrivateFilesDirectory.getCanonicalFile();
        if (!privateRoot.isDirectory() && !privateRoot.mkdirs()) {
            throw new IOException("Could not create app-private files root");
        }
        metadataDirectory = new File(privateRoot, "profile-manager").getCanonicalFile();
        profileDataRoot = new File(privateRoot, "fingerprint-profiles").getCanonicalFile();
        if (!isInside(privateRoot, metadataDirectory) || !isInside(privateRoot, profileDataRoot)) {
            throw new IOException("Profile storage must remain inside app-private files");
        }
        metadataFile = new File(metadataDirectory, METADATA_NAME);
        backupFile = new File(metadataDirectory, BACKUP_NAME);
        tempFile = new File(metadataDirectory, TEMP_NAME);
        ensureDirectory(metadataDirectory);
        ensureDirectory(profileDataRoot);
    }

    synchronized void initialize(String defaultName, String localeSnapshot,
                                 String timezoneSnapshot) throws IOException {
        if (state != null) return;
        boolean hadMetadata = metadataFile.exists() || backupFile.exists();
        try {
            state = hadMetadata ? readStateWithBackup() : new State(Collections.emptyList(), null);
        } catch (IOException | RuntimeException malformed) {
            quarantineMetadataFiles();
            state = new State(Collections.emptyList(), null);
            metadataRecovered = true;
        }

        List<Profile> profiles = new ArrayList<>(state.profiles);
        String currentId = state.currentProfileId;
        boolean changed = false;

        boolean hasUsableProfile = false;
        for (Profile profile : profiles) if (!profile.deleting) hasUsableProfile = true;
        if (!hasUsableProfile) {
            Profile fallback = newProfile(defaultName, localeSnapshot, timezoneSnapshot);
            ensureProfileDirectory(fallback.id);
            profiles.add(fallback);
            currentId = fallback.id;
            changed = true;
        }

        boolean currentUsable = false;
        for (Profile profile : profiles) {
            if (!profile.deleting && profile.id.equals(currentId)) currentUsable = true;
        }
        if (!currentUsable) {
            currentId = firstUsable(profiles).id;
            changed = true;
        }

        for (Profile profile : profiles) {
            if (!profile.deleting) ensureProfileDirectory(profile.id);
        }
        if (changed || !metadataFile.exists()) {
            commit(new State(profiles, currentId));
        } else {
            state = new State(profiles, currentId);
        }

        if (!metadataRecovered) cleanupOrphanUuidDirectories();
    }

    synchronized List<Profile> listProfiles() {
        requireInitialized();
        List<Profile> result = new ArrayList<>();
        for (Profile profile : state.profiles) if (!profile.deleting) result.add(profile);
        return Collections.unmodifiableList(result);
    }

    synchronized Profile currentProfile() {
        requireInitialized();
        for (Profile profile : state.profiles) {
            if (!profile.deleting && profile.id.equals(state.currentProfileId)) return profile;
        }
        throw new IllegalStateException("No active browser profile is available");
    }

    synchronized File profileDirectory(String profileId) throws IOException {
        requireInitialized();
        Profile profile = findProfile(profileId);
        if (profile == null || profile.deleting) throw new IOException("Unknown browser profile");
        File directory = profileDirectoryForId(profile.id);
        ensureProfileDirectory(profile.id);
        return directory.getCanonicalFile();
    }

    synchronized Profile createProfile(String name, String localeSnapshot,
                                       String timezoneSnapshot) throws IOException {
        requireInitialized();
        List<Profile> current = new ArrayList<>(listProfiles());
        if (current.size() >= MAX_PROFILES) throw new IOException("Profile limit reached");
        String cleanName = validateName(name);
        Profile profile = newProfile(cleanName, localeSnapshot, timezoneSnapshot);
        ensureProfileDirectory(profile.id);
        current.add(profile);
        try {
            commit(new State(current, state.currentProfileId));
        } catch (IOException error) {
            deleteTreeNoFollow(profileDirectoryForId(profile.id).toPath());
            throw error;
        }
        return profile;
    }

    synchronized void renameProfile(String profileId, String name) throws IOException {
        requireInitialized();
        String cleanName = validateName(name);
        List<Profile> updated = new ArrayList<>();
        boolean found = false;
        for (Profile profile : state.profiles) {
            if (profile.id.equals(profileId) && !profile.deleting) {
                updated.add(profile.withName(cleanName));
                found = true;
            } else {
                updated.add(profile);
            }
        }
        if (!found) throw new IOException("Unknown browser profile");
        commit(new State(updated, state.currentProfileId));
    }

    synchronized void selectProfile(String profileId) throws IOException {
        requireInitialized();
        Profile target = findProfile(profileId);
        if (target == null || target.deleting) throw new IOException("Unknown browser profile");
        ensureProfileDirectory(target.id);
        commit(new State(state.profiles, target.id));
    }

    /** Persist a tombstone first; directory removal is intentionally a separate recoverable step. */
    synchronized void requestDelete(String profileId) throws IOException {
        requireInitialized();
        if (profileId.equals(state.currentProfileId)) {
            throw new IOException("Switch to another profile before deleting the active profile");
        }
        List<Profile> updated = new ArrayList<>();
        boolean found = false;
        for (Profile profile : state.profiles) {
            if (profile.id.equals(profileId) && !profile.deleting) {
                updated.add(profile.asDeleting());
                found = true;
            } else {
                updated.add(profile);
            }
        }
        if (!found) throw new IOException("Unknown browser profile");
        if (usableCount(updated) < 1) throw new IOException("At least one profile must remain");
        commit(new State(updated, state.currentProfileId));
    }

    synchronized void cancelPendingDelete(String profileId) throws IOException {
        requireInitialized();
        String safeId = validateId(profileId);
        List<Profile> updated = new ArrayList<>();
        boolean found = false;
        for (Profile profile : state.profiles) {
            if (profile.id.equals(safeId) && profile.deleting) {
                updated.add(new Profile(profile.id, profile.name, profile.createdAtMillis,
                        profile.userAgentTemplate, profile.localeSnapshot, profile.timezoneSnapshot,
                        profile.templateSource, false));
                found = true;
            } else {
                updated.add(profile);
            }
        }
        if (!found) throw new IOException("No pending deletion for this profile");
        commit(new State(updated, state.currentProfileId));
    }

    synchronized void completePendingDeletions() throws IOException {
        requireInitialized();
        List<Profile> kept = new ArrayList<>();
        boolean changed = false;
        for (Profile profile : state.profiles) {
            if (profile.deleting) {
                deleteTreeNoFollow(profileDirectoryForId(profile.id).toPath());
                changed = true;
            } else {
                kept.add(profile);
            }
        }
        if (changed) {
            if (kept.isEmpty()) throw new IOException("Deletion would leave no browser profile");
            String currentId = state.currentProfileId;
            boolean currentExists = false;
            for (Profile profile : kept) if (profile.id.equals(currentId)) currentExists = true;
            if (!currentExists) currentId = kept.get(0).id;
            commit(new State(kept, currentId));
        }
    }

    synchronized boolean isCurrent(String profileId) {
        requireInitialized();
        return state.currentProfileId.equals(profileId);
    }

    synchronized List<String> pendingDeleteIds() {
        requireInitialized();
        List<String> pending = new ArrayList<>();
        for (Profile profile : state.profiles) if (profile.deleting) pending.add(profile.id);
        return Collections.unmodifiableList(pending);
    }

    synchronized boolean wasMetadataRecovered() {
        return metadataRecovered;
    }

    private State readStateWithBackup() throws IOException {
        IOException primaryError = null;
        if (metadataFile.exists()) {
            try {
                return parseState(new String(Files.readAllBytes(metadataFile.toPath()), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException error) {
                primaryError = new IOException("Profile metadata is damaged", error);
            }
        }
        if (backupFile.exists()) {
            try {
                State recovered = parseState(new String(Files.readAllBytes(backupFile.toPath()), StandardCharsets.UTF_8));
                writeStateFile(recovered);
                return recovered;
            } catch (IOException | RuntimeException error) {
                if (primaryError != null) error.addSuppressed(primaryError);
                throw new IOException("No valid profile metadata copy is available", error);
            }
        }
        if (primaryError != null) throw primaryError;
        return new State(Collections.emptyList(), null);
    }

    private State parseState(String json) throws IOException {
        try {
            java.util.Map<String, Object> root = MiniJson.object(MiniJson.parse(json), "profile metadata");
            int version = MiniJson.integer(root.get("schemaVersion"), "schemaVersion");
            if (version != SCHEMA_VERSION) throw new IOException("Unsupported profile metadata schema " + version);
            String currentId = nullableString(root.get("currentProfileId"));
            List<Object> serializedProfiles = MiniJson.array(root.get("profiles"), "profiles");
            if (serializedProfiles.size() > MAX_PROFILES) throw new IOException("Too many stored profiles");
            List<Profile> profiles = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Object item : serializedProfiles) {
                java.util.Map<String, Object> value = MiniJson.object(item, "profile");
                String id = validateId(MiniJson.string(value.get("id"), "profile id"));
                if (!ids.add(id)) throw new IOException("Duplicate profile id");
                String name = validateName(MiniJson.string(value.get("name"), "profile name"));
                long created = numberAsLong(value.get("createdAtMillis"), "createdAtMillis");
                String ua = boundedString(value.get("userAgentTemplate"), "userAgentTemplate", 96);
                String locale = boundedString(value.get("localeSnapshot"), "localeSnapshot", 96);
                String timezone = boundedString(value.get("timezoneSnapshot"), "timezoneSnapshot", 96);
                String source = boundedString(value.get("templateSource"), "templateSource", 160);
                String status = MiniJson.string(value.get("status"), "status");
                if (!ACTIVE.equals(status) && !DELETING.equals(status)) throw new IOException("Invalid profile status");
                profiles.add(new Profile(id, name, created, ua, locale, timezone, source, DELETING.equals(status)));
            }
            if (currentId != null) currentId = validateId(currentId);
            return new State(profiles, currentId);
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Invalid profile metadata", error);
        }
    }

    private void commit(State next) throws IOException {
        writeStateFile(next);
        state = next;
    }

    private void writeStateFile(State value) throws IOException {
        ensureDirectory(metadataDirectory);
        byte[] bytes = serialize(value).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(tempFile, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (metadataFile.exists()) {
            Files.copy(metadataFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try (FileOutputStream backup = new FileOutputStream(backupFile, true)) {
                backup.getFD().sync();
            }
        }
        try {
            Files.move(tempFile.toPath(), metadataFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(tempFile.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(backupFile.toPath());
    }

    private String serialize(State value) {
        StringBuilder json = new StringBuilder(256 + value.profiles.size() * 256);
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
                .append(",\"currentProfileId\":");
        if (value.currentProfileId == null) json.append("null");
        else appendJsonString(json, value.currentProfileId);
        json.append(",\"profiles\":[");
        for (int i = 0; i < value.profiles.size(); i++) {
            Profile profile = value.profiles.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":"); appendJsonString(json, profile.id);
            json.append(",\"name\":"); appendJsonString(json, profile.name);
            json.append(",\"createdAtMillis\":").append(profile.createdAtMillis);
            json.append(",\"userAgentTemplate\":"); appendJsonString(json, profile.userAgentTemplate);
            json.append(",\"localeSnapshot\":"); appendJsonString(json, profile.localeSnapshot);
            json.append(",\"timezoneSnapshot\":"); appendJsonString(json, profile.timezoneSnapshot);
            json.append(",\"templateSource\":"); appendJsonString(json, profile.templateSource);
            json.append(",\"status\":"); appendJsonString(json, profile.deleting ? DELETING : ACTIVE);
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (c < 0x20) output.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else output.append(c);
            }
        }
        output.append('"');
    }

    private Profile newProfile(String name, String localeSnapshot, String timezoneSnapshot) throws IOException {
        return new Profile(UUID.randomUUID().toString(), validateName(name), System.currentTimeMillis(),
                "GeckoView mobile default (no spoofed UA)", normalizeSnapshot(localeSnapshot, 96),
                normalizeSnapshot(timezoneSnapshot, 96),
                "Captured from Android system defaults when this profile was created; informational snapshot only",
                false);
    }

    private void ensureProfileDirectory(String id) throws IOException {
        File directory = profileDirectoryForId(id);
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Profile path must not be a symbolic link");
        ensureDirectory(directory);
        File canonical = directory.getCanonicalFile();
        if (!isInside(profileDataRoot, canonical) || !canonical.equals(directory.getCanonicalFile())) {
            throw new IOException("Profile path escaped its app-private storage root");
        }
    }

    private File profileDirectoryForId(String id) throws IOException {
        String safeId = validateId(id);
        File canonicalRoot = profileDataRoot.getCanonicalFile();
        File child = new File(canonicalRoot, safeId);
        if (!isInside(canonicalRoot, child.getCanonicalFile())) {
            throw new IOException("Invalid profile data path");
        }
        return child;
    }

    private void cleanupPendingDeletions() throws IOException {
        completePendingDeletions();
    }

    private void cleanupOrphanUuidDirectories() throws IOException {
        Set<String> retained = new HashSet<>();
        for (Profile profile : state.profiles) retained.add(profile.id);
        File[] entries = profileDataRoot.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!isValidId(entry.getName()) || retained.contains(entry.getName())) continue;
            deleteTreeNoFollow(entry.toPath());
        }
    }

    private void quarantineMetadataFiles() throws IOException {
        long stamp = System.currentTimeMillis();
        if (metadataFile.exists()) {
            Files.move(metadataFile.toPath(), new File(metadataDirectory,
                    "profiles.corrupt." + stamp + ".json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (backupFile.exists()) {
            Files.move(backupFile.toPath(), new File(metadataDirectory,
                    "profiles.corrupt." + stamp + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(tempFile.toPath());
    }

    private static void deleteTreeNoFollow(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Profile findProfile(String id) throws IOException {
        String safeId = validateId(id);
        for (Profile profile : state.profiles) if (profile.id.equals(safeId)) return profile;
        return null;
    }

    private static Profile firstUsable(List<Profile> profiles) {
        for (Profile profile : profiles) if (!profile.deleting) return profile;
        throw new IllegalStateException("No usable browser profile");
    }

    private static int usableCount(List<Profile> profiles) {
        int count = 0;
        for (Profile profile : profiles) if (!profile.deleting) count++;
        return count;
    }

    private void requireInitialized() {
        if (state == null) throw new IllegalStateException("Profile store has not been initialized");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Private storage path must not be a symbolic link");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create app-private directory: " + directory.getName());
        }
    }

    private static boolean isInside(File parent, File child) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
    }

    private static String validateId(String value) throws IOException {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equals(value)) throw new IOException("Invalid browser profile ID");
            return normalized;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Invalid browser profile ID", error);
        }
    }

    private static boolean isValidId(String value) {
        try { validateId(value); return true; } catch (IOException ignored) { return false; }
    }

    private static String validateName(String value) throws IOException {
        if (value == null) throw new IOException("Profile name is required");
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > MAX_NAME_LENGTH) {
            throw new IOException("Profile name must contain 1 to " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) throw new IOException("Profile name contains a control character");
        }
        return clean;
    }

    private static String boundedString(Object value, String label, int maxLength) throws IOException {
        String text = MiniJson.string(value, label);
        if (text.length() > maxLength) throw new IOException(label + " is too long");
        return text;
    }

    private static String nullableString(Object value) throws IOException {
        if (value == null) return null;
        return boundedString(value, "currentProfileId", 36);
    }

    private static long numberAsLong(Object value, String label) throws IOException {
        if (!(value instanceof Number)) throw new IOException(label + " must be a number");
        Number number = (Number) value;
        double decimal = number.doubleValue();
        long whole = number.longValue();
        if (!Double.isFinite(decimal) || decimal != (double) whole || whole < 0) {
            throw new IOException(label + " is invalid");
        }
        return whole;
    }

    private static String normalizeSnapshot(String value, int maxLength) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) clean = "system-default";
        if (clean.length() > maxLength) clean = clean.substring(0, maxLength);
        return clean;
    }
}
