package com.cue.simplebrowser;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small versioned local store for declarative custom search-engine definitions. */
final class CustomEngineStore {
    interface Persistence {
        String read();
        void write(String value);
    }

    private static final String VERSION = "custom-search-engines-v1";
    private final Persistence persistence;
    private final ArrayList<SearchEngine> engines = new ArrayList<>();

    CustomEngineStore(Persistence persistence) {
        if (persistence == null) {
            throw new IllegalArgumentException("Persistence is required");
        }
        this.persistence = persistence;
    }

    void load() {
        engines.clear();
        String stored = persistence.read();
        if (stored == null || !stored.startsWith(VERSION + "\n")) {
            return;
        }
        Set<String> ids = new HashSet<>();
        String[] records = stored.substring(VERSION.length() + 1).split("\n");
        for (String record : records) {
            if (record.isEmpty()) {
                continue;
            }
            try {
                String[] fields = record.split("\t", -1);
                if (fields.length != 6) {
                    continue;
                }
                String id = decode(fields[0]);
                String name = decode(fields[1]);
                String urlTemplate = decode(fields[2]);
                SearchEngine.Category category = SearchEngine.Category.valueOf(decode(fields[3]));
                String purpose = decode(fields[4]);
                String accessNote = decode(fields[5]);
                SearchEngine engine = SearchEngine.createCustom(id, name, urlTemplate,
                        category, purpose, accessNote);
                if (ids.add(engine.id)) {
                    engines.add(engine);
                }
            } catch (Exception ignored) {
                // Ignore malformed or obsolete records instead of preventing browser startup.
            }
        }
    }

    List<SearchEngine> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(engines));
    }

    boolean add(SearchEngine engine) {
        if (!isValidCustom(engine) || indexOf(engine.id) >= 0) {
            return false;
        }
        engines.add(engine);
        persist();
        return true;
    }

    boolean edit(String id, SearchEngine replacement) {
        int index = indexOf(id);
        if (index < 0 || !isValidCustom(replacement) || !id.equals(replacement.id)) {
            return false;
        }
        engines.set(index, replacement);
        persist();
        return true;
    }

    boolean remove(String id) {
        int index = indexOf(id);
        if (index < 0) {
            return false;
        }
        engines.remove(index);
        persist();
        return true;
    }

    private boolean isValidCustom(SearchEngine engine) {
        return engine != null && engine.custom
                && SearchEngine.validateUrlTemplate(engine.urlTemplate) == null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < engines.size(); i++) {
            if (engines.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void persist() {
        StringBuilder stored = new StringBuilder(VERSION).append('\n');
        for (SearchEngine engine : engines) {
            stored.append(encode(engine.id)).append('\t')
                    .append(encode(engine.name)).append('\t')
                    .append(encode(engine.urlTemplate)).append('\t')
                    .append(encode(engine.category.name())).append('\t')
                    .append(encode(engine.purpose)).append('\t')
                    .append(encode(engine.accessNote)).append('\n');
        }
        persistence.write(stored.toString());
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            throw new AssertionError("UTF-8 is required on Android", exception);
        }
    }

    private static String decode(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value, "UTF-8");
    }
}
