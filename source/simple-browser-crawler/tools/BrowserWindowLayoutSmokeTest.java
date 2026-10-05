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

        check(!source.contains("showWallpaperPicker")
                        && !source.contains("openWallpaperDocumentPicker")
                        && !source.contains("REQUEST_SELECT_WALLPAPER")
                        && !source.contains("WallpaperPickerFlow"),
                "wallpaper selection code must not be exposed from the main UI");
        check(strings.contains("壁纸每日强制轮换")
                        && strings.contains("没有手动切换入口")
                        && !strings.contains("从设备选择照片")
                        && !strings.contains("每日自动轮换已关闭"),
                "the wallpaper disclosure must be informational and must not describe a selection control");
        check(source.contains("wallpaperDisclosure.setClickable(false)")
                        && source.contains("wallpaperDisclosure.setFocusable(false)")
                        && source.contains("LocalDate.now().toString()")
                        && source.contains("WALLPAPER_REFRESH_INTERVAL_MILLIS"),
                "daily wallpaper note must be non-interactive and the refresh must use the device-local date");
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
        System.out.println("PASS: edge-to-edge wallpaper/status bar with inset-safe controls, compact menu, 48dp tab counter, chevron-free engine picker, non-interactive forced-rotation disclosure, no wallpaper selection UI, and sparse home regressions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
