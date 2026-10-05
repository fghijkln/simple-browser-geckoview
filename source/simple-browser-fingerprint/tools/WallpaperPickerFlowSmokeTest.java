package com.cue.simplebrowser;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Regression checks for opening and selecting from the in-app wallpaper chooser. */
public final class WallpaperPickerFlowSmokeTest {
    private WallpaperPickerFlowSmokeTest() { }

    public static void main(String[] args) {
        WallpaperRotation.SelectionState initial = new WallpaperRotation.SelectionState(
                "coastal-cliffs", true, "2026-10-03", "coastal-cliffs");
        WallpaperPickerFlow.Session picker = WallpaperPickerFlow.open(initial);
        check(picker.selection == initial, "opening the chooser must retain the current selection");
        check(picker.wallpapers().size() == 14, "opening the chooser must expose all 14 offline wallpapers");
        Set<String> ids = new HashSet<>();
        for (WallpaperRotation.Wallpaper wallpaper : picker.wallpapers()) {
            check(ids.add(wallpaper.id), "chooser entries must have unique IDs");
        }

        WallpaperPickerFlow.SelectionResult<String> selected = picker.select(
                "aurora-ridge", id -> "preview:" + id);
        check(selected.successful && !selected.usedFallback,
                "choosing an available gallery card must succeed without fallback");
        check("aurora-ridge".equals(selected.selection.selectedId)
                        && !selected.selection.automatic && selected.selection.automaticDate == null,
                "manual selection from the chooser must turn off daily rotation");
        check("preview:aurora-ridge".equals(selected.preview),
                "the chosen wallpaper preview must be returned for display");

        WallpaperPickerFlow.SelectionResult<String> recovered = picker.select("aurora-ridge",
                id -> {
                    if ("aurora-ridge".equals(id)) throw new IOException("damaged image");
                    return "preview:" + id;
                });
        check(recovered.successful && recovered.usedFallback,
                "a failed gallery preview must recover through the bundled default");
        check(WallpaperRotation.DEFAULT_ID.equals(recovered.selection.selectedId)
                        && "preview:courtyard".equals(recovered.preview),
                "fallback must select and display the built-in courtyard image");
        check(!recovered.selection.automatic,
                "fallback to the built-in image must not silently re-enable daily rotation");

        WallpaperPickerFlow.SelectionResult<String> unavailable = picker.select("aurora-ridge",
                id -> { throw new IOException("all wallpaper decodes failed"); });
        check(!unavailable.successful && !unavailable.usedFallback
                        && "coastal-cliffs".equals(unavailable.selection.selectedId)
                        && unavailable.preview == null,
                "if both requested and default images fail, keep the prior selection and preview untouched");

        WallpaperPickerFlow.SelectionResult<String> invalid = picker.select("not-in-the-library",
                id -> "unexpected");
        check(!invalid.successful && "coastal-cliffs".equals(invalid.selection.selectedId),
                "the chooser must reject unknown IDs without invoking selection");
        System.out.println("PASS: chooser opens with 14 stable entries, selects a gallery wallpaper, disables rotation, falls back on decode errors, and preserves the previous selection if fallback also fails");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
