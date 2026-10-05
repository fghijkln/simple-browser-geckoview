package com.cue.simplebrowser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Small, Android-independent model for supported browser overflow actions. */
final class BrowserUiModel {
    static final String NEW_TAB = "new_tab";
    static final String SWITCH_TABS = "switch_tabs";
    static final String HOME = "home";
    static final String BACK = "back";
    static final String FORWARD = "forward";
    static final String RELOAD = "reload";
    static final String STOP = "stop";
    static final String SITE_MODE = "site_mode";
    static final String SEARCH_ENGINE = "search_engine";
    static final String HISTORY = "history";
    static final String BOOKMARKS = "bookmarks";
    static final String SAVE_BOOKMARK = "save_bookmark";
    static final String EXTENSION_ARCHIVE = "extension_archive";
    static final String SETTINGS = "settings";

    static final class Action {
        final String id;
        final String title;
        final String glyph;
        final boolean enabled;

        Action(String id, String title, String glyph, boolean enabled) {
            this.id = id;
            this.title = title;
            this.glyph = glyph;
            this.enabled = enabled;
        }
    }

    static final class Section {
        final String title;
        final List<Action> actions;

        Section(String title, List<Action> actions) {
            this.title = title;
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }
    }

    private BrowserUiModel() { }

    static List<Action> quickActions() {
        return Collections.unmodifiableList(Arrays.asList(
                new Action(NEW_TAB, "新标签", "＋", true),
                new Action(SWITCH_TABS, "切换标签", "▢", true),
                new Action(HOME, "主页", "⌂", true)));
    }

    static List<Section> menuSections(boolean canGoBack, boolean canGoForward, boolean loading,
                                      boolean canReload, boolean desktopMode,
                                      String searchEngine) {
        ArrayList<Section> sections = new ArrayList<>();
        sections.add(new Section("导航", Arrays.asList(
                new Action(BACK, "后退", "‹", canGoBack),
                new Action(FORWARD, "前进", "›", canGoForward),
                new Action(loading ? STOP : RELOAD, loading ? "停止加载" : "刷新",
                        loading ? "×" : "↻", loading || canReload))));
        sections.add(new Section("网页显示", Collections.singletonList(
                new Action(SITE_MODE, desktopMode ? "切换到手机站" : "切换到电脑版",
                        desktopMode ? "▱" : "▰", true))));
        sections.add(new Section("搜索", Collections.singletonList(
                new Action(SEARCH_ENGINE, "搜索引擎 · " + safeName(searchEngine), "⌕", true))));
        sections.add(new Section("历史与书签", Arrays.asList(
                new Action(HISTORY, "浏览历史", "◷", true),
                new Action(BOOKMARKS, "书签", "☆", true),
                new Action(SAVE_BOOKMARK, "保存当前页为书签", "+", true))));
        sections.add(new Section("隐私与设置", Arrays.asList(
                new Action(EXTENSION_ARCHIVE, "扩展 ZIP（仅查看）", "▤", true),
                new Action(SETTINGS, "设置", "⚙", true))));
        return Collections.unmodifiableList(sections);
    }

    private static String safeName(String value) {
        return value == null || value.trim().isEmpty() ? "Google" : value.trim();
    }
}
