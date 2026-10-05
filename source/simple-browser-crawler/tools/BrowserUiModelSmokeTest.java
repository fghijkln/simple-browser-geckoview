package com.cue.simplebrowser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Focused checks for overflow menu actions and browser navigation state. */
public final class BrowserUiModelSmokeTest {
    private BrowserUiModelSmokeTest() { }

    public static void main(String[] args) {
        List<BrowserUiModel.Action> quick = BrowserUiModel.quickActions();
        check(quick.size() == 3, "quick action area should expose only tab switching and home operations");
        check(quick.get(0).id.equals(BrowserUiModel.NEW_TAB)
                        && quick.get(1).id.equals(BrowserUiModel.SWITCH_TABS)
                        && quick.get(2).id.equals(BrowserUiModel.HOME),
                "quick actions must retain tab/home operations without a wallpaper chooser shortcut");
        check(quick.stream().noneMatch(action -> "wallpaper".equals(action.id)
                        || "壁纸".equals(action.title)),
                "the overflow quick-action model must not register the crash-prone wallpaper action");

        List<BrowserUiModel.Section> sections = BrowserUiModel.menuSections(
                false, true, false, false, false, "Google");
        check(sections.size() == 5, "menu should keep navigation/display/search/history/privacy groups");
        Set<String> ids = new HashSet<>();
        for (BrowserUiModel.Section section : sections) {
            check(!section.title.trim().isEmpty(), "menu section needs a visible title");
            for (BrowserUiModel.Action action : section.actions) ids.add(action.id);
        }
        check(ids.contains(BrowserUiModel.BACK) && ids.contains(BrowserUiModel.FORWARD)
                        && ids.contains(BrowserUiModel.RELOAD) && ids.contains(BrowserUiModel.SITE_MODE)
                        && ids.contains(BrowserUiModel.SEARCH_ENGINE)
                        && ids.contains(BrowserUiModel.EXTENSION_ARCHIVE)
                        && ids.contains(BrowserUiModel.SETTINGS) && ids.contains(BrowserUiModel.HISTORY)
                        && ids.contains(BrowserUiModel.BOOKMARKS) && ids.contains(BrowserUiModel.SAVE_BOOKMARK),
                "menu should expose supported navigation, site mode, search, history, bookmarks, extension review, and settings actions");
        check(!sections.get(0).actions.get(0).enabled && sections.get(0).actions.get(1).enabled,
                "back/forward availability should follow the active browser history");
        check(sections.get(0).actions.get(2).id.equals(BrowserUiModel.RELOAD),
                "idle pages should expose refresh");
        check(BrowserUiModel.menuSections(true, false, true, true, true, "Bing")
                        .get(0).actions.get(2).id.equals(BrowserUiModel.STOP),
                "loading pages should expose stop instead of refresh");
        check(!sections.get(0).actions.get(2).enabled,
                "new-tab pages should not present an enabled refresh with no web page to reload");
        check(BrowserUiModel.menuSections(true, false, false, true, true, "Bing")
                        .get(1).actions.get(0).title.equals("切换到手机站"),
                "desktop mode action should offer a return to phone mode");
        check(BrowserUiModel.menuSections(true, false, false, true, false, "Bing")
                        .get(4).actions.get(0).title.equals("扩展 ZIP（仅查看）"),
                "legacy filter rules must not be advertised as active protection");
        for (BrowserUiModel.Section section : sections) {
            for (BrowserUiModel.Action action : section.actions) {
                check(!action.title.contains("无痕") && !action.title.contains("下载")
                                && !action.title.contains("商店") && !action.title.contains("默认浏览器"),
                        "menu must not advertise unsupported browser features");
            }
        }

        System.out.println("PASS: supported overflow menu groups/actions, navigation states, phone/desktop and read-only extension label, and no unsupported menu entries");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
