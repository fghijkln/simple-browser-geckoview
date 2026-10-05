package com.cue.simplebrowser;

import java.util.ArrayList;
import java.util.List;

/** Regression check that the overflow menu no longer registers a wallpaper chooser action. */
public final class OverflowActionDispatcherSmokeTest {
    private OverflowActionDispatcherSmokeTest() { }

    public static void main(String[] args) {
        check(BrowserUiModel.quickActions().stream().noneMatch(action -> "wallpaper".equals(action.id)
                        || "壁纸".equals(action.title)),
                "the overflow top row must not expose a clickable 壁纸 quick-action card");
        check(BrowserUiModel.menuSections(false, false, false, false, false, "Google").stream()
                        .flatMap(section -> section.actions.stream())
                        .noneMatch(action -> "wallpaper".equals(action.id) || "壁纸".equals(action.title)),
                "the overflow menu sections must not register a wallpaper chooser action");

        FakeHost removedAction = new FakeHost();
        OverflowActionDispatcher.dispatch("wallpaper", removedAction);
        check(removedAction.events.equals(List.of("dismiss", "other:wallpaper")),
                "a stale wallpaper ID must not enter a special chooser route");

        FakeHost navigation = new FakeHost();
        OverflowActionDispatcher.dispatch(BrowserUiModel.HOME, navigation);
        check(navigation.events.equals(List.of("dismiss", "other:" + BrowserUiModel.HOME)),
                "supported actions must preserve their existing route after menu dismissal");
        System.out.println("PASS: overflow quick-action cards and menu sections do not register 壁纸, and the dispatcher has no chooser route for a stale wallpaper action ID");
    }

    private static final class FakeHost implements OverflowActionDispatcher.Host {
        final List<String> events = new ArrayList<>();

        @Override
        public void dismissOverflowMenu() {
            events.add("dismiss");
        }

        @Override
        public void dispatchOtherAction(String actionId) {
            events.add("other:" + actionId);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
