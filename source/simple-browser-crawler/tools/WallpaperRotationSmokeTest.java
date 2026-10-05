package com.cue.simplebrowser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/** Static/source-level regression checks for the forced seven-wallpaper daily cycle. */
public final class WallpaperRotationSmokeTest {
    private WallpaperRotationSmokeTest() { }

    public static void main(String[] args) throws IOException {
        check(args.length == 4, "expected MainActivity, strings, bundled asset directory, and retained original drawable");
        Path mainActivity = Path.of(args[0]);
        Path stringsPath = Path.of(args[1]);
        Path assets = Path.of(args[2]);
        Path courtyard = Path.of(args[3]);
        String source = Files.readString(mainActivity, StandardCharsets.UTF_8);
        String strings = Files.readString(stringsPath, StandardCharsets.UTF_8);

        check(WallpaperRotation.all().size() == WallpaperRotation.CYCLE_DAYS && WallpaperRotation.CYCLE_DAYS == 7,
                "the bundled catalog must contain exactly seven wallpapers");
        Set<String> ids = new HashSet<>();
        int assetCount = 0;
        Set<String> imageDigests = new HashSet<>();
        for (WallpaperRotation.Wallpaper wallpaper : WallpaperRotation.all()) {
            check(ids.add(wallpaper.id), "wallpaper IDs must be unique");
            Path file = wallpaper.assetPath == null ? courtyard : assets.resolve(wallpaper.assetPath);
            check(Files.isRegularFile(file) && Files.size(file) > 16_384,
                    "bundled wallpaper is missing or implausibly small: " + file);
            BufferedImage image = ImageIO.read(file.toFile());
            check(image != null && image.getWidth() == 1440 && image.getHeight() == 2560,
                    "wallpaper must decode at its expected portrait dimensions: " + file);
            image.flush();
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
                check(imageDigests.add(java.util.HexFormat.of().formatHex(digest)),
                        "bundled wallpaper images must not be exact duplicates: " + file);
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
            if (wallpaper.assetPath != null) assetCount++;
        }
        check(assetCount == 6, "the library must use one retained drawable plus six bundled JPEG assets");
        try (var files = Files.list(assets.resolve("wallpapers"))) {
            long images = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(".*\\.(?i:jpg|jpeg|png|webp)$"))
                    .count();
            check(images == 6, "only the six catalogued JPEG wallpaper files may remain in assets/wallpapers");
        }

        LocalDate start = LocalDate.of(2026, 10, 3);
        int startIndex = WallpaperRotation.indexForLocalDate(start.toString());
        Set<String> sevenDayCycle = new HashSet<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = start.plusDays(offset);
            int index = WallpaperRotation.indexForLocalDate(date.toString());
            check(index == (startIndex + offset) % 7,
                    "adjacent local dates must advance exactly one slot on " + date);
            check(sevenDayCycle.add(WallpaperRotation.forLocalDate(date.toString()).id),
                    "a seven-day cycle must visit every wallpaper once");
            check(index == WallpaperRotation.indexForLocalDate(date.toString()),
                    "the same local date must always map to the same wallpaper");
        }
        check(WallpaperRotation.indexForLocalDate(start.plusDays(7).toString()) == startIndex,
                "the fixed local-date mapping must repeat exactly after seven days");
        check(WallpaperRotation.indexForLocalDate(start.minusDays(1).toString())
                        != WallpaperRotation.indexForLocalDate(start.toString()),
                "moving the local date backwards across midnight must recompute the selected wallpaper");

        Instant sameInstant = Instant.parse("2026-01-01T00:30:00Z");
        LocalDate shanghaiDate = LocalDate.ofInstant(sameInstant, ZoneId.of("Asia/Shanghai"));
        LocalDate losAngelesDate = LocalDate.ofInstant(sameInstant, ZoneId.of("America/Los_Angeles"));
        check(!shanghaiDate.equals(losAngelesDate), "timezone test instant must straddle local midnight");
        check(!WallpaperRotation.forLocalDate(shanghaiDate.toString()).id
                        .equals(WallpaperRotation.forLocalDate(losAngelesDate.toString()).id),
                "a timezone change that changes the local calendar date must select that date's wallpaper");

        int createStart = source.indexOf("protected void onCreate(");
        int resumeStart = source.indexOf("protected void onResume()");
        check(createStart >= 0 && resumeStart > createStart
                        && source.substring(createStart, resumeStart).contains("restoreSelectedWallpaper();"),
                "the current-date wallpaper must be applied during Activity creation");
        int pauseStart = source.indexOf("protected void onPause()", resumeStart);
        int backStart = source.indexOf("public void onBackPressed()", pauseStart);
        check(resumeStart >= 0 && pauseStart > resumeStart && backStart > pauseStart,
                "expected explicit Activity resume/pause lifecycle methods");
        String resume = source.substring(resumeStart, pauseStart);
        String pause = source.substring(pauseStart, backStart);
        check(resume.contains("refreshDailyWallpaper();")
                        && resume.contains("wallpaperRotationChecksActive = true;")
                        && resume.contains("wallpaperRotationHandler.postDelayed(wallpaperRotationCheck, WALLPAPER_REFRESH_INTERVAL_MILLIS)")
                        && source.contains("wallpaperRotationHandler.postDelayed(this, WALLPAPER_REFRESH_INTERVAL_MILLIS)")
                        && source.contains("LocalDate.now().toString()"),
                "app resume and foreground 60-second checks must recalculate from the current system-local date");
        check(pause.contains("wallpaperRotationChecksActive = false;")
                        && pause.contains("wallpaperRotationHandler.removeCallbacks(wallpaperRotationCheck)"),
                "the periodic refresh must stop when the Activity is paused");
        check(!source.contains("AlarmManager") && !source.contains("SCHEDULE_EXACT_ALARM"),
                "rotation must not rely on exact alarms or request an alarm permission");
        check(!source.contains("showWallpaperPicker")
                        && !source.contains("openWallpaperDocumentPicker")
                        && !source.contains("REQUEST_SELECT_WALLPAPER")
                        && !source.contains("WallpaperPickerFlow")
                        && !source.contains("selectManually"),
                "the main UI and app code must expose no wallpaper selection control or picker path");
        check(strings.contains("壁纸每日强制轮换")
                        && strings.contains("没有手动切换入口"),
                "settings disclosure must clearly describe forced daily rotation without an interactive control");
        System.out.println("PASS: seven decodable 1440x2560 wallpapers; adjacent-date advancement; fixed same-day mapping; exact seven-day cycle; timezone/date recalculation; foreground midnight refresh; no wallpaper picker or switch UI");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
