package com.cue.simplebrowser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Focused checks for the offline wallpaper library and daily-selection state machine. */
public final class WallpaperRotationSmokeTest {
    private WallpaperRotationSmokeTest() { }

    public static void main(String[] args) throws IOException {
        check(args.length == 4, "expected MainActivity, strings, bundled wallpaper assets, and retained courtyard image");
        Path mainActivity = Path.of(args[0]);
        Path stringsPath = Path.of(args[1]);
        Path assets = Path.of(args[2]);
        Path courtyard = Path.of(args[3]);
        String source = Files.readString(mainActivity, StandardCharsets.UTF_8);
        String strings = Files.readString(stringsPath, StandardCharsets.UTF_8);

        check(WallpaperRotation.all().size() == 14, "exactly fourteen wallpapers must be catalogued");
        check(Files.isRegularFile(courtyard) && Files.size(courtyard) > 16_384,
                "the retained original courtyard wallpaper must remain bundled");
        Set<String> ids = new HashSet<>();
        int assetCount = 0;
        for (WallpaperRotation.Wallpaper wallpaper : WallpaperRotation.all()) {
            check(ids.add(wallpaper.id), "wallpaper ids must be unique");
            if (wallpaper.assetPath != null) {
                Path file = assets.resolve(wallpaper.assetPath);
                check(Files.isRegularFile(file) && Files.size(file) > 16_384,
                        "bundled wallpaper asset missing or implausibly small: " + wallpaper.assetPath);
                assetCount++;
            }
        }
        check(assetCount == 13, "thirteen generated wallpapers plus the retained courtyard image are required");

        LocalDate start = LocalDate.of(2026, 10, 3);
        WallpaperRotation.SelectionState state = new WallpaperRotation.SelectionState(
                WallpaperRotation.DEFAULT_ID, false, null, null)
                .setAutomatic(true, start.toString());
        check(state.automatic && state.automaticDate.equals(start.toString()),
                "enabling automatic rotation must persist enabled state and local date");
        String stableId = state.selectedId;
        WallpaperRotation.SelectionState sameDay = state.onLocalDate(start.toString());
        check(sameDay == state && sameDay.selectedId.equals(stableId),
                "same local date must not change the selected wallpaper across checks or restarts");
        check(WallpaperRotation.selectForDate(start.toString(), WallpaperRotation.DEFAULT_ID)
                        .equals(WallpaperRotation.selectForDate(start.toString(), WallpaperRotation.DEFAULT_ID)),
                "date selection must be deterministic for the same local date and prior image");
        WallpaperRotation.SelectionState malformed = new WallpaperRotation.SelectionState(
                "missing-wallpaper", true, "not-an-ISO-date", "also-missing")
                .onLocalDate(start.toString());
        check(malformed.selectedId != null && malformed.automatic
                        && start.toString().equals(malformed.automaticDate)
                        && malformed.lastAutomaticId.equals(malformed.selectedId),
                "malformed persisted IDs/date must be normalized and safely restarted on the current local date");

        for (int day = 1; day < 45; day++) {
            LocalDate nextDate = start.plusDays(day);
            WallpaperRotation.SelectionState next = state.onLocalDate(nextDate.toString());
            check(next.selectedId != null && !next.selectedId.equals(state.selectedId),
                    "daily rotation must not repeat the immediately previous image on " + nextDate);
            check(next.automaticDate.equals(nextDate.toString()), "local date must advance with rotation");
            state = next;
        }

        WallpaperRotation.SelectionState manual = state.selectManually("coastal-cliffs");
        check(!manual.automatic && manual.automaticDate == null
                        && "coastal-cliffs".equals(manual.selectedId),
                "manual wallpaper selection must persist the image and turn off daily rotation");
        WallpaperRotation.SelectionState reenabled = manual.setAutomatic(true, start.plusDays(46).toString());
        check(reenabled.automatic && !"coastal-cliffs".equals(reenabled.selectedId),
                "re-enabling daily rotation should move away from the manually selected previous image");

        check(source.contains("new-tab-wallpaper-daily-auto")
                        && source.contains("new-tab-wallpaper-auto-date")
                        && source.contains("new-tab-wallpaper-last-auto-id")
                        && source.contains("catch (ClassCastException malformedPreference)")
                        && source.contains("preferences.edit().remove(key).apply()")
                        && source.contains("persistWallpaperRotationState")
                        && source.contains("editor.commit()"),
                "automatic toggle, date and repeat-prevention state must be persisted locally");
        check(source.contains(".setAutomatic(checked, LocalDate.now().toString())")
                        && source.contains("WallpaperPickerFlow.open(readWallpaperRotationState())")
                        && source.contains(".select(wallpaperId, id -> loadBundledWallpaper(id, 1))")
                        && source.contains("replaceWallpaperSelection")
                        && source.contains(".putBoolean(WALLPAPER_AUTO_PREFERENCE, false)"),
                "both bundled and local-photo manual selection must disable automatic rotation");
        check(source.contains("refreshDailyWallpaper();")
                        && source.contains("wallpaperRotationHandler.postDelayed(this, 60_000L)")
                        && source.contains("wallpaperRotationHandler.removeCallbacks(wallpaperRotationCheck)"),
                "foreground date changes and app resume must refresh without a background service");

        int pickerStart = source.indexOf("private void showWallpaperPicker()");
        int pickerEnd = source.indexOf("private View buildWallpaperCard(", pickerStart);
        check(pickerStart >= 0 && pickerEnd > pickerStart, "expected the offline wallpaper chooser");
        String picker = source.substring(pickerStart, pickerEnd);
        check(picker.contains("wallpaperBackdrop.getDrawable()")
                        && picker.contains("new SwitchCompat(this)")
                        && picker.contains("picker.wallpapers()")
                        && picker.contains("buildWallpaperCard(first)")
                        && picker.contains("loadVisibleWallpaperThumbnails(galleryScroll)"),
                "chooser must show selected-state preview, persistent toggle and bundled gallery");
        int cardStart = source.indexOf("private View buildWallpaperCard(");
        int loaderStart = source.indexOf("private void loadVisibleWallpaperThumbnails(", cardStart);
        check(cardStart >= 0 && loaderStart > cardStart,
                "expected a separate lazy wallpaper thumbnail loader");
        String cardBuilder = source.substring(cardStart, loaderStart);
        check(cardBuilder.contains("thumbnail.setImageResource(R.drawable.ic_browser)")
                        && !cardBuilder.contains("loadBundledWallpaper("),
                "opening the chooser must not synchronously decode every bundled wallpaper thumbnail");
        check(!picker.contains("openWallpaperGallery")
                        && !picker.contains("google.com/search?tbm=isch")
                        && !picker.contains("bing.com/images/search")
                        && !picker.contains("Intent.ACTION_VIEW"),
                "wallpaper chooser must not contain Google/Microsoft image-search or app-link routes");
        check(!source.contains("com.google.android.apps.wallpapers")
                        && !source.contains("com.microsoft.bing.wallpapers")
                        && !strings.contains("Google Wallpapers")
                        && !strings.contains("Microsoft Bing Wallpapers")
                        && !strings.contains("wallpaper_google_gallery")
                        && !strings.contains("wallpaper_bing_gallery"),
                "Google and Microsoft wallpaper-app link code and copy must be absent");
        check(strings.contains("每日自动轮换") && strings.contains("离线提供")
                        && strings.contains("每日自动轮换已关闭"),
                "wallpaper copy must explain offline availability and manual-selection behavior");

        System.out.println("PASS: 14 bundled wallpapers, date stability, deterministic daily rotation, no immediate repeat, manual-selection/toggle state, local persistence, offline gallery preview, and no Google/Microsoft wallpaper app-link code");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
