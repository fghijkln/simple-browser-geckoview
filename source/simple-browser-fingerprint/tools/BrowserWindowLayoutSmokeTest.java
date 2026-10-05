package com.cue.simplebrowser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression checks for the native Android window and new-tab layout. */
public final class BrowserWindowLayoutSmokeTest {
    private BrowserWindowLayoutSmokeTest() { }

    public static void main(String[] args) throws IOException {
        check(args.length == 3, "expected MainActivity, wallpaper asset, and string resource paths");
        String source = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String strings = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8);

        check(source.contains("WindowCompat.setDecorFitsSystemWindows(getWindow(), false)"),
                "activity must explicitly define its edge-to-edge window policy");
        check(source.contains("getWindow().setStatusBarColor(Color.TRANSPARENT)")
                        && source.contains("insetsController.setAppearanceLightStatusBars(false)"),
                "transparent photo-backed status bar must use readable light icons");
        check(source.contains("windowRoot.addView(wallpaperBackdrop")
                        && source.contains("wallpaperBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP)"),
                "the wallpaper must be a full-window background behind the status-bar region");
        check(source.contains("ViewCompat.setOnApplyWindowInsetsListener(root")
                        && source.contains("WindowInsetsCompat.Type.systemBars()")
                        && source.contains("WindowInsetsCompat.Type.displayCutout()"),
                "the controls container must derive safe edges from system bars and display cutouts");
        check(source.contains("view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)"),
                "interactive content must remain inset from system bars and cutouts");

        int headerStart = source.indexOf("private View buildHeader()");
        int headerEnd = source.indexOf("private void showOverflowMenu()", headerStart);
        check(headerStart >= 0 && headerEnd > headerStart, "expected a dedicated compact top header");
        String header = source.substring(headerStart, headerEnd);
        check(header.contains("header.setBackgroundColor(Color.TRANSPARENT)")
                        && !header.contains("R.string.app_name") && !header.contains("branding")
                        && header.contains("header.addView(spacer"),
                "top header must remain transparent and free of branding");
        check(header.contains("tabCountButton.setMinimumWidth(dp(48))")
                        && header.contains("tabCountButton.setMinimumHeight(dp(48))")
                        && header.contains("overflowButton.setMinimumWidth(dp(48))")
                        && header.contains("overflowButton.setMinimumHeight(dp(48))"),
                "top controls need comfortable 48dp touch areas");
        check(header.contains("tabCountButton.setBackground(rounded(Color.TRANSPARENT")
                        && strings.contains("<string name=\"tabs_count_button\">%1$d</string>")
                        && !strings.contains("<string name=\"tabs_count_button\">▢"),
                "tab count must be a centered numeral in a transparent rounded outline, not a checkbox glyph");
        check(header.contains("overflowButton.setOnClickListener(view -> showOverflowMenu())"),
                "top-right overflow menu must remain reachable");

        int menuStart = source.indexOf("private void showOverflowMenu()");
        int menuEnd = source.indexOf("private View darkMenuRow", menuStart);
        check(menuStart >= 0 && menuEnd > menuStart, "expected a dedicated overflow menu builder");
        String menu = source.substring(menuStart, menuEnd);
        check(menu.contains("ViewGroup.LayoutParams.WRAP_CONTENT") && menu.contains("Gravity.END")
                        && menu.contains("Math.min(dp(352)") && menu.contains("safeLeftInset")
                        && menu.contains("scroll.setFillViewport(false)"),
                "overflow should be content-height, right-aligned, safe-width bounded, and compact");
        check(menu.contains("headingParams.topMargin = dp(9)")
                        && source.contains("new LinearLayout.LayoutParams(dp(30), dp(40))")
                        && source.contains("new LinearLayout.LayoutParams(dp(20), dp(40))"),
                "overflow rows and section spacing should use compact dimensions");

        check(source.contains("wallpaperBackdrop.setVisibility(showHome ? View.VISIBLE : View.GONE)")
                        && source.contains("wallpaperScrim.setVisibility(showHome ? View.VISIBLE : View.GONE)"),
                "photo wallpaper should appear on home and stay behind web pages only when appropriate");
        check(source.contains("searchEnginePicker.setOnClickListener(view -> showSearchEnginePicker())")
                        && source.contains("searchEnginePicker.addView(selectedEngineIcon")
                        && source.contains("searchEnginePicker.setContentDescription(")
                        && !source.contains("engineChevron") && !source.contains("label(\"⌄\""),
                "engine logo remains a labeled tappable pill without a text chevron");
        check(source.contains("navigateFromAddress(homeAddressInput)"),
                "the new-tab address/search field must retain its navigation flow");

