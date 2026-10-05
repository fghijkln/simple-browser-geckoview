package com.cue.simplebrowser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Seven bundled wallpapers mapped to ISO local dates in a fixed seven-day cycle. */
final class WallpaperRotation {
    static final int CYCLE_DAYS = 7;

    static final class Wallpaper {
        final String id;
        final String title;
        /** Null identifies the original drawable resource; other values are bundled asset paths. */
        final String assetPath;

        Wallpaper(String id, String title, String assetPath) {
            this.id = id;
            this.title = title;
            this.assetPath = assetPath;
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
        WALLPAPERS = Collections.unmodifiableList(wallpapers);
        if (WALLPAPERS.size() != CYCLE_DAYS) {
            throw new ExceptionInInitializerError("The wallpaper cycle must contain exactly seven images");
        }
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

    /**
     * Maps an ISO-8601 local calendar date to a stable wallpaper. The epoch-day modulo seven
     * rule means adjacent dates always advance one slot and every seven-day span repeats.
     */
    static int indexForLocalDate(String isoLocalDate) {
        if (isoLocalDate == null) throw new IllegalArgumentException("Local date is required");
        long epochDay = LocalDate.parse(isoLocalDate).toEpochDay();
        return (int) Math.floorMod(epochDay, CYCLE_DAYS);
    }

    static Wallpaper forLocalDate(String isoLocalDate) {
        return WALLPAPERS.get(indexForLocalDate(isoLocalDate));
    }
}
