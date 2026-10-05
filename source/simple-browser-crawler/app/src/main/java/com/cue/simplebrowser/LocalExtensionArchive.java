package com.cue.simplebrowser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded local ZIP importer. No URL-based install or remote resource loader exists. */
final class LocalExtensionArchive {
    static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 512 * 1024;
    static final int MAX_ENTRIES = 256;

    static final class PackageData {
        final Mv3ExtensionManifest manifest;
        final Map<String, byte[]> files;
        PackageData(Mv3ExtensionManifest manifest, Map<String, byte[]> files) {
            this.manifest = manifest;
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
            this.files = Collections.unmodifiableMap(copy);
        }
        String text(String path) {
            byte[] value = files.get(path);
            if (value == null) throw new IllegalArgumentException("Missing package resource: " + path);
            try { return decodeUtf8(value, path); }
            catch (IOException error) { throw new IllegalArgumentException("Invalid UTF-8 extension resource: " + path, error); }
        }
    }

    private LocalExtensionArchive() { }

    static PackageData read(InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException("ZIP input is required");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        byte[] archiveBuffer = new byte[8192];
        int archiveCount;
        while ((archiveCount = source.read(archiveBuffer)) != -1) {
            if (archive.size() + archiveCount > MAX_ARCHIVE_BYTES) {
                throw new IOException("ZIP archive exceeds the compressed size limit");
            }
            archive.write(archiveBuffer, 0, archiveCount);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        java.util.Set<String> directories = new java.util.LinkedHashSet<>();
        int entries = 0;
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("ZIP contains more than " + MAX_ENTRIES + " entries");
                String rawName = entry.getName();
                if (entry.isDirectory()) {
                    if (!rawName.endsWith("/")) throw new IOException("Non-canonical ZIP directory entry");
                    String path;
                    try { path = Mv3ExtensionManifest.safePath(rawName.substring(0, rawName.length() - 1), "ZIP directory"); }
                    catch (IllegalArgumentException error) { throw new IOException("Unsafe ZIP directory: " + rawName, error); }
                    if (!directories.add(path) || files.containsKey(path)) throw new IOException("Duplicate or conflicting ZIP directory: " + path);
                    zip.closeEntry();
                    continue;
                }
                String path;
                try { path = Mv3ExtensionManifest.safePath(rawName, "ZIP entry"); }
                catch (IllegalArgumentException error) { throw new IOException("Unsafe ZIP entry: " + rawName, error); }
                if (files.containsKey(path) || directories.contains(path) || directories.stream().anyMatch(d -> d.startsWith(path + "/"))) {
                    throw new IOException("Duplicate or conflicting ZIP entry: " + path);
                }
                String[] segments = path.split("/");
                String parent = "";
                for (int i = 0; i < segments.length - 1; i++) {
                    parent = parent.isEmpty() ? segments[i] : parent + "/" + segments[i];
                    if (files.containsKey(parent)) throw new IOException("ZIP file used as a directory: " + parent);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    if (output.size() + count > MAX_ENTRY_BYTES || total + count > MAX_TOTAL_BYTES) {
                        throw new IOException("ZIP expanded content exceeds the supported size limit");
                    }
                    output.write(buffer, 0, count);
                    total += count;
                }
                files.put(path, output.toByteArray());
                zip.closeEntry();
            }
        }
        byte[] rawManifest = files.get("manifest.json");
        if (rawManifest == null) throw new IOException("ZIP must contain a root manifest.json");
        if (rawManifest.length > MAX_MANIFEST_BYTES) throw new IOException("manifest.json exceeds the supported size limit");
        String manifestJson = decodeUtf8(rawManifest, "manifest.json");
        Mv3ExtensionManifest manifest;
        try { manifest = Mv3ExtensionManifest.parse(manifestJson); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid extension manifest: " + error.getMessage(), error); }
        for (String resource : manifest.resourcePaths) {
            if (!files.containsKey(resource)) throw new IOException("Manifest references missing local resource: " + resource);
        }
        java.util.Set<String> executableText = new java.util.LinkedHashSet<>();
        for (Mv3ExtensionManifest.ContentScript script : manifest.contentScripts) {
            executableText.addAll(script.js);
            executableText.addAll(script.css);
        }
        if (manifest.serviceWorkerPath != null) executableText.add(manifest.serviceWorkerPath);
        for (String file : executableText) {
            String sourceText = decodeUtf8(files.get(file), file);
            if (sourceText.indexOf('\u0000') >= 0) throw new IOException("Script/style resource contains a NUL byte: " + file);
        }
        return new PackageData(manifest, files);
    }

