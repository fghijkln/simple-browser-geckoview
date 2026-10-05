package com.cue.simplebrowser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Offline wallpaper catalog and deterministic, local-date-based rotation state. */
final class WallpaperRotation {
    static final String DEFAULT_ID = "courtyard";

    static final class Wallpaper {
        final String id;
        final String title;
        /** Null identifies the retained drawable resource; other values are bundled asset paths. */
        final String assetPath;

        Wallpaper(String id, String title, String assetPath) {
            this.id = id;
            this.title = title;
            this.assetPath = assetPath;
        }
    }

    static final class SelectionState {
        final String selectedId;
        final boolean automatic;
        final String automaticDate;
        final String lastAutomaticId;

        SelectionState(String selectedId, boolean automatic, String automaticDate, String lastAutomaticId) {
            this.selectedId = normalizeId(selectedId);
            this.automatic = automatic;
            this.automaticDate = normalizeDate(automaticDate);
            this.lastAutomaticId = normalizeId(lastAutomaticId);
        }

        SelectionState onLocalDate(String localDate) {
            if (!automatic || localDate == null || localDate.equals(automaticDate)) return this;
            String previous = lastAutomaticId != null ? lastAutomaticId : selectedId;
            String next = selectForDate(localDate, previous);
            return new SelectionState(next, true, localDate, next);
        }

        SelectionState setAutomatic(boolean enabled, String localDate) {
            if (!enabled) return new SelectionState(selectedId, false, null, lastAutomaticId);
            String previous = selectedId != null ? selectedId : lastAutomaticId;
            String next = selectForDate(localDate, previous);
            return new SelectionState(next, true, localDate, next);
        }

        SelectionState selectManually(String id) {
            if (find(id) == null) throw new IllegalArgumentException("Unknown wallpaper id: " + id);
            return new SelectionState(id, false, null, lastAutomaticId);
        }
    }

    private static final List<Wallpaper> WALLPAPERS;

    static {
        List<Wallpaper> wallpapers = new ArrayList<>();
        wallpapers.add(new Wallpaper("courtyard", "暮光庭院", null));
        wallpapers.add(new Wallpaper("alpine-lake", "晨雾湖山", "wallpapers/alpine-lake.jpg"));
        wallpapers.add(new Wallpaper("sandstone-canyon", "赤岩峡谷", "wallpapers/sandstone-canyon.jpg"));
        wallpapers.add(new Wallpaper("coastal-cliffs", "海蚀岬角", "wallpapers/coastal-cliffs.jpg"));
        wallpapers.add(new Wallpaper("rainforest-falls", "雨林瀑布", "wallpapers/rainforest-falls.jpg"));
        wallpapers.add(new Wallpaper("snow-pines", "雪松林道", "wallpapers/snow-pines.jpg"));
        wallpapers.add(new Wallpaper("moss-garden", "苔庭石桥", "wallpapers/moss-garden.jpg"));
        wallpapers.add(new Wallpaper("aurora-ridge", "极光山谷", "wallpapers/aurora-ridge.jpg"));
        wallpapers.add(new Wallpaper("meadow-dawn", "高山花甸", "wallpapers/meadow-dawn.jpg"));
        wallpapers.add(new Wallpaper("modern-courtyard", "石庭光影", "wallpapers/modern-courtyard.jpg"));
        wallpapers.add(new Wallpaper("coastal-pavilion", "海岸静亭", "wallpapers/coastal-pavilion.jpg"));
        wallpapers.add(new Wallpaper("silk-flow", "青铜流线", "wallpapers/silk-flow.jpg"));
        wallpapers.add(new Wallpaper("mineral-marble", "矿岩纹理", "wallpapers/mineral-marble.jpg"));
        wallpapers.add(new Wallpaper("soft-geometry", "柔光拱形", "wallpapers/soft-geometry.jpg"));
        WALLPAPERS = Collections.unmodifiableList(wallpapers);
    }

    private WallpaperRotation() { }

    static List<Wallpaper> all() {
        return WALLPAPERS;
    }

    static Wallpaper find(String id) {
        if (id == null) return null;
        for (Wallpaper wallpaper : WALLPAPERS) {
            if (wallpaper.id.equals(id)) return wallpaper;
        }
        return null;
    }

    static String normalizeId(String id) {
        return find(id) == null ? null : id;
    }

    private static String normalizeDate(String date) {
        if (date == null) return null;
        try {
            LocalDate parsed = LocalDate.parse(date);
            return parsed.toString().equals(date) ? date : null;
        } catch (java.time.DateTimeException error) {
            return null;
        }
    }

    /** Selects a stable wallpaper for an ISO local date, skipping the previous image if needed. */
    static String selectForDate(String isoLocalDate, String previousId) {
        if (isoLocalDate == null) throw new IllegalArgumentException("Local date is required");
        long epochDay = LocalDate.parse(isoLocalDate).toEpochDay();
        int index = (int) Math.floorMod(epochDay, WALLPAPERS.size());
        if (WALLPAPERS.size() > 1 && WALLPAPERS.get(index).id.equals(previousId)) {
            index = (index + 1) % WALLPAPERS.size();
        }
        return WALLPAPERS.get(index).id;
    }
}
