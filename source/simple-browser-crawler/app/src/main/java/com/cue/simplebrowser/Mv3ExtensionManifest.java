package com.cue.simplebrowser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deliberately limited Manifest V3 parser for user-imported local ZIP packages. */
final class Mv3ExtensionManifest {
    static final Set<String> SUPPORTED_PERMISSIONS;
    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final Set<String> KNOWN_KEYS;
    static {
        Set<String> supported = new LinkedHashSet<>();
        Collections.addAll(supported, "storage", "tabs", "scripting");
        SUPPORTED_PERMISSIONS = Collections.unmodifiableSet(supported);
        Set<String> known = new LinkedHashSet<>();
        Collections.addAll(known, "manifest_version", "name", "version", "description", "permissions",
                "host_permissions", "content_scripts", "background", "action", "web_accessible_resources",
                "icons", "default_locale", "minimum_chrome_version", "author", "homepage_url",
                "short_name", "options_page", "options_ui", "commands", "content_security_policy",
                "incognito", "offline_enabled", "update_url", "version_name");
        KNOWN_KEYS = Collections.unmodifiableSet(known);
    }

    static final class ContentScript {
        final List<UrlMatchPattern> matches;
        final List<String> js;
        final List<String> css;
        final String runAt;
        ContentScript(List<UrlMatchPattern> matches, List<String> js, List<String> css, String runAt) {
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
            this.js = Collections.unmodifiableList(new ArrayList<>(js));
            this.css = Collections.unmodifiableList(new ArrayList<>(css));
            this.runAt = runAt;
        }
        boolean matches(String url) {
            for (UrlMatchPattern pattern : matches) if (pattern.matches(url)) return true;
            return false;
        }
    }

    final String name;
    final String version;
    final String description;
    final Set<String> permissions;
    final Set<String> unsupportedPermissions;
    final List<UrlMatchPattern> hostPermissions;
    final List<ContentScript> contentScripts;
    final Set<String> resourcePaths;
    final List<String> unsupportedFeatures;
    final String serviceWorkerPath;