    private static String decodeUtf8(byte[] value, String path) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(value)).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IOException("Invalid UTF-8 in extension resource: " + path, malformed);
        }
    }

    /** App-private, UUID-named store with a canonical-path containment check for every write. */
    static final class Store {
        private final File root;
        Store(File filesDirectory) throws IOException {
            root = new File(filesDirectory, "extensions").getCanonicalFile();
            if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create private extension directory");
            if (!root.isDirectory()) throw new IOException("Extension store is not a directory");
        }

        synchronized String install(PackageData data) throws IOException {
            String id = UUID.randomUUID().toString();
            File target = new File(root, id).getCanonicalFile();
            if (!target.getParentFile().equals(root) || !target.mkdir()) throw new IOException("Could not create extension directory");
            boolean completed = false;
            try {
                for (Map.Entry<String, byte[]> entry : data.files.entrySet()) {
                    String path = Mv3ExtensionManifest.safePath(entry.getKey(), "stored resource");
                    File file = new File(target, path).getCanonicalFile();
                    String targetPath = target.getCanonicalPath() + File.separator;
                    if (!file.getCanonicalPath().startsWith(targetPath)) throw new IOException("Stored path escaped the extension directory");
                    File parent = file.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IOException("Could not create extension resource directory");
                    try (FileOutputStream output = new FileOutputStream(file)) { output.write(entry.getValue()); }
                }
                completed = true;
                return id;
            } finally {
                if (!completed) deleteRecursively(target);
            }
        }

        synchronized List<StoredPackage> list() throws IOException {
            List<StoredPackage> result = new ArrayList<>();
            File[] directories = root.listFiles(File::isDirectory);
            if (directories == null) return result;
            for (File directory : directories) {
                if (!directory.getName().matches("[0-9a-fA-F-]{36}")) continue;
                File canonical = directory.getCanonicalFile();
                if (!canonical.getParentFile().equals(root)) continue;
                Map<String, byte[]> files = new LinkedHashMap<>();
                readDirectory(canonical, canonical, files);
                byte[] manifest = files.get("manifest.json");
                if (manifest == null) continue;
                Mv3ExtensionManifest parsed = Mv3ExtensionManifest.parse(new String(manifest, StandardCharsets.UTF_8));
                for (String resource : parsed.resourcePaths) if (!files.containsKey(resource)) {
                    throw new IOException("Installed extension is missing resource " + resource);
                }
                result.add(new StoredPackage(directory.getName(), new PackageData(parsed, files)));
            }
            return result;
        }

        synchronized boolean remove(String id) throws IOException {
            if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return false;
            File target = new File(root, id).getCanonicalFile();
            if (!target.getParentFile().equals(root)) return false;
            return !target.exists() || deleteRecursively(target);
        }

        private static void readDirectory(File root, File directory, Map<String, byte[]> out) throws IOException {
            File[] children = directory.listFiles();
            if (children == null) return;
            for (File child : children) {
                File canonical = child.getCanonicalFile();
                String rootPath = root.getCanonicalPath() + File.separator;
                if (!canonical.getCanonicalPath().startsWith(rootPath)) throw new IOException("Stored resource escaped extension directory");
                if (child.isDirectory()) readDirectory(root, child, out);
                else {
                    String rootCanonical = root.getCanonicalPath();
                    String childCanonical = child.getCanonicalPath();
                    String path = childCanonical.substring(rootCanonical.length() + 1).replace(File.separatorChar, '/');
                    Mv3ExtensionManifest.safePath(path, "stored resource");
                    if (out.containsKey(path) || child.length() > MAX_ENTRY_BYTES) throw new IOException("Invalid installed resource: " + path);
                    try (FileInputStream input = new FileInputStream(child); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (bytes.size() + count > MAX_ENTRY_BYTES) throw new IOException("Installed resource too large");
                            bytes.write(buffer, 0, count);
                        }
                        out.put(path, bytes.toByteArray());
                    }
                }
            }
        }

        private static boolean deleteRecursively(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
            return file.delete() || !file.exists();
        }
    }

    static final class StoredPackage {
        final String id;
        final PackageData data;
        StoredPackage(String id, PackageData data) { this.id = id; this.data = data; }
    }

}
