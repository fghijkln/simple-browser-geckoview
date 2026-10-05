package com.cue.simplebrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Tracks independent tab navigation/UI state and the selected tab. */
final class BrowserTabRegistry {
    static final class Tab {
        final String id;
        String url = "";
        String lastRequestedUrl;
        String title = "";
        String errorMessage;
        boolean showingHome = true;
        boolean loading;
        boolean canGoBack;
        boolean canGoForward;
        boolean failed;
        int progress;
        boolean initialDesktopUaReloadRequired;

        private Tab(String id) { this.id = id; }
    }

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private String selectedId;

    Tab create() { return create(UUID.randomUUID().toString()); }

    Tab create(String id) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Tab ID is required");
        if (find(id) != null) throw new IllegalArgumentException("Duplicate tab ID: " + id);
        Tab tab = new Tab(id);
        tabs.add(tab);
        selectedId = id;
        return tab;
    }

    boolean select(String id) {
        if (find(id) == null) return false;
        selectedId = id;
        return true;
    }

    /** Removes a tab and selects the tab at the same position, or the previous last tab. */
    boolean close(String id) {
        int index = indexOf(id);
        if (index < 0) return false;
        boolean wasSelected = id.equals(selectedId);
        tabs.remove(index);
        if (wasSelected) {
            selectedId = tabs.isEmpty() ? null : tabs.get(Math.min(index, tabs.size() - 1)).id;
        } else if (selectedId != null && find(selectedId) == null) {
            selectedId = tabs.isEmpty() ? null : tabs.get(0).id;
        }
        return true;
    }

    Tab selected() { return find(selectedId); }

    Tab find(String id) {
        if (id == null) return null;
        for (Tab tab : tabs) if (tab.id.equals(id)) return tab;
        return null;
    }

    List<Tab> all() { return Collections.unmodifiableList(new ArrayList<>(tabs)); }

    int size() { return tabs.size(); }

    private int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id.equals(id)) return i;
        return -1;
    }
}