        int homeStart = source.indexOf("private View buildHomeView()");
        int homeEnd = source.indexOf("private View buildErrorPanel()", homeStart);
        check(homeStart >= 0 && homeEnd > homeStart, "expected a dedicated new-tab page builder");
        String home = source.substring(homeStart, homeEnd);
        check(home.contains("homeRoot.setBackgroundColor(Color.TRANSPARENT)")
                        && !home.contains("brand") && !home.contains("websiteShortcuts")
                        && !home.contains("siteShortcut("),
                "new tab must reveal the full-window wallpaper without branding or shortcut tiles");

        check(source.contains("Intent.ACTION_OPEN_DOCUMENT") && source.contains("image/*")
                        && source.contains("Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION")
                        && source.contains("takePersistableUriPermission(uri")
                        && source.contains("WALLPAPER_URI_PREFERENCE")
                        && source.contains("loadThumbnail(uri, new Size(1600, 2400), null)"),
                "photo picker must persist safe URI grants and store wallpaper selection across restarts");
        check(source.contains("saveWallpaperThumbnail(thumbnail)")
                        && source.contains("restoreBundledWallpaper(false)")
                        && source.contains("R.drawable.new_tab_wallpaper"),
                "non-persistable documents and missing files must fall back cleanly to the bundled wallpaper");
        int galleryStart = source.indexOf("private void showWallpaperPicker()");
        int galleryEnd = source.indexOf("private View buildWallpaperCard(", galleryStart);
        check(galleryStart >= 0 && galleryEnd > galleryStart, "expected an in-app bundled wallpaper gallery");
        String gallery = source.substring(galleryStart, galleryEnd);
        check(gallery.contains("wallpaperBackdrop.getDrawable()")
                        && gallery.contains("new SwitchCompat(this)")
                        && gallery.contains("picker.wallpapers()")
                        && gallery.contains("buildWallpaperCard(first)")
                        && gallery.contains("loadVisibleWallpaperThumbnails(galleryScroll)"),
                "wallpaper picker must show the selected preview, daily toggle and lazily loaded offline wallpaper cards");
        check(source.contains("WALLPAPER_THUMBNAIL_SAMPLE_SIZE = 8")
                        && source.contains("result.usedFallback")
                        && strings.contains("wallpaper_asset_unavailable"),
                "thumbnail memory use must be bounded and failed bundled selections must fall back visibly");
        check(!gallery.contains("openWallpaperGallery")
                        && !gallery.contains("google.com/search?tbm=isch")
                        && !gallery.contains("bing.com/images/search")
                        && !source.contains("com.google.android.apps.wallpapers")
                        && !source.contains("com.microsoft.bing.wallpapers"),
                "wallpaper app and external Google/Microsoft gallery links must be absent");
        check(strings.contains("每日自动轮换") && strings.contains("14 张原创壁纸随应用离线提供")
                        && strings.contains("从设备选择照片") && strings.contains("每日自动轮换已关闭")
                        && !strings.contains("Google Wallpapers")
                        && !strings.contains("Microsoft Bing Wallpapers"),
                "wallpaper picker must explain offline assets, local photos, manual selection and rotation");
        check(menu.contains("List<BrowserUiModel.Action> quickActions = BrowserUiModel.quickActions()")
                        && menu.contains("quick.setOnClickListener(view -> dispatchOverflowAction(action.id))")
                        && !menu.contains("壁纸")
                        && source.contains("OverflowActionDispatcher.dispatch(actionId")
                        && !source.contains("BrowserUiModel.WALLPAPER.equals(actionId)")
                        && !source.contains("postWallpaperPickerAfterMenuDismiss(action)")
                        && !source.contains("showWallpaperPicker();"),
                "the top-row cards must come from a model without 壁纸, and overflow dispatch must not route to its chooser");
        check(source.contains("BrowserUiModel.BACK.equals(actionId)")
                        && source.contains("BrowserUiModel.FORWARD.equals(actionId)"),
                "browser back and forward must remain available through menu actions");
        check(!source.contains("private View buildNavigationBar(") && !source.contains("navigationButton("),
                "the fixed five-control bottom row must be absent");

        Path wallpaper = Path.of(args[1]);
        check(Files.isRegularFile(wallpaper) && Files.size(wallpaper) > 16_384,
                "the original wallpaper resource must remain present and nonempty");
        System.out.println("PASS: edge-to-edge wallpaper/status bar with inset-safe controls, compact menu, 48dp tab counter, chevron-free engine picker, persistent photo picker/fallback, selected-state offline gallery, daily rotation toggle, no external wallpaper links, and sparse home regressions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