    private Mv3ExtensionManifest(String name, String version, String description, Set<String> permissions,
            Set<String> unsupportedPermissions, List<UrlMatchPattern> hostPermissions,
            List<ContentScript> contentScripts, Set<String> resourcePaths,
            List<String> unsupportedFeatures, String serviceWorkerPath) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.permissions = Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
        this.unsupportedPermissions = Collections.unmodifiableSet(new LinkedHashSet<>(unsupportedPermissions));
        this.hostPermissions = Collections.unmodifiableList(new ArrayList<>(hostPermissions));
        this.contentScripts = Collections.unmodifiableList(new ArrayList<>(contentScripts));
        this.resourcePaths = Collections.unmodifiableSet(new LinkedHashSet<>(resourcePaths));
        this.unsupportedFeatures = Collections.unmodifiableList(new ArrayList<>(unsupportedFeatures));
        this.serviceWorkerPath = serviceWorkerPath;
    }

    static Mv3ExtensionManifest parse(String json) {
        Map<String, Object> root = MiniJson.object(MiniJson.parse(json), "manifest");
        if (MiniJson.integer(root.get("manifest_version"), "manifest_version") != 3) {
            throw new IllegalArgumentException("Only Manifest V3 packages are accepted");
        }
        if (root.containsKey("cue_extension_format")) {
            throw new IllegalArgumentException("Cue's bundled ad-block format is not an installable Chrome extension");
        }
        String name = MiniJson.string(root.get("name"), "name");
        String version = MiniJson.string(root.get("version"), "version");
        String description = root.containsKey("description")
                ? MiniJson.string(root.get("description"), "description") : "No description";
        Set<String> permissions = new LinkedHashSet<>();
        Set<String> unsupportedPermissions = new LinkedHashSet<>();
        Set<String> seenPermissions = new LinkedHashSet<>();
        Object rawPermissions = root.get("permissions");
        if (rawPermissions != null) {
            for (Object value : MiniJson.array(rawPermissions, "permissions")) {
                String permission = MiniJson.string(value, "permission");
                if (!seenPermissions.add(permission)) {
                    throw new IllegalArgumentException("Duplicate permission: " + permission);
                }
                permissions.add(permission);
                if (!SUPPORTED_PERMISSIONS.contains(permission)) unsupportedPermissions.add(permission);
            }
        }
        List<String> unsupported = new ArrayList<>();
        for (String key : root.keySet()) {
            if (!KNOWN_KEYS.contains(key)) unsupported.add("manifest key: " + key);
        }
        if (!unsupportedPermissions.isEmpty()) unsupported.add("Unsupported permissions: " + join(unsupportedPermissions));

        List<UrlMatchPattern> hosts = new ArrayList<>();
        Object rawHosts = root.get("host_permissions");
        if (rawHosts != null) {
            for (Object value : MiniJson.array(rawHosts, "host_permissions")) {
                hosts.add(UrlMatchPattern.parse(MiniJson.string(value, "host permission")));
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        List<Mv3ExtensionManifest.ContentScript> scripts = new ArrayList<>();
        Object rawScripts = root.get("content_scripts");
        if (rawScripts != null) {
            for (Object rawScript : MiniJson.array(rawScripts, "content_scripts")) {
                Map<String, Object> script = MiniJson.object(rawScript, "content script");
                for (String key : script.keySet()) {
                if (!"matches".equals(key) && !"js".equals(key) && !"css".equals(key)
                        && !"run_at".equals(key) && !"all_frames".equals(key)) {
                        unsupported.add("content_scripts." + key);
                    }
                }
                List<UrlMatchPattern> matches = new ArrayList<>();
                for (Object value : MiniJson.array(script.get("matches"), "content script matches")) {
                    UrlMatchPattern pattern = UrlMatchPattern.parse(MiniJson.string(value, "content script match"));
                    matches.add(pattern);
                    if (!coveredByHost(pattern.source(), hosts)) {
                        throw new IllegalArgumentException("Content script match must also be covered by explicit host_permissions: " + pattern.source());
                    }
                }
                List<String> js = readPaths(script.get("js"), "content script js", paths);
                List<String> css = readPaths(script.get("css"), "content script css", paths);
                if (matches.isEmpty() || (js.isEmpty() && css.isEmpty())) {
                    throw new IllegalArgumentException("Content scripts need HTTPS matches and local JS or CSS resources");
                }
                String runAt = script.containsKey("run_at")
                        ? MiniJson.string(script.get("run_at"), "run_at") : "document_idle";
                if (!"document_start".equals(runAt) && !"document_end".equals(runAt)
                        && !"document_idle".equals(runAt)) {
                    throw new IllegalArgumentException("Unsupported content script run_at: " + runAt);
                }
                if (script.containsKey("all_frames") && !(script.get("all_frames") instanceof Boolean)) {
                    throw new IllegalArgumentException("content_scripts.all_frames must be a boolean");
                }
                if (script.containsKey("all_frames") && Boolean.TRUE.equals(script.get("all_frames"))) {
                    unsupported.add("content_scripts.all_frames (main frame only)");
                }
                if ("document_idle".equals(runAt)) unsupported.add("content_scripts.run_at=document_idle (approximated as document_end)");
                scripts.add(new ContentScript(matches, js, css, runAt));
            }
        }
        String worker = null;
        if (root.containsKey("background")) {
            Map<String, Object> background = MiniJson.object(root.get("background"), "background");
            if (background.containsKey("service_worker")) {
                worker = safePath(background.get("service_worker"), "background.service_worker");
                paths.add(worker);
                unsupported.add("background.service_worker lifecycle (not executed; no persistent or event worker)");
            }
            if (background.containsKey("scripts") || background.containsKey("page")) {
                unsupported.add("legacy background scripts/pages");
            }
            for (String key : background.keySet()) {
                if (!"service_worker".equals(key) && !"scripts".equals(key) && !"page".equals(key)) {
                    unsupported.add("background." + key);
                }
            }
        }
        if (root.containsKey("action")) {
            Map<String, Object> action = MiniJson.object(root.get("action"), "action");
            if (action.containsKey("default_popup")) {
                paths.add(safePath(action.get("default_popup"), "action.default_popup"));
                unsupported.add("action popup UI (not opened by this browser)");
            }
            if (action.containsKey("default_title")) unsupported.add("action.default_title");
            for (String key : action.keySet()) {
                if (!"default_popup".equals(key) && !"default_title".equals(key)) unsupported.add("action." + key);
            }
        }
        if (root.containsKey("web_accessible_resources")) {
            validateWebAccessibleResources(root.get("web_accessible_resources"), paths);
            unsupported.add("web_accessible_resources serving");
        }
        for (String metadata : new String[]{"icons", "default_locale", "minimum_chrome_version", "author",
                "homepage_url", "short_name", "options_page", "options_ui", "commands", "content_security_policy",
                "incognito", "offline_enabled", "update_url", "version_name"}) {
            if (root.containsKey(metadata)) unsupported.add("manifest." + metadata);
        }
        for (UrlMatchPattern scriptMatch : flattenMatches(scripts)) {
            if (!coveredByHost(scriptMatch.source(), hosts)) {
                throw new IllegalArgumentException("Content script host access is not explicitly declared");
            }
        }
        return new Mv3ExtensionManifest(name, version, description, permissions, unsupportedPermissions,
                hosts, scripts, paths, unsupported, worker);
    }

    boolean hasPermission(String permission) { return permissions.contains(permission); }

    boolean grantsHost(String url) {
        for (UrlMatchPattern pattern : hostPermissions) if (pattern.matches(url)) return true;
        return false;
    }

    boolean matchesContentScript(String url) {
        for (ContentScript script : contentScripts) if (script.matches(url)) return true;
        return false;
    }

    private static boolean coveredByHost(String pattern, List<UrlMatchPattern> hosts) {
        for (UrlMatchPattern host : hosts) {
            if (host.source().equals("https://*/*") || host.source().equals(pattern)) return true;
            if (host.source().startsWith("https://*.") && pattern.startsWith("https://")) {
                String suffix = host.source().substring("https://*".length(), host.source().length() - 2);
                String authority = pattern.substring("https://".length(), pattern.length() - 2);
                if (authority.equals(suffix.substring(1)) || authority.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private static List<UrlMatchPattern> flattenMatches(List<ContentScript> scripts) {
        List<UrlMatchPattern> all = new ArrayList<>();
        for (ContentScript script : scripts) all.addAll(script.matches);
        return all;
    }

    private static String join(Set<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private static List<String> readPaths(Object value, String label, Set<String> all) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        for (Object item : MiniJson.array(value, label)) {
            String path = safePath(item, label);
            if (!result.add(path)) throw new IllegalArgumentException("Duplicate resource reference: " + path);
            all.add(path);
        }
        return result;
    }

    private static void validateWebAccessibleResources(Object value, Set<String> paths) {
        for (Object item : MiniJson.array(value, "web_accessible_resources")) {
            Map<String, Object> declaration = MiniJson.object(item, "web accessible resource declaration");
            readPaths(declaration.get("resources"), "web accessible resources", paths);
        }
    }

    static String safePath(Object value, String description) {
        String path = MiniJson.string(value, description);
        if (!SAFE_PATH.matcher(path).matches() || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.contains("%") || path.contains("//")) {
            throw new IllegalArgumentException(description + " must be a canonical relative local path");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(description + " contains an empty, dot, or traversal segment");
            }
        }
        return path;
    }
}
