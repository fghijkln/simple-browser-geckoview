package com.cue.simplebrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;

import java.util.Collections;
import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.regex.Pattern;
import java.time.LocalDate;

/** A Chinese-language browser backed by Mozilla GeckoView. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(31, 41, 63);
    private static final int SECONDARY = Color.rgb(113, 123, 143);
    private static final int ACCENT = Color.rgb(72, 101, 218);
    private static final int BACKGROUND = Color.rgb(246, 247, 250);
    private static final int WHITE = Color.WHITE;
    private static final int BORDER = Color.rgb(226, 230, 238);
    private static final String STATE_TAB_IDS = "simple-browser.tabs.ids";
    private static final String STATE_SELECTED_TAB = "simple-browser.tabs.selected";
    private static final String STATE_TAB_STATE_PREFIX = "simple-browser.tabs.state.";
    private static final String STATE_TAB_URL_PREFIX = "simple-browser.tabs.url.";
    private static final String STATE_TAB_TITLE_PREFIX = "simple-browser.tabs.title.";
    private static final String STATE_TAB_HOME_PREFIX = "simple-browser.tabs.home.";
    private static final String SEARCH_PREFERENCES = "simple-browser.preferences";
    private static final String WALLPAPER_URI_PREFERENCE = "new-tab-wallpaper-uri";
    private static final String WALLPAPER_LOCAL_COPY_PREFERENCE = "new-tab-wallpaper-local-copy";
    private static final String WALLPAPER_LOCAL_COPY_FILE = "selected-new-tab-wallpaper.png";
    private static final String WALLPAPER_BUNDLED_ID_PREFERENCE = "new-tab-wallpaper-bundled-id";
    private static final String WALLPAPER_AUTO_PREFERENCE = "new-tab-wallpaper-daily-auto";
    private static final String WALLPAPER_AUTO_DATE_PREFERENCE = "new-tab-wallpaper-auto-date";
    private static final String WALLPAPER_LAST_AUTO_ID_PREFERENCE = "new-tab-wallpaper-last-auto-id";
    private static final String SEARCH_ENGINE_PREFERENCE = "search-engine";
    private static final String HISTORY_PREFERENCE = "browser-history-v1";
    private static final String BOOKMARKS_PREFERENCE = "browser-bookmarks-v1";
    private static final String SITE_MODE_PREFERENCE = SiteMode.PREFERENCE_KEY;
    private static final String CUSTOM_ENGINES_PREFERENCE = "custom-search-engines";
    private static final String PROFILE_PREFERENCES_PREFIX = "browser.profile.";
    private static final int REQUEST_IMPORT_EXTENSION_ZIP = 7401;
    private static final int REQUEST_SELECT_WALLPAPER = 7402;
    private static final int REQUEST_DNS_VPN_CONSENT = 7403;
    private static final int WALLPAPER_THUMBNAIL_SAMPLE_SIZE = 8;
    private static final String USER_EXTENSION_PREFIX = "extension.mv3.";
    private static final Pattern SCHEME_PREFIX = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern HOST_AND_PORT = Pattern.compile("^[^\\s/:]+:[0-9]{1,5}(/.*)?$");
    private static final long MAX_BROWSER_DOWNLOAD_BYTES = 256L * 1024L * 1024L;

    private EditText addressInput;
    private EditText homeAddressInput;
    private SearchEngine selectedSearchEngine;
    private CustomEngineStore customEngineStore;
    private LinearLayout searchEnginePicker;
    private TextView tabCountButton;
    private TextView overflowButton;
    private TextView profileBadge;
    private LinearLayout profileManagerCards;
    private View browserAddressBar;
    private PopupWindow overflowPopup;
    private Dialog searchEngineDialog;
    private Dialog tabSwitcherDialog;
    private Dialog settingsDialog;
    private Dialog pluginManagerDialog;
    private Dialog pluginEditorDialog;
    private Dialog extensionManagerDialog;
    private Dialog wallpaperDialog;
    private Dialog profileManagerDialog;
    private boolean activityResumed;
    private LinearLayout extensionManagerCards;
    private LinearLayout tabItems;
    private HorizontalScrollView tabScroll;
    private LinearLayout activeCategoryTabs;
    private LinearLayout activeFeatureTabs;
    private ListView activeEngineList;
    private SearchEnginePickerAdapter activeEngineAdapter;
    private LinearLayout pluginManagerCards;
    private EditText activePickerFilter;
    private SearchEngine.Category[] activePickerCategory;
    private String[] activePickerFeatureCategory;
    private TextView activePickerEmpty;
    private Runnable pendingSearchEngineFilter;
    private TextView siteModeButton;
    private SiteMode.Mode siteMode = SiteMode.Mode.PHONE;
    private ProgressBar progressBar;
    private FrameLayout contentFrame;
    private ImageView wallpaperBackdrop;
    private View wallpaperScrim;
    private final Handler searchEngineIconHandler = new Handler(Looper.getMainLooper());
    private final Handler searchEngineFilterHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService searchEngineIconExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "search-engine-icon-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "browser-download-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> unavailableEngineIcons = ConcurrentHashMap.newKeySet();
    private final Set<String> loadingEngineIcons = ConcurrentHashMap.newKeySet();
    private final LruCache<String, Bitmap> searchEngineIconCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount());
        }
    };
    private final Handler wallpaperRotationHandler = new Handler(Looper.getMainLooper());
    private boolean wallpaperRotationChecksActive;
    private final Runnable wallpaperRotationCheck = new Runnable() {
        @Override
        public void run() {
            if (!wallpaperRotationChecksActive) return;
            refreshDailyWallpaper();
            wallpaperRotationHandler.postDelayed(this, 60_000L);
        }
    };
    private int safeLeftInset;
    private int safeRightInset;
    private int safeTopInset;
    private View homeView;
    private View errorPanel;
    private TextView errorMessage;
    private BrowserTabRegistry tabRegistry;
    private BrowserDataStore browserDataStore;
    private BrowserProfileStore browserProfileStore;
    private BrowserProfileStore.Profile currentBrowserProfile;
    private android.content.SharedPreferences profilePreferences;
    private boolean profileMetadataRecovered;
    private final Map<String, BrowserTabSession> browserSessions = new HashMap<>();
    private LocalExtensionArchive.Store localExtensionArchiveStore;
    private boolean extensionArchiveLoaded;
    private final List<LocalExtensionArchive.StoredPackage> userExtensions = new ArrayList<>();

    private static final class BrowserTabSession {
        final BrowserTabRegistry.Tab tab;
        final GeckoViewBrowserAdapter browser;
        volatile String currentPageUrl;

        BrowserTabSession(BrowserTabRegistry.Tab tab, GeckoViewBrowserAdapter browser) {
            this.tab = tab;
            this.browser = browser;
            this.currentPageUrl = tab.url;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tabRegistry = new BrowserTabRegistry();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BACKGROUND);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(true);

        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        try {
            browserProfileStore = new BrowserProfileStore(getFilesDir());
            browserProfileStore.initialize("默认环境", Locale.getDefault().toLanguageTag(),
                    TimeZone.getDefault().getID());
            profileMetadataRecovered = browserProfileStore.wasMetadataRecovered();
            boolean preferenceCleanupSucceeded = true;
            for (String deletingProfileId : browserProfileStore.pendingDeleteIds()) {
                if (!clearProfileLocalPreferences(deletingProfileId)) preferenceCleanupSucceeded = false;
            }
            if (preferenceCleanupSucceeded) {
                try {
                    browserProfileStore.completePendingDeletions();
                } catch (IOException ignored) {
                    // Keep the tombstone; a later launch retries recursive profile deletion.
                }
            }
            currentBrowserProfile = browserProfileStore.currentProfile();
            profilePreferences = getSharedPreferences(PROFILE_PREFERENCES_PREFIX
                    + currentBrowserProfile.id, MODE_PRIVATE);
        } catch (IOException error) {
            throw new IllegalStateException("浏览环境私有存储无法初始化", error);
        }
        browserDataStore = new BrowserDataStore(new BrowserDataStore.Persistence() {
            @Override public String readHistory() { return profilePreferences.getString(HISTORY_PREFERENCE, ""); }
            @Override public String readBookmarks() { return profilePreferences.getString(BOOKMARKS_PREFERENCE, ""); }
            @Override public void writeHistory(String value) {
                profilePreferences.edit().putString(HISTORY_PREFERENCE, value).apply();
            }
            @Override public void writeBookmarks(String value) {
                profilePreferences.edit().putString(BOOKMARKS_PREFERENCE, value).apply();
            }
        });
        try {
            SearchEngine.installBuiltIns(SearchEngineCatalog.parse(
                    readAssetText("search-engines/catalog.v1.json")));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Bundled search-engine catalog could not be loaded", error);
        }
        customEngineStore = new CustomEngineStore(new CustomEngineStore.Persistence() {
            @Override
            public String read() {
                return preferences.getString(CUSTOM_ENGINES_PREFERENCE, "");
            }

            @Override
            public void write(String value) {
                preferences.edit().putString(CUSTOM_ENGINES_PREFERENCE, value).apply();
            }
        });
        customEngineStore.load();
        selectedSearchEngine = SearchEngine.byId(
                preferences.getString(SEARCH_ENGINE_PREFERENCE, SearchEngine.DEFAULT_ID), allSearchEngines());
        siteMode = SiteMode.Mode.fromStoredValue(profilePreferences.getString(
                SITE_MODE_PREFERENCE, SiteMode.Mode.PHONE.storedValue()));
        String profileDirectory;
        try {
            profileDirectory = browserProfileStore.profileDirectory(currentBrowserProfile.id).getCanonicalPath();
        } catch (IOException error) {
            throw new IllegalStateException("Gecko profile path is unavailable", error);
        }
        GeckoViewBrowserAdapter.initializeRuntime(this, profileDirectory, isWebRtcProtectionEnabled(), result -> {
            if (result.protectedModeEnabled && result.javascriptFallback) {
                Toast.makeText(this, R.string.webrtc_protection_fallback, Toast.LENGTH_LONG).show();
            }
        });

        FrameLayout windowRoot = new FrameLayout(this);
        windowRoot.setBackgroundColor(BACKGROUND);
        wallpaperBackdrop = new ImageView(this);
        wallpaperBackdrop.setImageResource(R.drawable.new_tab_wallpaper);
        wallpaperBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        windowRoot.addView(wallpaperBackdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallpaperScrim = new View(this);
        wallpaperScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99211712, 0x50301D16, 0x30211611}));
        windowRoot.addView(wallpaperScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setFocusableInTouchMode(true);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            safeLeftInset = safeInsets.left;
            safeRightInset = safeInsets.right;
            safeTopInset = safeInsets.top;
            view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom);
            return windowInsets;
        });
        windowRoot.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(windowRoot);
        ViewCompat.requestApplyInsets(root);
        restoreSelectedWallpaper();

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        browserAddressBar = buildAddressBar();
        root.addView(browserAddressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(BACKGROUND));
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        initializeBrowserViews();

        String[] savedTabIds = savedInstanceState == null
                ? null : savedInstanceState.getStringArray(STATE_TAB_IDS);
        if (savedTabIds != null) {
            for (String id : savedTabIds) {
                if (id != null && !id.trim().isEmpty()) createBrowserTab(id, savedInstanceState);
            }
        }
        if (tabRegistry.size() == 0) createBrowserTab(null, null);
        if (savedInstanceState != null) {
            tabRegistry.select(savedInstanceState.getString(STATE_SELECTED_TAB));
        }
        renderActiveTab();
        refreshProfileBadge();
        if (profileMetadataRecovered) {
            Toast.makeText(this, "环境索引已恢复；旧版浏览数据未迁移或删除", Toast.LENGTH_LONG).show();
        }
    }

    private void initializeLocalExtensionArchive() {
        if (extensionArchiveLoaded) return;
        try {
            localExtensionArchiveStore = new LocalExtensionArchive.Store(getFilesDir());
            userExtensions.clear();
            userExtensions.addAll(localExtensionArchiveStore.list());
            extensionArchiveLoaded = true;
        } catch (IOException | IllegalArgumentException error) {
            Toast.makeText(this, getString(R.string.extension_import_failed, error.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DNS_VPN_CONSENT) {
            if (resultCode == RESULT_OK) {
                startDnsVpnService();
                Toast.makeText(this, "正在启动 DNS-only 实验隧道；解析路径仍未验证", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "VPN 授权未获准；保护未激活，网页浏览仍可继续", Toast.LENGTH_LONG).show();
            }
            if (settingsDialog != null && settingsDialog.isShowing()) settingsDialog.dismiss();
            searchEngineIconHandler.postDelayed(this::showBrowserSettings, 500L);
            return;
        }
        for (BrowserTabSession session : browserSessions.values()) {
            if (session.browser.onActivityResult(requestCode, resultCode, data)) return;
        }
        if (requestCode == REQUEST_SELECT_WALLPAPER) {
            applyWallpaperPickerResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_IMPORT_EXTENSION_ZIP || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        try (InputStream input = getContentResolver().openInputStream(data.getData())) {
            LocalExtensionArchive.PackageData packageData = LocalExtensionArchive.read(input);
            if (localExtensionArchiveStore == null) localExtensionArchiveStore = new LocalExtensionArchive.Store(getFilesDir());
            localExtensionArchiveStore.install(packageData);
            userExtensions.clear();
            userExtensions.addAll(localExtensionArchiveStore.list());
            Toast.makeText(this, R.string.extension_import_success, Toast.LENGTH_LONG).show();
            renderExtensionManagerCards();
        } catch (IOException | IllegalArgumentException error) {
            Toast.makeText(this, getString(R.string.extension_import_failed,
                    error.getMessage() == null ? "invalid package" : error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showWallpaperPicker() {
        if (!isWallpaperPickerWindowReady()) return;
        if (wallpaperDialog != null) {
            if (wallpaperDialog.isShowing()) return;
            wallpaperDialog = null;
        }
        WallpaperPickerFlow.Session picker = WallpaperPickerFlow.open(readWallpaperRotationState());
        Dialog dialog = new Dialog(this);
        dialog.setOnDismissListener(dismissed -> {
            if (wallpaperDialog == dialog) wallpaperDialog = null;
        });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(12));
        sheet.setBackground(rounded(WHITE, dp(22), BORDER));
        TextView title = label(getString(R.string.wallpaper_picker_title), 19, INK, true);
        sheet.addView(title);
        TextView subtitle = label(getString(R.string.wallpaper_picker_subtitle), 12, SECONDARY, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(4);
        subtitleParams.bottomMargin = dp(9);
        sheet.addView(subtitle, subtitleParams);

        FrameLayout preview = new FrameLayout(this);
        preview.setBackground(rounded(Color.rgb(235, 238, 243), dp(14), BORDER));
        preview.setClipToOutline(true);
        ImageView previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable currentWallpaper = wallpaperBackdrop.getDrawable();
        if (currentWallpaper == null) previewImage.setImageResource(R.drawable.new_tab_wallpaper);
        else previewImage.setImageDrawable(currentWallpaper);
        preview.addView(previewImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView previewLabel = label(getCurrentWallpaperCaption(), 12, Color.WHITE, true);
        previewLabel.setPadding(dp(10), dp(7), dp(10), dp(7));
        previewLabel.setBackground(rounded(0xB51B2230, dp(11), Color.TRANSPARENT));
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        captionParams.setMargins(dp(8), 0, dp(8), dp(8));
        preview.addView(previewLabel, captionParams);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(112));
        previewParams.bottomMargin = dp(9);
        sheet.addView(preview, previewParams);

        addWallpaperChoice(sheet, getString(R.string.wallpaper_choose_photo), () -> {
            dialog.dismiss();
            openWallpaperDocumentPicker();
        });
        addWallpaperChoice(sheet, getString(R.string.wallpaper_original), () -> {
            selectBundledWallpaper(WallpaperRotation.DEFAULT_ID);
            refreshWallpaperPicker();
        });

        LinearLayout rotationRow = new LinearLayout(this);
        rotationRow.setGravity(Gravity.CENTER_VERTICAL);
        rotationRow.setPadding(dp(10), 0, dp(8), 0);
        rotationRow.setMinimumHeight(dp(54));
        rotationRow.setBackground(rounded(Color.rgb(247, 248, 251), dp(13), BORDER));
        LinearLayout rotationCopy = new LinearLayout(this);
        rotationCopy.setOrientation(LinearLayout.VERTICAL);
        TextView rotationTitle = label(getString(R.string.wallpaper_daily_rotation), 13, INK, true);
        rotationCopy.addView(rotationTitle);
        TextView rotationHint = label(getString(R.string.wallpaper_daily_rotation_hint), 10, SECONDARY, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(2);
        rotationCopy.addView(rotationHint, hintParams);
        rotationRow.addView(rotationCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SwitchCompat automaticSwitch = new SwitchCompat(this);
        automaticSwitch.setChecked(picker.selection.automatic);
        rotationRow.addView(automaticSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(rotationRow);
        automaticSwitch.setOnCheckedChangeListener((button, checked) -> {
            WallpaperRotation.SelectionState changed = readWallpaperRotationState()
                    .setAutomatic(checked, LocalDate.now().toString());
            if (!persistWallpaperRotationState(changed)) {
                button.setOnCheckedChangeListener(null);
                button.setChecked(!checked);
                button.setOnCheckedChangeListener((source, value) -> {
                    WallpaperRotation.SelectionState retry = readWallpaperRotationState()
                            .setAutomatic(value, LocalDate.now().toString());
                    if (persistWallpaperRotationState(retry)) {
                        applyBundledWallpaper(retry.selectedId);
                        refreshWallpaperPicker();
                    }
                });
                Toast.makeText(this, R.string.wallpaper_rotation_save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            applyBundledWallpaper(changed.selectedId);
            refreshWallpaperPicker();
        });

        TextView galleryTitle = label(getString(R.string.wallpaper_gallery_count,
                WallpaperRotation.all().size()), 12, SECONDARY, true);
        LinearLayout.LayoutParams galleryTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        galleryTitleParams.topMargin = dp(10);
        galleryTitleParams.bottomMargin = dp(5);
        sheet.addView(galleryTitle, galleryTitleParams);
        ScrollView galleryScroll = new ScrollView(this);
        galleryScroll.setFillViewport(false);
        galleryScroll.setVerticalScrollBarEnabled(false);
        galleryScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) ->
                loadVisibleWallpaperThumbnails(galleryScroll));
        LinearLayout galleryRows = new LinearLayout(this);
        galleryRows.setOrientation(LinearLayout.VERTICAL);
        List<WallpaperRotation.Wallpaper> wallpapers = picker.wallpapers();
        for (int i = 0; i < wallpapers.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            WallpaperRotation.Wallpaper first = wallpapers.get(i);
            row.addView(buildWallpaperCard(first), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < wallpapers.size()) {
                WallpaperRotation.Wallpaper second = wallpapers.get(i + 1);
                row.addView(buildWallpaperCard(second), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1f));
            }
            galleryRows.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        galleryScroll.addView(galleryRows);
        sheet.addView(galleryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        try {
            showBottomDialog(dialog, sheet, dp(520), dp(800));
        } catch (WindowManager.BadTokenException | IllegalStateException error) {
            if (dialog.isShowing()) dialog.dismiss();
            return;
        }
        wallpaperDialog = dialog;
        galleryScroll.post(() -> {
            if (wallpaperDialog != dialog || !dialog.isShowing() || !isWallpaperPickerWindowReady()) return;
            loadVisibleWallpaperThumbnails(galleryScroll);
        });
    }

    private View buildWallpaperCard(WallpaperRotation.Wallpaper wallpaper) {
        String selectedId = readWallpaperRotationState().selectedId;
        boolean selected = wallpaper.id.equals(selectedId);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag(wallpaper.id);
        card.setPadding(dp(5), dp(5), dp(5), dp(7));
        card.setBackground(rounded(selected ? Color.rgb(248, 250, 255) : WHITE,
                dp(14), selected ? ACCENT : BORDER));
        card.setFocusable(true);
        card.setContentDescription(getString(selected
                ? R.string.wallpaper_card_selected : R.string.wallpaper_card_description,
                wallpaper.title));
        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(rounded(Color.rgb(235, 238, 243), dp(10), Color.TRANSPARENT));
        thumbnail.setClipToOutline(true);
        thumbnail.setImageResource(R.drawable.ic_browser);
        card.addView(thumbnail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(128)));
        TextView caption = label(wallpaper.title, 11, selected ? ACCENT : INK, true);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        captionParams.topMargin = dp(2);
        card.addView(caption, captionParams);
        card.setOnClickListener(view -> {
            selectBundledWallpaper(wallpaper.id);
            refreshWallpaperPicker();
        });
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMargins(dp(3), dp(3), dp(3), dp(5));
        card.setLayoutParams(cardParams);
        return card;
    }

    private void loadVisibleWallpaperThumbnails(ScrollView galleryScroll) {
        if (galleryScroll.getHeight() <= 0 || galleryScroll.getChildCount() == 0) return;
        View rowsView = galleryScroll.getChildAt(0);
        if (!(rowsView instanceof ViewGroup rows)) return;
        int viewportTop = galleryScroll.getScrollY();
        int viewportBottom = viewportTop + galleryScroll.getHeight();
        for (int rowIndex = 0; rowIndex < rows.getChildCount(); rowIndex++) {
            View row = rows.getChildAt(rowIndex);
            if (row.getBottom() < viewportTop || row.getTop() > viewportBottom) continue;
            if (!(row instanceof ViewGroup cards)) continue;
            for (int cardIndex = 0; cardIndex < cards.getChildCount(); cardIndex++) {
                View cardView = cards.getChildAt(cardIndex);
                if (!(cardView instanceof ViewGroup card)
                        || !(card.getTag() instanceof String wallpaperId)
                        || card.getChildCount() == 0
                        || !(card.getChildAt(0) instanceof ImageView thumbnail)
                        || wallpaperId.equals(thumbnail.getTag())) continue;
                thumbnail.setTag(wallpaperId);
                try {
                    Bitmap bitmap = loadBundledWallpaper(wallpaperId, WALLPAPER_THUMBNAIL_SAMPLE_SIZE);
                    if (bitmap == null) throw new IOException("Bundled wallpaper thumbnail is unavailable");
                    thumbnail.setImageBitmap(bitmap);
                } catch (IOException | RuntimeException error) {
                    thumbnail.setImageResource(R.drawable.ic_browser);
                }
            }
        }
    }

    private String getCurrentWallpaperCaption() {
        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        if (wallpaperPreferenceString(preferences, WALLPAPER_URI_PREFERENCE, null) != null
                || wallpaperPreferenceBoolean(preferences, WALLPAPER_LOCAL_COPY_PREFERENCE, false)) {
            return getString(R.string.wallpaper_local_photo_preview);
        }
        WallpaperRotation.Wallpaper wallpaper = WallpaperRotation.find(
                readWallpaperRotationState().selectedId);
        return getString(R.string.wallpaper_preview_caption,
                wallpaper == null ? getString(R.string.wallpaper_default_name) : wallpaper.title);
    }

    private WallpaperRotation.SelectionState readWallpaperRotationState() {
        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        return new WallpaperRotation.SelectionState(
                wallpaperPreferenceString(preferences, WALLPAPER_BUNDLED_ID_PREFERENCE,
                        WallpaperRotation.DEFAULT_ID),
                wallpaperPreferenceBoolean(preferences, WALLPAPER_AUTO_PREFERENCE, false),
                wallpaperPreferenceString(preferences, WALLPAPER_AUTO_DATE_PREFERENCE, null),
                wallpaperPreferenceString(preferences, WALLPAPER_LAST_AUTO_ID_PREFERENCE, null));
    }

    private String wallpaperPreferenceString(android.content.SharedPreferences preferences,
                                             String key, String fallback) {
        try {
            return preferences.getString(key, fallback);
        } catch (ClassCastException malformedPreference) {
            preferences.edit().remove(key).apply();
            return fallback;
        }
    }

    private boolean wallpaperPreferenceBoolean(android.content.SharedPreferences preferences,
                                                String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (ClassCastException malformedPreference) {
            preferences.edit().remove(key).apply();
            return fallback;
        }
    }

    private boolean persistWallpaperRotationState(WallpaperRotation.SelectionState state) {
        return persistWallpaperRotationState(state, false);
    }

    private boolean persistWallpaperRotationState(WallpaperRotation.SelectionState state, boolean clearPhotoSelection) {
        android.content.SharedPreferences.Editor editor = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE)
                .edit().putBoolean(WALLPAPER_AUTO_PREFERENCE, state.automatic)
                .remove(WALLPAPER_AUTO_DATE_PREFERENCE);
        if (clearPhotoSelection) {
            editor.remove(WALLPAPER_URI_PREFERENCE).remove(WALLPAPER_LOCAL_COPY_PREFERENCE);
        }
        if (state.selectedId == null) editor.remove(WALLPAPER_BUNDLED_ID_PREFERENCE);
        else editor.putString(WALLPAPER_BUNDLED_ID_PREFERENCE, state.selectedId);
        if (state.automaticDate != null) editor.putString(WALLPAPER_AUTO_DATE_PREFERENCE, state.automaticDate);
        if (state.lastAutomaticId != null) editor.putString(WALLPAPER_LAST_AUTO_ID_PREFERENCE, state.lastAutomaticId);
        else editor.remove(WALLPAPER_LAST_AUTO_ID_PREFERENCE);
        return editor.commit();
    }

    private void refreshWallpaperPicker() {
        Dialog previous = wallpaperDialog;
        if (previous == null || !previous.isShowing()) return;
        try {
            previous.dismiss();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Dismiss may race with the window manager while this Activity is leaving.
        }
        if (wallpaperDialog == previous) wallpaperDialog = null;
        postWallpaperPickerAfterMenuDismiss(this::showWallpaperPicker);
    }

    private void addWallpaperChoice(LinearLayout sheet, String title, Runnable action) {
        TextView choice = label(title, 14, INK, true);
        choice.setGravity(Gravity.CENTER_VERTICAL);
        choice.setPadding(dp(14), 0, dp(14), 0);
        choice.setMinHeight(dp(48));
        choice.setBackground(rounded(Color.rgb(247, 248, 251), dp(13), BORDER));
        choice.setFocusable(true);
        choice.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        sheet.addView(choice, params);
    }

    private void selectBundledWallpaper(String wallpaperId) {
        WallpaperPickerFlow.SelectionResult<Bitmap> result = WallpaperPickerFlow
                .open(readWallpaperRotationState())
                .select(wallpaperId, id -> loadBundledWallpaper(id, 1));
        if (!result.successful) {
            Toast.makeText(this, R.string.wallpaper_selection_failed, Toast.LENGTH_LONG).show();
            return;
        }
        WallpaperRotation.Wallpaper wallpaper = WallpaperRotation.find(result.selection.selectedId);
        if (wallpaper == null) return;
        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        String previousUri = wallpaperPreferenceString(preferences, WALLPAPER_URI_PREFERENCE, null);
        if (!persistWallpaperRotationState(result.selection, true)) {
            Toast.makeText(this, R.string.wallpaper_rotation_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (previousUri != null) releaseWallpaperPermission(previousUri);
        new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE).delete();
        new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE + ".tmp").delete();
        wallpaperBackdrop.setImageBitmap(result.preview);
        if (result.usedFallback) {
            Toast.makeText(this, R.string.wallpaper_asset_unavailable, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.wallpaper_manual_selected, wallpaper.title),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean applyBundledWallpaper(String wallpaperId) {
        try {
            Bitmap bitmap = loadBundledWallpaper(wallpaperId, 1);
            if (bitmap == null) throw new IOException("Bundled wallpaper is unavailable");
            wallpaperBackdrop.setImageBitmap(bitmap);
            return true;
        } catch (IOException | IllegalArgumentException error) {
            Toast.makeText(this, R.string.wallpaper_selection_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private Bitmap loadBundledWallpaper(String wallpaperId, int sampleSize) throws IOException {
        WallpaperRotation.Wallpaper wallpaper = WallpaperRotation.find(wallpaperId);
        if (wallpaper == null) throw new IOException("Unknown bundled wallpaper");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sampleSize);
        if (options.inSampleSize > 1) options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap;
        if (wallpaper.assetPath == null) {
            bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.new_tab_wallpaper, options);
        } else {
            try (InputStream input = getAssets().open(wallpaper.assetPath)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
        }
        if (bitmap == null) throw new IOException("Bundled wallpaper could not be decoded");
        return bitmap;
    }

    private void refreshDailyWallpaper() {
        WallpaperRotation.SelectionState current = readWallpaperRotationState();
        if (!current.automatic) return;
        WallpaperRotation.SelectionState updated = current.onLocalDate(LocalDate.now().toString());
        if (updated == current) return;
        if (persistWallpaperRotationState(updated)) applyBundledWallpaper(updated.selectedId);
    }

    private void openWallpaperDocumentPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(picker, REQUEST_SELECT_WALLPAPER);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.wallpaper_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void applyWallpaperPickerResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (uri.getScheme() == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            Toast.makeText(this, R.string.wallpaper_selection_failed, Toast.LENGTH_LONG).show();
            return;
        }

        boolean persistedGrant = false;
        boolean tookGrant = false;
        int readFlag = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (readFlag != 0 && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                persistedGrant = true;
                tookGrant = true;
            } catch (SecurityException ignored) {
                // A readable but non-persistable provider grant is saved as a private thumbnail copy instead.
            }
        }

        try {
            Bitmap thumbnail = loadWallpaperThumbnail(uri);
            if (persistedGrant) {
                replaceWallpaperSelection(uri.toString(), false);
            } else {
                saveWallpaperThumbnail(thumbnail);
                replaceWallpaperSelection(null, true);
            }
            wallpaperBackdrop.setImageBitmap(thumbnail);
            Toast.makeText(this, R.string.wallpaper_selection_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            if (tookGrant) releaseWallpaperPermission(uri.toString());
            Toast.makeText(this, R.string.wallpaper_selection_failed, Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap loadWallpaperThumbnail(Uri uri) throws IOException {
        Bitmap bitmap = getContentResolver().loadThumbnail(uri, new Size(1600, 2400), null);
        if (bitmap == null) throw new IOException("Image provider returned no wallpaper preview");
        return bitmap;
    }

    private void saveWallpaperThumbnail(Bitmap bitmap) throws IOException {
        File temporary = new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE + ".tmp");
        File target = new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Could not save wallpaper preview");
            }
            output.flush();
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Could not replace the previous wallpaper preview");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Could not finish saving the wallpaper preview");
        }
    }

    private void replaceWallpaperSelection(String newUri, boolean localCopy) throws IOException {
        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        String previousUri = wallpaperPreferenceString(preferences, WALLPAPER_URI_PREFERENCE, null);
        android.content.SharedPreferences.Editor editor = preferences.edit()
                .remove(WALLPAPER_URI_PREFERENCE)
                .remove(WALLPAPER_BUNDLED_ID_PREFERENCE)
                .remove(WALLPAPER_AUTO_DATE_PREFERENCE)
                .putBoolean(WALLPAPER_AUTO_PREFERENCE, false)
                .putBoolean(WALLPAPER_LOCAL_COPY_PREFERENCE, localCopy);
        if (!localCopy) editor.putString(WALLPAPER_URI_PREFERENCE, newUri);
        if (!editor.commit()) throw new IOException("Could not persist wallpaper selection");
        if (previousUri != null && !previousUri.equals(newUri)) releaseWallpaperPermission(previousUri);
        if (!localCopy) new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE).delete();
    }

    private void restoreSelectedWallpaper() {
        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        WallpaperRotation.SelectionState rotation = readWallpaperRotationState();
        if (rotation.automatic) {
            WallpaperRotation.SelectionState updated = rotation.onLocalDate(LocalDate.now().toString());
            if (updated != rotation) {
                persistWallpaperRotationState(updated);
                rotation = updated;
            }
            if (!applyBundledWallpaper(rotation.selectedId)) {
                restoreBundledWallpaper(false);
            }
            return;
        }
        String savedUri = wallpaperPreferenceString(preferences, WALLPAPER_URI_PREFERENCE, null);
        try {
            if (savedUri != null) {
                wallpaperBackdrop.setImageBitmap(loadWallpaperThumbnail(Uri.parse(savedUri)));
            } else if (wallpaperPreferenceBoolean(preferences, WALLPAPER_LOCAL_COPY_PREFERENCE, false)) {
                File savedCopy = new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE);
                Bitmap bitmap = savedCopy.isFile() ? BitmapFactory.decodeFile(savedCopy.getAbsolutePath()) : null;
                if (bitmap == null) throw new IOException("Saved wallpaper preview is unavailable");
                wallpaperBackdrop.setImageBitmap(bitmap);
            } else {
                String bundledId = wallpaperPreferenceString(preferences,
                        WALLPAPER_BUNDLED_ID_PREFERENCE, WallpaperRotation.DEFAULT_ID);
                if (!applyBundledWallpaper(bundledId)) restoreBundledWallpaper(false);
            }
        } catch (Exception error) {
            restoreBundledWallpaper(false);
        }
    }

    private void restoreBundledWallpaper(boolean notify) {
        android.content.SharedPreferences preferences = getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
        if (notify) {
            selectBundledWallpaper(WallpaperRotation.DEFAULT_ID);
            return;
        }
        String previousUri = wallpaperPreferenceString(preferences, WALLPAPER_URI_PREFERENCE, null);
        WallpaperRotation.SelectionState selection = readWallpaperRotationState()
                .selectManually(WallpaperRotation.DEFAULT_ID);
        persistWallpaperRotationState(selection, true);
        if (previousUri != null) releaseWallpaperPermission(previousUri);
        new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE).delete();
        new File(getFilesDir(), WALLPAPER_LOCAL_COPY_FILE + ".tmp").delete();
        applyBundledWallpaper(WallpaperRotation.DEFAULT_ID);
        if (notify) Toast.makeText(this, R.string.wallpaper_original_restored, Toast.LENGTH_SHORT).show();
    }

    private void releaseWallpaperPermission(String uriText) {
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uriText), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // The document provider may have removed the URI or its persisted permission.
        }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), dp(4));
        header.setBackgroundColor(Color.TRANSPARENT);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        profileBadge = label("环境", 12, Color.WHITE, true);
        profileBadge.setSingleLine(true);
        profileBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        profileBadge.setMaxWidth(dp(132));
        profileBadge.setGravity(Gravity.CENTER);
        profileBadge.setPadding(dp(10), 0, dp(10), 0);
        profileBadge.setBackground(rounded(0x55313B50, dp(14), 0x88FFFFFF));
        profileBadge.setFocusable(true);
        profileBadge.setOnClickListener(view -> showProfileManager());
        profileBadge.setContentDescription("当前浏览环境；点按管理环境");
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        profileParams.rightMargin = dp(6);
        header.addView(profileBadge, profileParams);

        tabCountButton = label(getString(R.string.tabs_count_button, 1), 14, INK, true);
        tabCountButton.setTextColor(Color.WHITE);
        tabCountButton.setGravity(Gravity.CENTER);
        tabCountButton.setBackground(rounded(Color.TRANSPARENT, dp(12), 0xD9FFFFFF));
        tabCountButton.setFocusable(true);
        tabCountButton.setMinimumWidth(dp(48));
        tabCountButton.setMinimumHeight(dp(48));
        tabCountButton.setOnClickListener(view -> showTabSwitcher());
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        tabsParams.rightMargin = dp(6);
        header.addView(tabCountButton, tabsParams);

        overflowButton = label("⋮", 25, INK, true);
        overflowButton.setTextColor(Color.WHITE);
        overflowButton.setGravity(Gravity.CENTER);
        overflowButton.setBackground(rounded(0x55313B50, dp(14), 0x88FFFFFF));
        overflowButton.setFocusable(true);
        overflowButton.setMinimumWidth(dp(48));
        overflowButton.setMinimumHeight(dp(48));
        overflowButton.setContentDescription("打开浏览器菜单");
        overflowButton.setOnClickListener(view -> showOverflowMenu());
        header.addView(overflowButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private void refreshProfileBadge() {
        if (profileBadge == null || currentBrowserProfile == null) return;
        profileBadge.setText(getString(R.string.profile_badge, currentBrowserProfile.name));
        profileBadge.setContentDescription(getString(R.string.profile_badge_content_description,
                currentBrowserProfile.name));
    }

    private void showProfileManager() {
        if (profileManagerDialog != null && profileManagerDialog.isShowing()) return;
        Dialog dialog = new Dialog(this);
        profileManagerDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> {
            if (profileManagerDialog == dialog) profileManagerDialog = null;
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(18), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(24), BORDER));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("浏览环境", 19, INK, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = label("×", 25, SECONDARY, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.rgb(246, 247, 250), dp(18), BORDER));
        close.setOnClickListener(view -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        sheet.addView(heading);

        TextView disclosure = label("每个环境用随机 UUID 的应用私有 Gecko profile 目录保存浏览数据。切换会先关闭当前会话，再重启 GeckoView；旧版默认浏览数据不自动迁移。环境不是独立 Android 设备，硬件/系统指纹可能相同。", 11, SECONDARY, false);
        disclosure.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams disclosureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        disclosureParams.topMargin = dp(5);
        sheet.addView(disclosure, disclosureParams);

        TextView add = label("＋ 新建环境", 14, WHITE, true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(rounded(ACCENT, dp(14), ACCENT));
        add.setFocusable(true);
        add.setOnClickListener(view -> createBrowserProfile());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        addParams.topMargin = dp(11);
        sheet.addView(add, addParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        profileManagerCards = new LinearLayout(this);
        profileManagerCards.setOrientation(LinearLayout.VERTICAL);
        profileManagerCards.setPadding(0, dp(10), 0, dp(4));
        scroll.addView(profileManagerCards, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderProfileManagerCards();
        showBottomDialog(dialog, sheet, dp(390), dp(720));
    }

    private void renderProfileManagerCards() {
        if (profileManagerCards == null) return;
        profileManagerCards.removeAllViews();
        for (BrowserProfileStore.Profile profile : browserProfileStore.listProfiles()) {
            boolean active = browserProfileStore.isCurrent(profile.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(13), dp(12), dp(13), dp(10));
            card.setBackground(rounded(active ? Color.rgb(239, 244, 255) : WHITE, dp(15),
                    active ? ACCENT : BORDER));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(9);
            profileManagerCards.addView(card, cardParams);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(profile.name, 15, INK, true);
            titleRow.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (active) {
                TextView badge = label("当前环境", 10, ACCENT, true);
                badge.setPadding(dp(7), dp(4), dp(7), dp(4));
                badge.setBackground(rounded(Color.rgb(224, 233, 255), dp(9), Color.TRANSPARENT));
                titleRow.addView(badge);
            }
            card.addView(titleRow);

            TextView detail = label("UA：GeckoView 默认移动模板 · Locale：" + profile.localeSnapshot
                    + " · 时区：" + profile.timezoneSnapshot + "\n存储：应用私有 UUID 目录", 11, SECONDARY, false);
            detail.setMaxLines(3);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(5);
            card.addView(detail, detailParams);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            actionsParams.topMargin = dp(7);
            card.addView(actions, actionsParams);
            addProfileAction(actions, "配置", () -> showProfileConfiguration(profile));
            addProfileAction(actions, "改名", () -> renameBrowserProfile(profile));
            addProfileAction(actions, active ? "当前" : "切换", active ? null
                    : () -> confirmSwitchBrowserProfile(profile));
            addProfileAction(actions, "删除", () -> confirmDeleteBrowserProfile(profile));
        }
    }

    private void addProfileAction(LinearLayout row, String text, Runnable action) {
        TextView button = label(text, 11, action == null ? SECONDARY : ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(Color.rgb(247, 248, 251), dp(10), BORDER));
        button.setFocusable(action != null);
        button.setEnabled(action != null);
        if (action != null) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (row.getChildCount() > 0) params.leftMargin = dp(5);
        row.addView(button, params);
    }

    private void createBrowserProfile() {
        try {
            int nextNumber = browserProfileStore.listProfiles().size() + 1;
            browserProfileStore.createProfile("环境 " + nextNumber,
                    Locale.getDefault().toLanguageTag(), TimeZone.getDefault().getID());
            renderProfileManagerCards();
        } catch (IOException error) {
            Toast.makeText(this, "新建环境失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showProfileConfiguration(BrowserProfileStore.Profile profile) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("环境配置摘要 · " + profile.name)
                .setMessage(profile.configurationSummary()
                        + "\n\n数据隔离依赖 GeckoView 启动参数 -profile 指向上述私有 UUID 目录；此会话未提供 Android 真机，尚未对实际 Gecko 写入路径做设备验证。")
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void renameBrowserProfile(BrowserProfileStore.Profile profile) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(profile.name);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new android.app.AlertDialog.Builder(this)
                .setTitle("编辑环境名称")
                .setMessage("名称只用于显示，不会参与 profile 文件路径。")
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    try {
                        browserProfileStore.renameProfile(profile.id, input.getText().toString());
                        if (browserProfileStore.isCurrent(profile.id)) {
                            currentBrowserProfile = browserProfileStore.currentProfile();
                            refreshProfileBadge();
                        }
                        renderProfileManagerCards();
                    } catch (IOException error) {
                        Toast.makeText(this, "名称未保存：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void confirmSwitchBrowserProfile(BrowserProfileStore.Profile profile) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("切换浏览环境")
                .setMessage("将切换到“" + profile.name + "”。当前打开的标签会关闭；现有 Cookies、缓存、历史与站点存储仍留在各自环境中。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("切换", (dialog, which) -> {
                    try {
                        if (!profilePreferences.edit().commit()) {
                            throw new IOException("当前环境的本地状态尚未成功落盘");
                        }
                        browserProfileStore.selectProfile(profile.id);
                        if (!beginProfileRuntimeRestart()) {
                            browserProfileStore.selectProfile(currentBrowserProfile.id);
                        }
                    } catch (IOException error) {
                        Toast.makeText(this, "环境切换未开始：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void confirmDeleteBrowserProfile(BrowserProfileStore.Profile profile) {
        if (browserProfileStore.listProfiles().size() <= 1) {
            Toast.makeText(this, "至少保留一个环境；请先新建或切换到其他环境。", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除环境？")
                .setMessage("将永久删除“" + profile.name + "”的 Gecko profile 数据，包括 Cookie、缓存、历史、站点存储和站点权限。此操作不可撤销。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("继续", (dialog, which) -> new android.app.AlertDialog.Builder(this)
                        .setTitle("再次确认永久删除")
                        .setMessage("确定删除“" + profile.name + "”及其全部本地 profile 数据吗？")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton("永久删除", (secondDialog, secondWhich) -> deleteBrowserProfile(profile))
                        .show())
                .show();
    }

    private void deleteBrowserProfile(BrowserProfileStore.Profile profile) {
        try {
            boolean active = browserProfileStore.isCurrent(profile.id);
            if (active) {
                if (!profilePreferences.edit().commit()) {
                    throw new IOException("当前环境的本地状态尚未成功落盘");
                }
                BrowserProfileStore.Profile replacement = null;
                for (BrowserProfileStore.Profile candidate : browserProfileStore.listProfiles()) {
                    if (!candidate.id.equals(profile.id)) { replacement = candidate; break; }
                }
                if (replacement == null) throw new IOException("At least one other environment is required");
                browserProfileStore.selectProfile(replacement.id);
            }
            browserProfileStore.requestDelete(profile.id);
            if (active) {
                if (!beginProfileRuntimeRestart()) {
                    browserProfileStore.selectProfile(profile.id);
                    browserProfileStore.cancelPendingDelete(profile.id);
                    renderProfileManagerCards();
                }
            } else {
                if (!clearProfileLocalPreferences(profile.id)) {
                    throw new IOException("Profile metadata could not be cleared; deletion is queued for retry");
                }
                browserProfileStore.completePendingDeletions();
                renderProfileManagerCards();
            }
        } catch (IOException error) {
            Toast.makeText(this, "环境未删除：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean clearProfileLocalPreferences(String profileId) {
        return getSharedPreferences(PROFILE_PREFERENCES_PREFIX + profileId, MODE_PRIVATE)
                .edit().clear().commit();
    }

    private boolean beginProfileRuntimeRestart() {
        final int oldPid = android.os.Process.myPid();
        Intent restart = new Intent(this, ProfileRestartActivity.class);
        restart.putExtra(ProfileRestartActivity.EXTRA_OLD_PID, oldPid);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(restart);
        } catch (RuntimeException error) {
            Toast.makeText(this, "重启协调器无法打开；请手动关闭并重新启动应用后再切换。", Toast.LENGTH_LONG).show();
            return false;
        }
        dismissOverflowMenuSafely();
        if (profileManagerDialog != null && profileManagerDialog.isShowing()) profileManagerDialog.dismiss();
        GeckoViewBrowserAdapter.shutdownForProfileSwitch();
        finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(oldPid), 800L);
        return true;
    }

    private void showOverflowMenu() {
        if (overflowPopup != null && overflowPopup.isShowing()) return;
        int dark = Color.rgb(34, 37, 45);
        int darkCard = Color.rgb(48, 52, 62);
        int light = Color.rgb(245, 247, 250);
        int muted = Color.rgb(175, 182, 195);

        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int availableHeight = Math.max(dp(180), getResources().getDisplayMetrics().heightPixels
                        - safeTopInset - dp(96));
                int cappedHeight = View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED
                        ? availableHeight : Math.min(View.MeasureSpec.getSize(heightMeasureSpec), availableHeight);
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                        cappedHeight, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setBackground(rounded(dark, dp(20), dark));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        List<BrowserUiModel.Action> quickActions = BrowserUiModel.quickActions();
        for (BrowserUiModel.Action action : quickActions) {
            LinearLayout quick = new LinearLayout(this);
            quick.setOrientation(LinearLayout.VERTICAL);
            quick.setGravity(Gravity.CENTER);
            quick.setBackground(rounded(darkCard, dp(15), darkCard));
            quick.setFocusable(true);
            TextView glyph = label(action.glyph, 20, light, true);
            glyph.setGravity(Gravity.CENTER);
            quick.addView(glyph, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
            TextView title = label(action.title, 11, light, true);
            title.setGravity(Gravity.CENTER);
            quick.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
            quick.setOnClickListener(view -> dispatchOverflowAction(action.id));
            LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
            if (quickRow.getChildCount() > 0) quickParams.leftMargin = dp(5);
            quickRow.addView(quick, quickParams);
        }
        panel.addView(quickRow);

        BrowserTabRegistry.Tab active = activeTab();
        GeckoViewBrowserAdapter browser = activeBrowser();
        boolean canBack = active != null && !active.showingHome && browser != null && browser.canGoBack();
        boolean canForward = active != null && !active.showingHome && active.canGoForward;
        boolean loading = active != null && !active.showingHome && active.loading;
        boolean canReload = active != null && !active.showingHome;
        List<BrowserUiModel.Section> sections = BrowserUiModel.menuSections(canBack, canForward,
                loading, canReload, siteMode == SiteMode.Mode.DESKTOP,
                selectedSearchEngine == null ? "Google" : selectedSearchEngine.name);
        for (BrowserUiModel.Section section : sections) {
            TextView heading = label(section.title, 11, muted, true);
            LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headingParams.topMargin = dp(9);
            headingParams.bottomMargin = dp(2);
            panel.addView(heading, headingParams);
            for (BrowserUiModel.Action action : section.actions) {
                panel.addView(darkMenuRow(action, light, muted));
            }
        }

        int safeWidth = getResources().getDisplayMetrics().widthPixels - safeLeftInset - safeRightInset;
        int width = Math.max(dp(240), Math.min(dp(352), safeWidth - dp(20)));
        PopupWindow popup = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        overflowPopup = popup;
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            if (overflowPopup == popup) overflowPopup = null;
        });
        popup.showAsDropDown(overflowButton, 0, dp(6), Gravity.END);
    }

    private View darkMenuRow(BrowserUiModel.Action action, int textColor, int mutedColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        TextView glyph = label(action.glyph, 19, mutedColor, true);
        glyph.setGravity(Gravity.CENTER);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(30), dp(40)));
        TextView title = label(action.title, 13, textColor, false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        titleParams.leftMargin = dp(8);
        row.addView(title, titleParams);
        TextView chevron = label("›", 19, mutedColor, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(40)));
        row.setFocusable(true);
        row.setEnabled(action.enabled);
        row.setAlpha(action.enabled ? 1f : 0.42f);
        row.setContentDescription(action.title);
        row.setOnClickListener(view -> dispatchOverflowAction(action.id));
        return row;
    }

    private void dispatchOverflowAction(String actionId) {
        OverflowActionDispatcher.dispatch(actionId, new OverflowActionDispatcher.Host() {
            @Override
            public void dismissOverflowMenu() {
                dismissOverflowMenuSafely();
            }

            @Override
            public void dispatchOtherAction(String otherActionId) {
                dispatchOtherOverflowAction(otherActionId);
            }
        });
    }

    private void dismissOverflowMenuSafely() {
        PopupWindow popup = overflowPopup;
        if (popup == null) return;
        if (!popup.isShowing()) {
            if (overflowPopup == popup) overflowPopup = null;
            return;
        }
        try {
            popup.dismiss();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            if (overflowPopup == popup) overflowPopup = null;
        }
        if (overflowPopup == popup && !popup.isShowing()) overflowPopup = null;
    }

    private void postWallpaperPickerAfterMenuDismiss(Runnable action) {
        if (action == null) return;
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor == null) return;
        decor.post(() -> {
            if (isWallpaperPickerWindowReady(decor)) action.run();
        });
    }

    private boolean isWallpaperPickerWindowReady() {
        Window window = getWindow();
        return window != null && isWallpaperPickerWindowReady(window.getDecorView());
    }

    private boolean isWallpaperPickerWindowReady(View expectedDecor) {
        if (expectedDecor == null || wallpaperBackdrop == null || !activityResumed
                || isFinishing() || isDestroyed()) return false;
        Window window = getWindow();
        return window != null && window.getDecorView() == expectedDecor
                && expectedDecor.isAttachedToWindow() && expectedDecor.getWindowToken() != null;
    }

    private void dispatchOtherOverflowAction(String actionId) {
        if (BrowserUiModel.NEW_TAB.equals(actionId)) openNewTab();
        else if (BrowserUiModel.SWITCH_TABS.equals(actionId)) showTabSwitcher();
        else if (BrowserUiModel.HOME.equals(actionId)) showHome();
        else if (BrowserUiModel.BACK.equals(actionId)) goBack();
        else if (BrowserUiModel.FORWARD.equals(actionId)) goForward();
        else if (BrowserUiModel.RELOAD.equals(actionId)) {
            GeckoViewBrowserAdapter browser = activeBrowser();
            BrowserTabRegistry.Tab tab = activeTab();
            if (browser != null && tab != null && !tab.showingHome) browser.reload();
        } else if (BrowserUiModel.STOP.equals(actionId)) {
            BrowserTabRegistry.Tab tab = activeTab();
            GeckoViewBrowserAdapter browser = activeBrowser();
            if (tab != null && browser != null && tab.loading) {
                browser.stopLoading();
                tab.loading = false;
                tab.progress = 0;
                renderActiveTab();
            }
        } else if (BrowserUiModel.SITE_MODE.equals(actionId)) toggleSiteMode();
        else if (BrowserUiModel.SEARCH_ENGINE.equals(actionId)) showSearchEnginePicker();
        else if (BrowserUiModel.HISTORY.equals(actionId)) showHistoryDialog();
        else if (BrowserUiModel.BOOKMARKS.equals(actionId)) showBookmarksDialog();
        else if (BrowserUiModel.SAVE_BOOKMARK.equals(actionId)) saveCurrentPageBookmark();
        else if (BrowserUiModel.EXTENSION_ARCHIVE.equals(actionId)) showExtensionManager();
        else if (BrowserUiModel.SETTINGS.equals(actionId)) showBrowserSettings();
    }

    private void showHistoryDialog() {
        List<BrowserDataStore.Entry> entries = browserDataStore.history();
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            BrowserDataStore.Entry entry = entries.get(i);
            labels[i] = entry.title + "\n" + entry.url;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.history_title)
                .setAdapter(new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, labels), (dialog, which) ->
                        openStoredPage(entries.get(which).url))
                .setNeutralButton(R.string.history_clear, (dialog, which) -> confirmClearHistory())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void confirmClearHistory() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.history_clear)
                .setMessage(R.string.history_clear_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_clear, (dialog, which) -> {
                    browserDataStore.clearHistory();
                    Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showBookmarksDialog() {
        List<BrowserDataStore.Entry> entries = browserDataStore.bookmarks();
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.bookmarks_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            BrowserDataStore.Entry entry = entries.get(i);
            labels[i] = entry.title + "\n" + entry.url;
        }
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setAdapter(new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, labels), (owner, which) ->
                        openStoredPage(entries.get(which).url))
                .setNeutralButton(R.string.bookmark_save_current, (owner, which) -> saveCurrentPageBookmark())
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    BrowserDataStore.Entry entry = entries.get(position);
                    new android.app.AlertDialog.Builder(this)
                            .setMessage(getString(R.string.bookmark_remove_confirmation, entry.title))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.bookmark_remove, (confirm, button) -> {
                                browserDataStore.removeBookmark(entry.url);
                                dialog.dismiss();
                                showBookmarksDialog();
                            })
                            .show();
                    return true;
                }));
        dialog.show();
    }

    private void saveCurrentPageBookmark() {
        BrowserTabRegistry.Tab tab = activeTab();
        BrowserTabSession session = activeSession();
        String url = session == null ? (tab == null ? null : tab.url) : session.browser.getUrl();
        if (!isWebUrlString(url) && tab != null) url = tab.url;
        if (!isWebUrlString(url)) {
            Toast.makeText(this, R.string.bookmark_no_page, Toast.LENGTH_SHORT).show();
            return;
        }
        String title = tab == null || tab.title == null || tab.title.trim().isEmpty() ? url : tab.title;
        boolean added = browserDataStore.addBookmark(url, title, System.currentTimeMillis());
        Toast.makeText(this, added ? R.string.bookmark_saved : R.string.bookmark_already_saved,
                Toast.LENGTH_SHORT).show();
    }

    private void openStoredPage(String url) {
        BrowserTabRegistry.Tab tab = activeTab();
        if (tab == null) {
            openNewTab();
            tab = activeTab();
        }
        if (tab != null) navigateTab(tab, url);
    }

    private void showTabSwitcher() {
        if (tabSwitcherDialog != null && tabSwitcherDialog.isShowing()) return;
        Dialog dialog = new Dialog(this);
        tabSwitcherDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(14), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(23), BORDER));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(getString(R.string.tabs_title, tabRegistry.size()), 18, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = label(getString(R.string.tabs_new), 12, ACCENT, true);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(10), 0, dp(10), 0);
        add.setBackground(rounded(Color.rgb(238, 242, 255), dp(13), BORDER));
        add.setOnClickListener(view -> {
            dialog.dismiss();
            openNewTab();
        });
        heading.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        sheet.addView(heading);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        ScrollView tabsScroll = new ScrollView(this);
        tabsScroll.setFillViewport(false);
        tabsScroll.addView(cards, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(12);
        sheet.addView(tabsScroll, scrollParams);
        renderTabSwitcherCards(cards, title, dialog);
        showBottomDialog(dialog, sheet, dp(300), dp(620));
    }

    private void renderTabSwitcherCards(LinearLayout cards, TextView title, Dialog dialog) {
        cards.removeAllViews();
        title.setText(getString(R.string.tabs_title, tabRegistry.size()));
        for (BrowserTabRegistry.Tab tab : tabRegistry.all()) {
            boolean selected = tab == activeTab();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(6), dp(7));
            row.setBackground(rounded(selected ? Color.rgb(238, 242, 255) : WHITE,
                    dp(15), selected ? ACCENT : BORDER));
            row.setFocusable(true);
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(tab.title == null || tab.title.trim().isEmpty()
                    ? getString(R.string.new_tab) : tab.title.trim(), 13, INK, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(name);
            String detail = tab.showingHome ? getString(R.string.new_tab)
                    : tab.url == null ? "" : tab.url;
            TextView url = label(detail, 10, SECONDARY, false);
            url.setSingleLine(true);
            url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            copy.addView(url);
            row.addView(copy, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = label("×", 20, SECONDARY, false);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription(getString(R.string.tab_close_description,
                    tab.title == null || tab.title.trim().isEmpty()
                            ? getString(R.string.new_tab) : tab.title.trim()));
            close.setOnClickListener(view -> {
                closeTab(tab.id);
                renderTabSwitcherCards(cards, title, dialog);
            });
            row.addView(close, new LinearLayout.LayoutParams(dp(38), dp(42)));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                selectTab(tab.id);
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            cards.addView(row, rowParams);
        }
    }

    private void showBrowserSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        Dialog dialog = new Dialog(this);
        settingsDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(23), BORDER));
        TextView title = label(getString(R.string.settings_title), 19, INK, true);
        sheet.addView(title);
        TextView subtitle = label(getString(R.string.settings_subtitle), 12, SECONDARY, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(4);
        sheet.addView(subtitle, subtitleParams);
        String currentMode = siteMode == SiteMode.Mode.DESKTOP
                ? getString(R.string.site_mode_desktop) : getString(R.string.site_mode_phone);
        addSettingsRow(sheet, "⌕", getString(R.string.settings_search_engine),
                selectedSearchEngine.name, () -> {
                    dialog.dismiss();
                    showSearchEnginePicker();
                });
        addSettingsRow(sheet, "▱", getString(R.string.settings_site_mode), currentMode, () -> {
            dialog.dismiss();
            toggleSiteMode();
        });
        addSettingsRow(sheet, "◎", getString(R.string.settings_content_tracking),
                getString(R.string.gecko_tracking_active), this::showContentProtectionDetails);
        boolean webRtcProtectionEnabled = isWebRtcProtectionEnabled();
        int webRtcStatus = GeckoViewBrowserAdapter.isJavaScriptFallback()
                ? R.string.webrtc_protection_enabled_fallback
                : webRtcProtectionEnabled ? R.string.webrtc_protection_enabled
                : R.string.webrtc_protection_disabled;
        addSettingsRow(sheet, "◉", getString(R.string.settings_webrtc_protection),
                getString(webRtcStatus), () -> toggleWebRtcProtection(dialog));
        addSettingsRow(sheet, "⇄", getString(R.string.settings_dns_vpn),
                getString(R.string.gecko_doh_status, dnsVpnStatus()), () -> toggleDnsVpn(dialog));
        BrowserTabSession currentSession = activeSession();
        String currentSiteUrl = currentSession == null || currentSession.tab.showingHome
                ? null : currentSession.currentPageUrl;
        if ((currentSiteUrl == null || currentSiteUrl.isEmpty()) && currentSession != null
                && !currentSession.tab.showingHome) currentSiteUrl = currentSession.tab.url;
        String currentHost = currentSiteUrl == null ? null : Uri.parse(currentSiteUrl).getHost();
        String siteDetail = currentHost == null ? getString(R.string.content_tracking_site_unavailable)
                : getString(R.string.gecko_site_tracking_active, currentHost);
        addSettingsRow(sheet, "⌖", getString(R.string.settings_current_site_tracking), siteDetail,
                this::showContentProtectionDetails);
        addSettingsRow(sheet, "▤", getString(R.string.settings_extension_archive),
                getString(R.string.gecko_extension_status), () -> {
                    dialog.dismiss();
                    showExtensionManager();
                });
        addSettingsRow(sheet, "§", getString(R.string.settings_licenses),
                getString(R.string.open_source_licenses), () -> {
                    dialog.dismiss();
                    showOpenSourceLicenses();
                });
        TextView trackingDisclosure = label(getString(R.string.content_tracking_disclosure), 10, SECONDARY, false);
        trackingDisclosure.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams trackingDisclosureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trackingDisclosureParams.topMargin = dp(7);
        sheet.addView(trackingDisclosure, trackingDisclosureParams);
        TextView webRtcDisclosure = label(getString(R.string.webrtc_protection_disclosure), 10, SECONDARY, false);
        webRtcDisclosure.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams webRtcDisclosureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        webRtcDisclosureParams.topMargin = dp(7);
        sheet.addView(webRtcDisclosure, webRtcDisclosureParams);
        TextView dnsVpnDisclosure = label(getString(R.string.dns_vpn_disclosure), 10, SECONDARY, false);
        dnsVpnDisclosure.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dnsVpnDisclosureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dnsVpnDisclosureParams.topMargin = dp(7);
        sheet.addView(dnsVpnDisclosure, dnsVpnDisclosureParams);
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setVerticalScrollBarEnabled(false);
        settingsScroll.addView(sheet, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showBottomDialog(dialog, settingsScroll, dp(330), dp(620));
    }

    private void showContentProtectionDetails() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_content_tracking)
                .setMessage(R.string.content_tracking_disclosure)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private String dnsVpnStatus() {
        boolean authorized = android.net.VpnService.prepare(this) == null;
        if (!authorized) return getString(R.string.dns_vpn_inactive);
        if (DnsVpnService.isTunnelActuallyConnected()) return getString(R.string.dns_vpn_connected);
        switch (DnsVpnService.getState()) {
            case DnsVpnService.STATE_STARTING:
                return getString(R.string.dns_vpn_starting);
            case DnsVpnService.STATE_CONNECTED_EXPERIMENTAL:
                return getString(R.string.dns_vpn_authorized_disconnected);
            case DnsVpnService.STATE_ERROR:
                return getString(R.string.dns_vpn_error,
                        DnsVpnService.getStateDetail().isEmpty() ? "VPN 启动失败" : DnsVpnService.getStateDetail());
            case DnsVpnService.STATE_STOPPED:
            default:
                return getString(R.string.dns_vpn_authorized_disconnected);
        }
    }

    private void toggleDnsVpn(Dialog dialog) {
        int state = DnsVpnService.getState();
        if (DnsVpnService.isTunnelActuallyConnected() || state == DnsVpnService.STATE_STARTING) {
            stopService(new Intent(this, DnsVpnService.class));
            dialog.dismiss();
            Toast.makeText(this, "DNS-only 实验已停止；网页浏览仍可继续", Toast.LENGTH_SHORT).show();
            searchEngineIconHandler.postDelayed(this::showBrowserSettings, 250L);
            return;
        }
        android.content.Intent consent = android.net.VpnService.prepare(this);
        if (consent != null) {
            dialog.dismiss();
            startActivityForResult(consent, REQUEST_DNS_VPN_CONSENT);
        } else {
            startDnsVpnService();
            dialog.dismiss();
            searchEngineIconHandler.postDelayed(this::showBrowserSettings, 500L);
        }
    }

    private void startDnsVpnService() {
        Intent start = new Intent(this, DnsVpnService.class).setAction(DnsVpnService.ACTION_START);
        startForegroundService(start);
    }

    private boolean isWebRtcProtectionEnabled() {
        android.content.SharedPreferences preferences = extensionPreferences();
        return WebRtcProtectionPolicy.enabledFromPreference(
                preferences.contains(WebRtcProtectionPolicy.PREFERENCE_KEY),
                preferences.getBoolean(WebRtcProtectionPolicy.PREFERENCE_KEY,
                        WebRtcProtectionPolicy.DEFAULT_ENABLED));
    }

    private void toggleWebRtcProtection(Dialog dialog) {
        boolean enabled = !isWebRtcProtectionEnabled();
        extensionPreferences().edit()
                .putBoolean(WebRtcProtectionPolicy.PREFERENCE_KEY, enabled)
                .apply();
        GeckoViewBrowserAdapter.configureWebRtcProtection(this, enabled, result -> {
            reloadOpenPagesAfterPrivacyChange();
            if (dialog.isShowing()) dialog.dismiss();
            int message = result.javascriptFallback ? R.string.webrtc_protection_fallback
                    : result.protectedModeEnabled && result.nativePreferenceApplied
                    ? R.string.webrtc_protection_turned_on
                    : result.protectedModeEnabled ? R.string.webrtc_protection_policy_unverified
                    : result.nativePreferenceApplied ? R.string.webrtc_protection_turned_off
                    : R.string.webrtc_protection_policy_unverified;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            showBrowserSettings();
        });
    }

    private void reloadOpenPagesAfterPrivacyChange() {
        for (BrowserTabSession session : browserSessions.values()) {
            BrowserTabRegistry.Tab tab = session.tab;
            if (tab.showingHome || tab.failed) continue;
            String reloadUrl = session.browser.getUrl();
            if (!isWebUrlString(reloadUrl)) reloadUrl = session.currentPageUrl;
            if (!isWebUrlString(reloadUrl)) reloadUrl = tab.lastRequestedUrl;
            if (!isWebUrlString(reloadUrl)) reloadUrl = tab.url;
            if (isWebUrlString(reloadUrl)) {
                session.browser.stopLoading();
                tab.url = reloadUrl;
                tab.lastRequestedUrl = reloadUrl;
                tab.loading = true;
                tab.progress = 0;
                session.browser.loadUrl(reloadUrl);
            }
        }
        if (activeTab() != null && !activeTab().showingHome) renderActiveTab();
    }

    private void addSettingsRow(LinearLayout sheet, String glyph, String title,
                                String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(6), dp(8), dp(6));
        row.setBackground(rounded(Color.rgb(249, 250, 252), dp(14), BORDER));
        TextView icon = label(glyph, 18, ACCENT, true);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(44)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(title, 13, INK, true);
        copy.addView(name);
        TextView description = label(detail, 10, SECONDARY, false);
        description.setMaxLines(2);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(2);
        copy.addView(description, detailParams);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = label("›", 20, SECONDARY, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(44)));
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(9);
        sheet.addView(row, rowParams);
    }

    private void updateSiteModeButton() {
        if (siteModeButton == null) return;
        boolean desktop = siteMode == SiteMode.Mode.DESKTOP;
        siteModeButton.setText(getString(desktop ? R.string.site_mode_desktop : R.string.site_mode_phone));
        siteModeButton.setContentDescription(getString(
                desktop ? R.string.site_mode_desktop_description : R.string.site_mode_phone_description));
    }

    private void applySiteModeToBrowser() {
        for (BrowserTabSession session : browserSessions.values()) {
            session.browser.setUserAgentOverride(siteMode.userAgentOverride());
        }
    }

    private void toggleSiteMode() {
        siteMode = siteMode.toggled();
        profilePreferences.edit()
                .putString(SITE_MODE_PREFERENCE, siteMode.storedValue()).apply();
        updateSiteModeButton();
        applySiteModeToBrowser();
        BrowserTabSession session = activeSession();
        BrowserTabRegistry.Tab tab = activeTab();
        String currentUrl = session == null ? null : session.browser.getUrl();
        if (tab != null && !tab.showingHome && currentUrl != null && isWebUrl(Uri.parse(currentUrl))) {
            session.browser.reload();
        }
        Toast.makeText(this, siteMode == SiteMode.Mode.DESKTOP
                ? R.string.site_mode_desktop_changed : R.string.site_mode_phone_changed,
                Toast.LENGTH_LONG).show();
    }

    private View buildAddressBar() {
        LinearLayout addressPanel = new LinearLayout(this);
        addressPanel.setOrientation(LinearLayout.VERTICAL);
        addressPanel.setPadding(0, dp(1), 0, dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(4), dp(8), dp(4));
        row.setBackground(rounded(WHITE, dp(24), BORDER));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        rowParams.setMargins(dp(16), dp(4), dp(16), dp(2));
        row.setLayoutParams(rowParams);

        TextView globe = label("◎", 21, ACCENT, true);
        globe.setGravity(Gravity.CENTER);
        row.addView(globe, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT));

        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextSize(15);
        addressInput.setTextColor(INK);
        addressInput.setHintTextColor(SECONDARY);
        addressInput.setHint(getString(R.string.address_hint));
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addressInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressInput.setBackgroundColor(Color.TRANSPARENT);
        addressInput.setPadding(dp(5), 0, dp(5), 0);
        addressInput.setSelectAllOnFocus(false);
        addressInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                navigateFromAddress(addressInput);
                return true;
            }
            return false;
        });
        row.addView(addressInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView go = label(getString(R.string.go), 14, WHITE, true);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(14), 0, dp(14), 0);
        go.setBackground(rounded(ACCENT, dp(13), ACCENT));
        go.setOnClickListener(view -> navigateFromAddress(addressInput));
        go.setContentDescription(getString(R.string.go));
        row.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        addressPanel.addView(row, rowParams);
        return addressPanel;
    }

    private View buildTabStrip() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(3), dp(12), dp(3));
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(false);
        tabItems = new LinearLayout(this);
        tabItems.setOrientation(LinearLayout.HORIZONTAL);
        tabItems.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabItems, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(tabScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView add = label("+", 22, ACCENT, true);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription(getString(R.string.new_tab));
        add.setBackground(rounded(WHITE, dp(11), BORDER));
        add.setOnClickListener(view -> openNewTab());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(36), dp(34));
        addParams.leftMargin = dp(6);
        row.addView(add, addParams);
        return row;
    }

    private void renderTabStrip() {
        if (tabCountButton == null || tabRegistry == null) return;
        int count = tabRegistry.size();
        tabCountButton.setText(getString(R.string.tabs_count_button, count));
        tabCountButton.setContentDescription(getString(R.string.tabs_open_count, count));
    }


    private String readAssetText(String assetPath) throws IOException {
        try (InputStream input = getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private android.content.SharedPreferences extensionPreferences() {
        return getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE);
    }

    private String userExtensionKey(String id, String suffix) {
        return USER_EXTENSION_PREFIX + id + "." + suffix;
    }

    private void markImportedExtensionReadOnly(LocalExtensionArchive.StoredPackage installed) {
        // Imported MV3 archives remain available for review/removal, but are never executed.
        // This app does not implement the isolated-world extension runtime.
        extensionPreferences().edit().putBoolean(userExtensionKey(installed.id, "enabled"), false).apply();
    }

    /** GeckoView runs browser content without an application JavaScript bridge. */
    private void initializeBrowserViews() {
        homeView = buildHomeView();
        contentFrame.addView(homeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorPanel = buildErrorPanel();
        errorPanel.setVisibility(View.GONE);
        contentFrame.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private BrowserTabRegistry.Tab createBrowserTab(String id, Bundle savedState) {
        BrowserTabRegistry.Tab tab = id == null ? tabRegistry.create() : tabRegistry.create(id);
        if (savedState != null) {
            tab.url = savedState.getString(STATE_TAB_URL_PREFIX + tab.id, "");
            tab.title = savedState.getString(STATE_TAB_TITLE_PREFIX + tab.id, "");
            tab.showingHome = savedState.getBoolean(STATE_TAB_HOME_PREFIX + tab.id, tab.url.isEmpty());
        }

        GeckoViewBrowserAdapter browser = GeckoViewBrowserAdapter.createWithSurface(this);
        BrowserTabSession session = new BrowserTabSession(tab, browser);
        browserSessions.put(tab.id, session);
        FrameLayout surface = browser.getSurfaceContainer();
        surface.setBackgroundColor(WHITE);
        surface.setVisibility(View.GONE);
        contentFrame.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        browser.setPageVisible(false);
        browser.setPinchToZoomEnabled(true);
        browser.setUserAgentOverride(siteMode.userAgentOverride());
        tab.initialDesktopUaReloadRequired = savedState != null
                && savedState.getByteArray(STATE_TAB_STATE_PREFIX + tab.id) != null
                && siteMode == SiteMode.Mode.DESKTOP;
        configureBrowserSession(session);

        byte[] navigationState = savedState == null ? null
                : savedState.getByteArray(STATE_TAB_STATE_PREFIX + tab.id);
        if (navigationState != null && navigationState.length > 0) {
            browser.restoreNavigationState(navigationState);
        }
        return tab;
    }

    private void configureBrowserSession(BrowserTabSession session) {
        GeckoViewBrowserAdapter browser = session.browser;
        BrowserTabRegistry.Tab tab = session.tab;
        browser.setRequestInterceptor((url, method, isMainFrame) -> {
            if (url == null || !isWebUrl(Uri.parse(url))) {
                if (isMainFrame) runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        R.string.unsupported_link, Toast.LENGTH_SHORT).show());
                return true;
            }
            return false;
        });
        browser.setPopupHandler((targetUrl, userGesture) -> {
            if (userGesture && targetUrl != null && isWebUrl(Uri.parse(targetUrl))) {
                openNewTab();
                BrowserTabSession popupSession = activeSession();
                return popupSession == null ? null : popupSession.browser.getGeckoSession();
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    R.string.popup_blocked, Toast.LENGTH_SHORT).show());
            return null;
        });
        browser.setDownloadHandler(response -> handleGeckoDownload(session, response));
        browser.setLoadHandler(new GeckoViewBrowserAdapter.LoadHandler() {
            @Override
            public void onLoadStart(String url) {
                session.currentPageUrl = url;
                runOnUiThread(() -> handleTabLoadStart(session, url));
            }

            @Override
            public void onLoadError(int errorCode, String failedUrl) {
                runOnUiThread(() -> {
                    if (!isTabOpen(tab)) return;
                    tab.failed = true;
                    tab.loading = false;
                    tab.errorMessage = errorCode == org.mozilla.geckoview.WebRequestError.ERROR_SECURITY_SSL
                            || errorCode == org.mozilla.geckoview.WebRequestError.ERROR_SECURITY_BAD_CERT
                            || errorCode == org.mozilla.geckoview.WebRequestError.ERROR_BAD_HSTS_CERT
                            ? getString(R.string.ssl_error) : getString(R.string.network_error);
                    tab.progress = 0;
                    renderActiveTab();
                });
            }

            @Override
            public void onLoadComplete(String url, boolean success) {
                runOnUiThread(() -> {
                    if (!isTabOpen(tab)) return;
                    if (success && isWebUrlString(url)) {
                        browserDataStore.recordVisit(url, tab.title, System.currentTimeMillis());
                    }
                });
            }
        });
        browser.setCallback(new GeckoViewBrowserAdapter.Callback() {
            @Override
            public void onUrlChanged(String url) {
                if (url != null) session.currentPageUrl = url;
                runOnUiThread(() -> {
                    if (!isTabOpen(tab)) return;
                    if (url != null && isWebUrl(Uri.parse(url))) {
                        tab.url = url;
                        tab.lastRequestedUrl = url;
                    }
                    if (tab == activeTab()) renderActiveTab();
                });
            }

            @Override
            public void onTitleChanged(String title) {
                runOnUiThread(() -> {
                    if (!isTabOpen(tab)) return;
                    if (title != null && !title.trim().isEmpty()) tab.title = title.trim();
                    if (!tab.loading && isWebUrlString(session.currentPageUrl)) {
                        browserDataStore.recordVisit(session.currentPageUrl, tab.title,
                                System.currentTimeMillis());
                    }
                    if (tab == activeTab()) renderActiveTab();
                    else renderTabStrip();
                });
            }

            @Override
            public void onLoadingStateChanged(boolean loading, boolean canGoBack, boolean canGoForward) {
                runOnUiThread(() -> {
                    if (!isTabOpen(tab)) return;
                    tab.loading = loading;
                    tab.canGoBack = canGoBack;
                    tab.canGoForward = canGoForward;
                    if (loading) tab.progress = Math.max(tab.progress, 20);
                    else {
                        tab.progress = 100;
                        String url = browser.getUrl();
                        if (!tab.failed) {
                            tab.errorMessage = null;
                        }
                    }
                    if (tab == activeTab()) renderActiveTab();
                });
            }
        });
        browser.setOnRenderProcessTerminatedListener((status, errorCode) -> runOnUiThread(() -> {
            if (!isTabOpen(tab)) return;
            tab.failed = true;
            tab.loading = false;
            tab.errorMessage = getString(R.string.geckoview_render_lost);
            tab.progress = 0;
            renderActiveTab();
        }));
    }

    private void handleGeckoDownload(BrowserTabSession session, WebResponse response) {
        if (response == null || response.body == null || response.uri == null
                || !"https".equalsIgnoreCase(Uri.parse(response.uri).getScheme())
                || !response.isSecure || response.statusCode < 200 || response.statusCode >= 300) {
            Toast.makeText(this, R.string.download_rejected, Toast.LENGTH_LONG).show();
            return;
        }
        String contentLength = responseHeader(response, "content-length");
        try {
            if (contentLength != null && Long.parseLong(contentLength.trim()) > MAX_BROWSER_DOWNLOAD_BYTES) {
                response.body.close();
                Toast.makeText(this, R.string.download_too_large, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (NumberFormatException ignored) {
            // The streamed byte counter remains authoritative when servers send invalid lengths.
        } catch (IOException ignored) {
            // The stream is already unusable; the download worker will report the failure below.
        }
        String fileName = safeDownloadFileName(response);
        String mimeType = responseHeader(response, "content-type");
        if (mimeType == null || mimeType.trim().isEmpty()) mimeType = "application/octet-stream";
        int mimeSeparator = mimeType.indexOf(';');
        if (mimeSeparator >= 0) mimeType = mimeType.substring(0, mimeSeparator).trim();
        final String finalMimeType = mimeType;
        final String finalFileName = fileName;
        final InputStream responseBody = response.body;
        downloadExecutor.execute(() -> {
            Uri destination = null;
            try (InputStream input = responseBody) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, finalFileName);
                values.put(MediaStore.Downloads.MIME_TYPE, finalMimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Simple Browser");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IOException("Downloads provider refused the file");
                long bytes = 0;
                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new IOException("Could not open the destination file");
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        bytes += count;
                        if (bytes > MAX_BROWSER_DOWNLOAD_BYTES) {
                            throw new IOException("Download exceeds the 256 MiB safety limit");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(destination, ready, null, null);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.download_saved, finalFileName), Toast.LENGTH_LONG).show());
            } catch (IOException | RuntimeException error) {
                if (destination != null) {
                    try { getContentResolver().delete(destination, null, null); }
                    catch (RuntimeException ignored) { }
                }
                runOnUiThread(() -> Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static String responseHeader(WebResponse response, String name) {
        if (response.headers == null) return null;
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static String safeDownloadFileName(WebResponse response) {
        String candidate = null;
        String disposition = responseHeader(response, "content-disposition");
        if (disposition != null) {
            java.util.regex.Matcher encoded = Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8'[^']*')?([^;]+)",
                    Pattern.CASE_INSENSITIVE).matcher(disposition);
            java.util.regex.Matcher ordinary = Pattern.compile("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))",
                    Pattern.CASE_INSENSITIVE).matcher(disposition);
            try {
                if (encoded.find()) candidate = java.net.URLDecoder.decode(
                        encoded.group(1).trim().replace("\"", ""), "UTF-8");
                else if (ordinary.find()) candidate = ordinary.group(1) != null
                        ? ordinary.group(1) : ordinary.group(2).trim();
            } catch (IllegalArgumentException | IOException ignored) { }
        }
        if (candidate == null || candidate.trim().isEmpty()) {
            candidate = Uri.parse(response.uri).getLastPathSegment();
        }
        if (candidate == null || candidate.trim().isEmpty()) candidate = "download-" + System.currentTimeMillis();
        candidate = candidate.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        while (candidate.startsWith(".")) candidate = candidate.substring(1);
        if (candidate.isEmpty()) candidate = "download-" + System.currentTimeMillis();
        if (candidate.length() > 160) candidate = candidate.substring(0, 160);
        return candidate;
    }

    private void handleTabLoadStart(BrowserTabSession session, String url) {
        BrowserTabRegistry.Tab tab = session.tab;
        if (!isTabOpen(tab)) return;
        if (tab.initialDesktopUaReloadRequired && siteMode == SiteMode.Mode.DESKTOP) {
            tab.initialDesktopUaReloadRequired = false;
            session.browser.setUserAgentOverride(siteMode.userAgentOverride());
            session.browser.reload();
            return;
        }
        tab.failed = false;
        tab.errorMessage = null;
        tab.loading = true;
        tab.progress = 10;
        if (url != null) {
            session.currentPageUrl = url;
            tab.url = url;
            tab.lastRequestedUrl = url;
        }
        if (tab == activeTab()) renderActiveTab();
    }

    private boolean isTabOpen(BrowserTabRegistry.Tab tab) {
        return tab != null && tabRegistry.find(tab.id) == tab;
    }

    private BrowserTabRegistry.Tab activeTab() {
        return tabRegistry == null ? null : tabRegistry.selected();
    }

    private BrowserTabSession activeSession() {
        BrowserTabRegistry.Tab tab = activeTab();
        return tab == null ? null : browserSessions.get(tab.id);
    }

    private GeckoViewBrowserAdapter activeBrowser() {
        BrowserTabSession session = activeSession();
        return session == null ? null : session.browser;
    }

    private View buildHomeView() {
        FrameLayout homeRoot = new FrameLayout(this);
        homeRoot.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(28));

        LinearLayout search = new LinearLayout(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(6), dp(6), dp(10), dp(6));
        search.setBackground(rounded(Color.rgb(31, 34, 42), dp(30), 0x88FFFFFF));
        search.setElevation(dp(5));

        searchEnginePicker = new LinearLayout(this);
        searchEnginePicker.setOrientation(LinearLayout.HORIZONTAL);
        searchEnginePicker.setGravity(Gravity.CENTER);
        searchEnginePicker.setPadding(dp(9), 0, dp(8), 0);
        searchEnginePicker.setMinimumWidth(dp(60));
        searchEnginePicker.setBackground(rounded(Color.rgb(49, 52, 61), dp(24), Color.TRANSPARENT));
        searchEnginePicker.setFocusable(true);
        searchEnginePicker.setOnClickListener(view -> showSearchEnginePicker());
        ImageView selectedEngineIcon = new ImageView(this);
        selectedEngineIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        searchEnginePicker.addView(selectedEngineIcon,
                new LinearLayout.LayoutParams(dp(24), dp(24)));
        searchEnginePicker.setContentDescription(
                getString(R.string.search_engine_picker_description, selectedSearchEngine.name));
        search.addView(searchEnginePicker, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homeAddressInput = new EditText(this);
        homeAddressInput.setSingleLine(true);
        homeAddressInput.setTextSize(16);
        homeAddressInput.setTextColor(Color.WHITE);
        homeAddressInput.setHintTextColor(Color.rgb(193, 197, 207));
        homeAddressInput.setHint(R.string.home_search_hint);
        homeAddressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        homeAddressInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        homeAddressInput.setBackgroundColor(Color.TRANSPARENT);
        homeAddressInput.setPadding(dp(12), 0, dp(6), 0);
        homeAddressInput.setSelectAllOnFocus(false);
        homeAddressInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                navigateFromAddress(homeAddressInput);
                return true;
            }
            return false;
        });
        search.addView(homeAddressInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        content.addView(search, searchParams);

        scroll.addView(content);
        homeRoot.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateSearchEnginePicker();
        return homeRoot;
    }

    private View buildErrorPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        panel.setBackgroundColor(BACKGROUND);

        TextView title = label(getString(R.string.error_title), 20, INK, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        errorMessage = label(getString(R.string.network_error), 14, SECONDARY, false);
        errorMessage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(10);
        panel.addView(errorMessage, messageParams);
        TextView retry = label(getString(R.string.retry), 14, WHITE, true);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(22), 0, dp(22), 0);
        retry.setBackground(rounded(ACCENT, dp(14), ACCENT));
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        retryParams.topMargin = dp(20);
        panel.addView(retry, retryParams);
        retry.setOnClickListener(view -> {
            BrowserTabRegistry.Tab tab = activeTab();
            if (tab != null && tab.lastRequestedUrl != null) {
                navigateTab(tab, tab.lastRequestedUrl);
            }
        });
        return panel;
    }

    private void navigateFromAddress() {
        navigateFromAddress(addressInput);
    }

    private void navigateFromAddress(EditText input) {
        if (input == null) return;
        String target = targetFor(input.getText().toString());
        hideKeyboard(input);
        if (target == null) {
            int message = selectedSearchEngine != null && selectedSearchEngine.urlTemplate.isEmpty()
                    ? R.string.search_engine_template_unavailable : R.string.invalid_address;
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!target.isEmpty()) {
            loadUrl(target);
        }
    }

    private String targetFor(String rawInput) {
        String value = rawInput == null ? "" : rawInput.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (SCHEME_PREFIX.matcher(value).find()) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                if (!HOST_AND_PORT.matcher(value).matches()) {
                    return null;
                }
                value = "https://" + value;
            }
        } else if (looksLikeHost(value)) {
            value = "https://" + value;
        } else {
            return selectedSearchEngine.buildSearchUrl(value);
        }

        Uri uri = Uri.parse(value);
        if (!isWebUrl(uri)) {
            return null;
        }
        return value;
    }

    private Drawable fallbackSearchEngineIcon() {
        return AppCompatResources.getDrawable(this, android.R.drawable.ic_menu_search);
    }

    /** Keep a visible fallback immediately; asset I/O and bitmap decoding stay off the UI thread. */
    private void bindSearchEngineIcon(SearchEngine engine, ImageView target) {
        target.setTag(engine.id);
        target.setImageDrawable(fallbackSearchEngineIcon());
        String assetPath = engine.iconAssetPath();
        if (assetPath == null || unavailableEngineIcons.contains(engine.id)) {
            return;
        }
        Bitmap cached = searchEngineIconCache.get(engine.id);
        if (cached != null) {
            target.setImageDrawable(new BitmapDrawable(getResources(), cached));
            return;
        }
        if (!loadingEngineIcons.add(engine.id)) {
            return;
        }
        try {
            searchEngineIconExecutor.execute(() -> {
                Bitmap decoded = null;
                try {
                    decoded = decodeSearchEngineIcon(assetPath);
                } catch (IOException | RuntimeException ignored) {
                    unavailableEngineIcons.add(engine.id);
                } finally {
                    loadingEngineIcons.remove(engine.id);
                }
                if (decoded == null) {
                    unavailableEngineIcons.add(engine.id);
                    return;
                }
                searchEngineIconCache.put(engine.id, decoded);
                Bitmap loaded = decoded;
                searchEngineIconHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed() && engine.id.equals(target.getTag())) {
                        target.setImageDrawable(new BitmapDrawable(getResources(), loaded));
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            loadingEngineIcons.remove(engine.id);
        }
    }

    private Bitmap decodeSearchEngineIcon(String assetPath) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getAssets().open(assetPath)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        int targetPixels = Math.max(48, Math.round(48 * getResources().getDisplayMetrics().density));
        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > targetPixels * 2
                || bounds.outHeight / sampleSize > targetPixels * 2) {
            sampleSize *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        try (InputStream input = getAssets().open(assetPath)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private void updateSearchEnginePicker() {
        if (searchEnginePicker == null || selectedSearchEngine == null) {
            return;
        }
        ImageView engineIcon = (ImageView) searchEnginePicker.getChildAt(0);
        if (engineIcon != null) bindSearchEngineIcon(selectedSearchEngine, engineIcon);
        searchEnginePicker.setContentDescription(
                getString(R.string.search_engine_picker_description, selectedSearchEngine.name));
    }

    private void showSearchEnginePicker() {
        if (searchEngineDialog != null && searchEngineDialog.isShowing()) {
            return;
        }

        Dialog dialog = new Dialog(this);
        searchEngineDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(12), dp(18), dp(12));
        sheet.setBackground(rounded(WHITE, dp(24), BORDER));

        TextView handle = label(" ", 4, SECONDARY, false);
        handle.setBackground(rounded(Color.rgb(211, 216, 227), dp(4), Color.rgb(211, 216, 227)));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(38), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        sheet.addView(handle, handleParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(getString(R.string.search_engine_picker_title), 20, INK, true);
        headingCopy.addView(title);
        TextView helper = label(getString(R.string.search_engine_picker_subtitle), 12, SECONDARY, false);
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helperParams.topMargin = dp(3);
        headingCopy.addView(helper, helperParams);
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView managePlugins = label(getString(R.string.search_engine_manage), 12, ACCENT, true);
        managePlugins.setGravity(Gravity.CENTER);
        managePlugins.setPadding(dp(8), 0, dp(8), 0);
        managePlugins.setFocusable(true);
        managePlugins.setOnClickListener(view -> showPluginManager());
        heading.addView(managePlugins, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

        TextView close = label("×", 25, SECONDARY, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.rgb(246, 247, 250), dp(18), BORDER));
        close.setContentDescription(getString(R.string.close));
        close.setOnClickListener(view -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        sheet.addView(heading);

        LinearLayout categoryTabs = new LinearLayout(this);
        categoryTabs.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        tabsParams.topMargin = dp(16);
        sheet.addView(categoryTabs, tabsParams);

        LinearLayout featureBar = new LinearLayout(this);
        featureBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView featureLabel = label(getString(R.string.search_engine_feature_label), 11, SECONDARY, true);
        featureBar.addView(featureLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        HorizontalScrollView featureScroll = new HorizontalScrollView(this);
        featureScroll.setHorizontalScrollBarEnabled(false);
        featureScroll.setFillViewport(false);
        LinearLayout featureTabs = new LinearLayout(this);
        featureTabs.setOrientation(LinearLayout.HORIZONTAL);
        featureTabs.setGravity(Gravity.CENTER_VERTICAL);
        featureScroll.addView(featureTabs, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams featureScrollParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        featureScrollParams.leftMargin = dp(8);
        featureBar.addView(featureScroll, featureScrollParams);
        LinearLayout.LayoutParams featureBarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        featureBarParams.topMargin = dp(3);
        sheet.addView(featureBar, featureBarParams);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(dp(12), 0, dp(12), 0);
        filterRow.setBackground(rounded(Color.rgb(247, 248, 251), dp(13), BORDER));
        TextView searchIcon = label("⌕", 22, SECONDARY, false);
        searchIcon.setGravity(Gravity.CENTER);
        filterRow.addView(searchIcon, new LinearLayout.LayoutParams(dp(28), dp(44)));
        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setTextSize(14);
        filter.setTextColor(INK);
        filter.setHintTextColor(SECONDARY);
        filter.setHint(R.string.search_engine_search_hint);
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        filter.setBackgroundColor(Color.TRANSPARENT);
        filter.setPadding(dp(4), 0, 0, 0);
        filterRow.addView(filter, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        filterParams.topMargin = dp(10);
        filterParams.bottomMargin = dp(8);
        sheet.addView(filterRow, filterParams);

        FrameLayout results = new FrameLayout(this);
        ListView engineList = new ListView(this);
        engineList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        engineList.setDividerHeight(dp(8));
        engineList.setPadding(0, dp(1), 0, dp(8));
        engineList.setClipToPadding(false);
        TextView empty = label("", 13, SECONDARY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(12), dp(32), dp(12), dp(32));
        results.addView(engineList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        results.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        engineList.setEmptyView(empty);
        sheet.addView(results, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = label(getString(R.string.search_engine_picker_footer), 11, SECONDARY, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(7);
        sheet.addView(footer, footerParams);

        activeCategoryTabs = categoryTabs;
        activeFeatureTabs = featureTabs;
        activePickerFilter = filter;
        activePickerEmpty = empty;
        activePickerCategory = new SearchEngine.Category[]{selectedSearchEngine.category};
        activePickerFeatureCategory = new String[]{selectedSearchEngine.featureCategory};
        activeEngineList = engineList;
        activeEngineAdapter = new SearchEnginePickerAdapter(this, allSearchEngines(),
                selectedSearchEngine.id, this::bindSearchEngineIcon);
        engineList.setAdapter(activeEngineAdapter);
        engineList.setOnItemClickListener((parent, row, position, itemId) -> {
            selectedSearchEngine = activeEngineAdapter.getItem(position);
            activeEngineAdapter.setSelectedId(selectedSearchEngine.id);
            persistSelectedEngine();
            updateSearchEnginePicker();
            dialog.dismiss();
        });
        engineList.setFocusable(true);
        renderCategoryTabs(categoryTabs, activePickerCategory);
        renderFeatureTabs(featureTabs);
        renderEngineCards(activePickerCategory[0], activePickerFeatureCategory[0], "");

        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (pendingSearchEngineFilter != null) {
                    searchEngineFilterHandler.removeCallbacks(pendingSearchEngineFilter);
                }
                String query = value.toString();
                pendingSearchEngineFilter = () -> {
                    if (dialog.isShowing()) {
                        renderEngineCards(activePickerCategory[0], activePickerFeatureCategory[0], query);
                    }
                };
                searchEngineFilterHandler.postDelayed(pendingSearchEngineFilter, 70L);
            }

            @Override
            public void afterTextChanged(Editable value) { }
        });
        dialog.setOnDismissListener(ignored -> {
            if (pendingSearchEngineFilter != null) {
                searchEngineFilterHandler.removeCallbacks(pendingSearchEngineFilter);
                pendingSearchEngineFilter = null;
            }
            if (searchEngineDialog == dialog) searchEngineDialog = null;
            activeCategoryTabs = null;
            activeFeatureTabs = null;
            activeEngineList = null;
            activeEngineAdapter = null;
            activePickerFilter = null;
            activePickerCategory = null;
            activePickerFeatureCategory = null;
            activePickerEmpty = null;
        });

        FrameLayout shell = new FrameLayout(this);
        shell.setPadding(dp(10), 0, dp(10), dp(8));
        shell.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
        dialog.setContentView(shell);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setGravity(Gravity.BOTTOM);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int height = Math.max(dp(360), Math.min(dp(720), screenHeight - dp(88)));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void renderCategoryTabs(LinearLayout tabs, SearchEngine.Category[] activeCategory) {
        tabs.removeAllViews();
        List<SearchEngine> engines = activeEngineAdapter == null
                ? allSearchEngines() : activeEngineAdapter.getEngines();
        for (SearchEngine.Category category : SearchEngine.CATEGORIES) {
            int count = 0;
            for (SearchEngine engine : engines) {
                if (engine.category == category) count++;
            }
            boolean selected = activeCategory[0] == category;
            TextView chip = label(category.label + "\n" + count, 10,
                    selected ? categoryColor(category) : SECONDARY, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(2);
            chip.setPadding(dp(3), 0, dp(3), 0);
            chip.setBackground(rounded(selected ? categoryTint(category) : WHITE,
                    dp(13), selected ? categoryTint(category) : BORDER));
            chip.setContentDescription(getString(R.string.search_engine_category_description,
                    category.label, count));
            chip.setOnClickListener(view -> {
                activeCategory[0] = category;
                activePickerFeatureCategory[0] = "";
                renderCategoryTabs(tabs, activeCategory);
                renderFeatureTabs(activeFeatureTabs);
                renderEngineCards(category, "", activePickerFilter.getText().toString());
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (tabs.getChildCount() > 0) chipParams.leftMargin = dp(6);
            tabs.addView(chip, chipParams);
        }
    }

    private void renderFeatureTabs(LinearLayout tabs) {
        if (tabs == null || activePickerCategory == null || activePickerFeatureCategory == null
                || activeEngineAdapter == null) return;
        tabs.removeAllViews();
        List<SearchEngine> engines = activeEngineAdapter.getEngines();
        SearchEngine.Category accessCategory = activePickerCategory[0];
        int allCount = SearchEngineCatalog.filter(engines, accessCategory, null, "").size();
        addFeatureChip(tabs, "", getString(R.string.search_engine_feature_all), allCount);
        for (String feature : SearchEngineCatalog.featureCategories(engines, accessCategory)) {
            int count = SearchEngineCatalog.filter(engines, accessCategory, feature, "").size();
            addFeatureChip(tabs, feature, feature, count);
        }
    }

    private void addFeatureChip(LinearLayout tabs, String feature, String labelText, int count) {
        boolean selected = feature.equals(activePickerFeatureCategory[0]);
        TextView chip = label(labelText + " · " + count, 11,
                selected ? ACCENT : SECONDARY, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setBackground(rounded(selected ? Color.rgb(239, 243, 255) : WHITE,
                dp(12), selected ? Color.rgb(212, 221, 250) : BORDER));
        chip.setOnClickListener(view -> {
            activePickerFeatureCategory[0] = feature;
            renderFeatureTabs(tabs);
            renderEngineCards(activePickerCategory[0], feature, activePickerFilter.getText().toString());
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.rightMargin = dp(6);
        tabs.addView(chip, params);
    }

    private void renderEngineCards(SearchEngine.Category category, String featureCategory,
                                   String rawFilter) {
        if (activeEngineAdapter == null || activeEngineList == null) return;
        activeEngineAdapter.setFilters(category, featureCategory, rawFilter);
        if (activeEngineAdapter.getCount() == 0 && activePickerEmpty != null) {
            int categoryCount = SearchEngineCatalog.filter(activeEngineAdapter.getEngines(),
                    category, null, "").size();
            int message = categoryCount == 0 ? R.string.search_engine_category_empty
                    : R.string.search_engine_no_matches;
            activePickerEmpty.setText(getString(message, category.label));
        }
        activeEngineList.setSelection(0);
    }

    private List<SearchEngine> allSearchEngines() {
        return SearchEngine.withCustom(customEngineStore == null
                ? Collections.emptyList() : customEngineStore.getAll());
    }

    private void refreshSearchPicker() {
        updateSearchEnginePicker();
        if (searchEngineDialog == null || !searchEngineDialog.isShowing()
                || activeCategoryTabs == null || activeFeatureTabs == null || activeEngineList == null
                || activeEngineAdapter == null || activePickerFilter == null || activePickerCategory == null
                || activePickerFeatureCategory == null) {
            return;
        }
        activePickerCategory[0] = selectedSearchEngine.category;
        activePickerFeatureCategory[0] = selectedSearchEngine.featureCategory;
        activeEngineAdapter.setSelectedId(selectedSearchEngine.id);
        activeEngineAdapter.setEngines(allSearchEngines());
        renderCategoryTabs(activeCategoryTabs, activePickerCategory);
        renderFeatureTabs(activeFeatureTabs);
        renderEngineCards(activePickerCategory[0], activePickerFeatureCategory[0],
                activePickerFilter.getText().toString());
    }

    private void showExtensionManager() {
        if (extensionManagerDialog != null && extensionManagerDialog.isShowing()) {
            return;
        }
        initializeLocalExtensionArchive();
        Dialog dialog = new Dialog(this);
        extensionManagerDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(18), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(24), BORDER));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(getString(R.string.extension_manager_title), 19, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = label("×", 25, SECONDARY, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.rgb(246, 247, 250), dp(18), BORDER));
        close.setContentDescription(getString(R.string.close));
        close.setOnClickListener(view -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        sheet.addView(heading);

        TextView subtitle = label(getString(R.string.extension_manager_subtitle), 12, SECONDARY, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(3);
        sheet.addView(subtitle, subtitleParams);

        extensionManagerCards = new LinearLayout(this);
        extensionManagerCards.setOrientation(LinearLayout.VERTICAL);
        extensionManagerCards.setPadding(0, dp(10), 0, dp(10));
        ScrollView installedScroll = new ScrollView(this);
        installedScroll.setFillViewport(false);
        installedScroll.addView(extensionManagerCards, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(installedScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView licenses = label(getString(R.string.open_source_licenses), 12, ACCENT, true);
        licenses.setGravity(Gravity.CENTER);
        licenses.setFocusable(true);
        licenses.setOnClickListener(view -> showOpenSourceLicenses());
        sheet.addView(licenses, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        renderExtensionManagerCards();
        showBottomDialog(dialog, sheet, dp(380), dp(690));
    }

    private void showOpenSourceLicenses() {
        try {
            String[] licenseAssets = {
                    "THIRD-PARTY-NOTICES.txt",
                    "SEARCH-ENGINE-ICON-ATTRIBUTION.txt",
                    "MOZILLA-GECKOVIEW-MPL-2.0.txt",
                    "APACHE-2.0.txt"
            };
            StringBuilder text = new StringBuilder();
            for (String asset : licenseAssets) {
                text.append("\n\n========== ").append(asset).append(" ==========\n")
                        .append(readLicenseAsset(asset));
            }
            TextView body = label(text.toString(), 12, SECONDARY, false);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(2), 1f);
            body.setPadding(dp(8), dp(8), dp(8), dp(8));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(body, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.open_source_licenses_title)
                    .setView(scroll)
                    .setPositiveButton(R.string.close, null)
                    .show();
        } catch (IOException error) {
            Toast.makeText(this, R.string.open_source_licenses_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private String readLicenseAsset(String name) throws IOException {
        try (InputStream input = getAssets().open("licenses/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private void chooseExtensionZip() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(android.content.Intent.createChooser(intent,
                getString(R.string.extension_import_picker_title)), REQUEST_IMPORT_EXTENSION_ZIP);
    }

    private void renderExtensionManagerCards() {
        if (extensionManagerCards == null) return;
        extensionManagerCards.removeAllViews();
        LinearLayout nativeCard = new LinearLayout(this);
        nativeCard.setOrientation(LinearLayout.VERTICAL);
        nativeCard.setPadding(dp(14), dp(13), dp(14), dp(13));
        nativeCard.setBackground(rounded(WHITE, dp(15), BORDER));
        extensionManagerCards.addView(nativeCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nativeCard.addView(label(getString(R.string.gecko_extension_status), 14, INK, true));
        TextView detail = label(getString(R.string.content_tracking_disclosure), 11, SECONDARY, false);
        detail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(6);
        nativeCard.addView(detail, detailParams);
        TextView importButton = label(getString(R.string.extension_import_picker_title), 13, WHITE, true);
        importButton.setGravity(Gravity.CENTER);
        importButton.setBackground(rounded(ACCENT, dp(12), ACCENT));
        importButton.setFocusable(true);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        importParams.topMargin = dp(11);
        nativeCard.addView(importButton, importParams);
        importButton.setOnClickListener(view -> chooseExtensionZip());
        renderUserExtensionCards();
    }

    private void renderUserExtensionCards() {
        android.content.SharedPreferences preferences = extensionPreferences();
        for (LocalExtensionArchive.StoredPackage installed : userExtensions) {
            Mv3ExtensionManifest manifest = installed.data.manifest;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackground(rounded(WHITE, dp(15), BORDER));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = dp(9);
            extensionManagerCards.addView(card, cardParams);

            card.addView(label(manifest.name + "  ·  v" + manifest.version, 15, INK, true));
            TextView description = label(manifest.description, 12, SECONDARY, false);
            description.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descriptionParams.topMargin = dp(5);
            card.addView(description, descriptionParams);

            StringBuilder scopes = new StringBuilder();
            scopes.append(getString(R.string.extension_imported_permission_supported,
                    manifest.permissions.isEmpty() ? "none" : joinValues(manifest.permissions)));
            if (!manifest.unsupportedPermissions.isEmpty()) scopes.append("\n• ")
                    .append(getString(R.string.extension_imported_permission_unsupported,
                            joinValues(manifest.unsupportedPermissions)));
            scopes.append("\n• ").append(getString(R.string.extension_imported_host_scope,
                    manifest.hostPermissions.isEmpty() ? "none" : joinHostPatterns(manifest.hostPermissions)));
            if (!manifest.unsupportedFeatures.isEmpty()) scopes.append("\n• ")
                    .append(getString(R.string.extension_imported_unsupported,
                            joinValues(manifest.unsupportedFeatures)));
            scopes.append("\n• ").append(getString(R.string.extension_imported_local_note));
            TextView permissions = label(scopes.toString(), 11, SECONDARY, false);
            permissions.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams scopesParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scopesParams.topMargin = dp(8);
            card.addView(permissions, scopesParams);

            String enabledKey = userExtensionKey(installed.id, "enabled");
            String consentKey = userExtensionKey(installed.id, "consented");
            boolean consented = preferences.getBoolean(consentKey, false);
            TextView state = label(getString(R.string.extension_status_disabled),
                    12, SECONDARY, true);
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stateParams.topMargin = dp(8);
            card.addView(state, stateParams);
            TextView support = label(getString(R.string.extension_imported_status_unsupported),
                    11, SECONDARY, false);
            card.addView(support);

            TextView toggle = label(getString(R.string.extension_imported_not_runnable), 12, SECONDARY, true);
            toggle.setGravity(Gravity.CENTER);
            toggle.setEnabled(false);
            toggle.setAlpha(0.75f);
            toggle.setBackground(rounded(Color.rgb(241, 243, 247), dp(12), BORDER));
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            toggleParams.topMargin = dp(9);
            card.addView(toggle, toggleParams);

            if (consented) {
                TextView revoke = pluginAction(getString(R.string.extension_imported_revoke),
                        Color.rgb(178, 65, 72), () -> {
                            preferences.edit().putBoolean(enabledKey, false).putBoolean(consentKey, false).apply();
                            markImportedExtensionReadOnly(installed);
                            Toast.makeText(this, R.string.extension_imported_revoked, Toast.LENGTH_SHORT).show();
                            renderExtensionManagerCards();
                        });
                LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
                actionParams.topMargin = dp(4);
                card.addView(revoke, actionParams);
            }
            TextView remove = pluginAction(getString(R.string.extension_imported_remove),
                    Color.rgb(178, 65, 72), () -> confirmRemoveExtension(installed));
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            removeParams.topMargin = dp(4);
            card.addView(remove, removeParams);
        }
    }

    private String joinValues(Iterable<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private String joinHostPatterns(List<UrlMatchPattern> patterns) {
        StringBuilder result = new StringBuilder();
        for (UrlMatchPattern pattern : patterns) {
            if (result.length() > 0) result.append(", ");
            result.append(pattern.source());
        }
        return result.toString();
    }

    private void confirmRemoveExtension(LocalExtensionArchive.StoredPackage installed) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.extension_imported_remove_title)
                .setMessage(getString(R.string.extension_imported_remove_message, installed.data.manifest.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.extension_imported_remove, (dialog, which) -> {
                    try {
                        if (localExtensionArchiveStore != null) localExtensionArchiveStore.remove(installed.id);
                        android.content.SharedPreferences.Editor editor = extensionPreferences().edit();
                        for (String suffix : new String[]{"enabled", "consented", "storage"}) {
                            editor.remove(userExtensionKey(installed.id, suffix));
                        }
                        editor.apply();
                        userExtensions.remove(installed);
                        renderExtensionManagerCards();
                    } catch (IOException error) {
                        Toast.makeText(this, getString(R.string.extension_import_failed, error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }


    private void showPluginManager() {
        if (pluginManagerDialog != null && pluginManagerDialog.isShowing()) {
            return;
        }
        Dialog dialog = new Dialog(this);
        pluginManagerDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(18), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(24), BORDER));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCopy = new LinearLayout(this);
        titleCopy.setOrientation(LinearLayout.VERTICAL);
        titleCopy.addView(label(getString(R.string.plugin_manager_title), 19, INK, true));
        TextView subtitle = label(getString(R.string.plugin_manager_subtitle), 12, SECONDARY, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(3);
        titleCopy.addView(subtitle, subtitleParams);
        heading.addView(titleCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = label("×", 25, SECONDARY, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.rgb(246, 247, 250), dp(18), BORDER));
        close.setContentDescription(getString(R.string.close));
        close.setOnClickListener(view -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        sheet.addView(heading);

        TextView add = label(getString(R.string.plugin_add), 14, WHITE, true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(rounded(ACCENT, dp(14), ACCENT));
        add.setFocusable(true);
        add.setOnClickListener(view -> showPluginEditor(null));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        addParams.topMargin = dp(14);
        sheet.addView(add, addParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        pluginManagerCards = new LinearLayout(this);
        pluginManagerCards.setOrientation(LinearLayout.VERTICAL);
        pluginManagerCards.setPadding(0, dp(12), 0, dp(8));
        scroll.addView(pluginManagerCards, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView builtInsNote = label(getString(R.string.plugin_manager_builtins), 11, SECONDARY, false);
        builtInsNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(6);
        sheet.addView(builtInsNote, noteParams);
        renderPluginManagerCards();
        showBottomDialog(dialog, sheet, dp(380), dp(720));
    }

    private void renderPluginManagerCards() {
        if (pluginManagerCards == null) {
            return;
        }
        pluginManagerCards.removeAllViews();
        List<SearchEngine> customEngines = customEngineStore.getAll();
        if (customEngines.isEmpty()) {
            TextView empty = label(getString(R.string.plugin_manager_empty), 13, SECONDARY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(30), dp(20), dp(30));
            pluginManagerCards.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (SearchEngine engine : customEngines) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(13), dp(12), dp(13), dp(11));
            card.setBackground(rounded(WHITE, dp(15), BORDER));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(9);
            pluginManagerCards.addView(card, cardParams);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(engine.name, 14, INK, true);
            titleRow.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView category = label(engine.category.label, 10, categoryColor(engine.category), true);
            category.setPadding(dp(7), dp(4), dp(7), dp(4));
            category.setBackground(rounded(categoryTint(engine.category), dp(9), categoryTint(engine.category)));
            titleRow.addView(category);
            card.addView(titleRow);

            TextView detail = label(engine.purpose + " · " + engine.accessNote, 12, SECONDARY, false);
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(5);
            card.addView(detail, detailParams);
            TextView template = label(engine.urlTemplate, 10, SECONDARY, false);
            template.setSingleLine(true);
            template.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams templateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            templateParams.topMargin = dp(3);
            card.addView(template, templateParams);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
            actionsParams.topMargin = dp(7);
            card.addView(actions, actionsParams);
            TextView edit = pluginAction(getString(R.string.plugin_edit), ACCENT,
                    () -> showPluginEditor(engine));
            actions.addView(edit, new LinearLayout.LayoutParams(dp(70), dp(34)));
            TextView remove = pluginAction(getString(R.string.plugin_remove), Color.rgb(178, 65, 72),
                    () -> confirmRemovePlugin(engine));
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(70), dp(34));
            removeParams.leftMargin = dp(8);
            actions.addView(remove, removeParams);
        }
    }

    private TextView pluginAction(String text, int color, Runnable action) {
        TextView button = label(text, 12, color, true);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackground(rounded(Color.rgb(247, 248, 251), dp(11), BORDER));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void confirmRemovePlugin(SearchEngine engine) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.plugin_remove_title)
                .setMessage(getString(R.string.plugin_remove_message, engine.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.plugin_remove, (dialog, which) -> {
                    if (customEngineStore.remove(engine.id)) {
                        if (selectedSearchEngine.id.equals(engine.id)) {
                            selectedSearchEngine = SearchEngine.byId(SearchEngine.DEFAULT_ID);
                            persistSelectedEngine();
                        }
                        refreshSearchPicker();
                        renderPluginManagerCards();
                    }
                })
                .show();
    }

    private void showPluginEditor(SearchEngine editing) {
        Dialog dialog = new Dialog(this);
        pluginEditorDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(18), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(24), BORDER));
        String titleText = editing == null ? getString(R.string.plugin_add_title)
                : getString(R.string.plugin_edit_title);
        sheet.addView(label(titleText, 19, INK, true));
        TextView intro = label(getString(R.string.plugin_editor_intro), 12, SECONDARY, false);
        intro.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introParams.topMargin = dp(5);
        sheet.addView(intro, introParams);

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(form, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        EditText name = pluginEditorField(form, getString(R.string.plugin_name_label),
                getString(R.string.plugin_name_hint), editing == null ? "" : editing.name, false);
        EditText template = pluginEditorField(form, getString(R.string.plugin_url_label),
                "https://example.org/search?q={query}", editing == null ? "" : editing.urlTemplate, false);
        template.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText purpose = pluginEditorField(form, getString(R.string.plugin_purpose_label),
                getString(R.string.plugin_purpose_hint), editing == null ? "" : editing.purpose, false);
        EditText access = pluginEditorField(form, getString(R.string.plugin_access_label),
                getString(R.string.plugin_access_hint), editing == null ? "" : editing.accessNote, true);

        TextView categoryLabel = label(getString(R.string.plugin_category_label), 13, INK, true);
        LinearLayout.LayoutParams categoryLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        categoryLabelParams.topMargin = dp(12);
        categoryLabelParams.bottomMargin = dp(3);
        form.addView(categoryLabel, categoryLabelParams);
        RadioGroup categories = new RadioGroup(this);
        categories.setOrientation(RadioGroup.VERTICAL);
        form.addView(categories);
        int selectedCategoryId = -1;
        int categoryIndex = 0;
        for (SearchEngine.Category category : SearchEngine.CATEGORIES) {
            RadioButton option = new RadioButton(this);
            option.setId(5000 + categoryIndex);
            option.setText(category.label);
            option.setTextSize(13);
            option.setTextColor(INK);
            categories.addView(option, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            if (editing == null && category == SearchEngine.Category.FREE
                    || editing != null && editing.category == category) {
                selectedCategoryId = option.getId();
            }
            categoryIndex++;
        }
        if (selectedCategoryId >= 0) {
            categories.check(selectedCategoryId);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        buttonsParams.topMargin = dp(8);
        sheet.addView(buttons, buttonsParams);
        TextView cancel = pluginAction(getString(R.string.cancel), SECONDARY, dialog::dismiss);
        buttons.addView(cancel, new LinearLayout.LayoutParams(dp(84), dp(40)));
        TextView save = label(getString(R.string.save), 13, WHITE, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(rounded(ACCENT, dp(12), ACCENT));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(88), dp(40));
        saveParams.leftMargin = dp(8);
        buttons.addView(save, saveParams);
        save.setOnClickListener(view -> {
            int checkedId = categories.getCheckedRadioButtonId();
            SearchEngine.Category category = checkedId < 0 ? null
                    : SearchEngine.CATEGORIES.get(checkedId - 5000);
            String engineId = editing == null
                    ? "custom-" + UUID.randomUUID().toString().toLowerCase(Locale.ROOT)
                    : editing.id;
            final SearchEngine engine;
            try {
                engine = SearchEngine.createCustom(engineId, name.getText().toString(),
                        template.getText().toString(), category, purpose.getText().toString(),
                        access.getText().toString());
            } catch (IllegalArgumentException exception) {
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            boolean saved = editing == null ? customEngineStore.add(engine)
                    : customEngineStore.edit(editing.id, engine);
            if (!saved) {
                Toast.makeText(this, R.string.plugin_save_error, Toast.LENGTH_SHORT).show();
                return;
            }
            if (editing != null && selectedSearchEngine.id.equals(editing.id)) {
                selectedSearchEngine = SearchEngine.byId(editing.id, allSearchEngines());
                persistSelectedEngine();
            }
            refreshSearchPicker();
            renderPluginManagerCards();
            dialog.dismiss();
        });

        showBottomDialog(dialog, sheet, dp(500), dp(760));
    }

    private EditText pluginEditorField(LinearLayout form, String labelText, String hint,
                                       String value, boolean multiline) {
        TextView fieldLabel = label(labelText, 13, INK, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(10);
        labelParams.bottomMargin = dp(5);
        form.addView(fieldLabel, labelParams);
        EditText field = new EditText(this);
        field.setTextSize(14);
        field.setTextColor(INK);
        field.setHintTextColor(SECONDARY);
        field.setHint(hint);
        field.setText(value);
        field.setPadding(dp(12), dp(9), dp(12), dp(9));
        field.setBackground(rounded(WHITE, dp(12), BORDER));
        field.setSingleLine(!multiline);
        field.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (multiline) {
            field.setMinLines(2);
            field.setMaxLines(3);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        form.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void showBottomDialog(Dialog dialog, View content, int minHeight, int maxHeight) {
        FrameLayout shell = new FrameLayout(this);
        shell.setPadding(dp(10), 0, dp(10), dp(8));
        shell.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
        dialog.setContentView(shell);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setGravity(Gravity.BOTTOM);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int height = Math.max(minHeight, Math.min(maxHeight, screenHeight - dp(48)));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void persistSelectedEngine() {
        getSharedPreferences(SEARCH_PREFERENCES, MODE_PRIVATE).edit()
                .putString(SEARCH_ENGINE_PREFERENCE, selectedSearchEngine.id).apply();
    }

    private int categoryColor(SearchEngine.Category category) {
        if (category == SearchEngine.Category.ACCOUNT_REQUIRED) {
            return Color.rgb(154, 99, 30);
        }
        if (category == SearchEngine.Category.PAID) {
            return Color.rgb(126, 73, 168);
        }
        return Color.rgb(37, 126, 91);
    }

    private int categoryTint(SearchEngine.Category category) {
        if (category == SearchEngine.Category.ACCOUNT_REQUIRED) {
            return Color.rgb(255, 245, 225);
        }
        if (category == SearchEngine.Category.PAID) {
            return Color.rgb(246, 237, 253);
        }
        return Color.rgb(232, 247, 240);
    }

    private boolean looksLikeHost(String value) {
        if (value.isEmpty() || value.matches(".*\\s+.*")) {
            return false;
        }
        Uri uri = Uri.parse("https://" + value);
        String host = uri.getHost();
        return host != null && (host.contains(".") || "localhost".equalsIgnoreCase(host)
                || (host.startsWith("[") && host.endsWith("]")));
    }

    private boolean isWebUrl(Uri uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isWebUrlString(String url) {
        return url != null && !url.trim().isEmpty() && isWebUrl(Uri.parse(url));
    }

    private void loadUrl(String url) {
        BrowserTabRegistry.Tab tab = activeTab();
        if (tab != null) navigateTab(tab, url);
    }

    private void navigateTab(BrowserTabRegistry.Tab tab, String url) {
        if (!isWebUrl(Uri.parse(url))) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show();
            return;
        }
        BrowserTabSession session = browserSessions.get(tab.id);
        if (session == null) return;
        session.browser.setUserAgentOverride(siteMode.userAgentOverride());
        tab.url = url;
        tab.lastRequestedUrl = url;
        tab.showingHome = false;
        tab.failed = false;
        tab.errorMessage = null;
        tab.loading = true;
        tab.progress = 0;
        tab.canGoForward = false;
        if (tab == activeTab()) renderActiveTab();
        session.browser.loadUrl(url);
    }

    private void openNewTab() {
        createBrowserTab(null, null);
        renderActiveTab();
    }

    private void selectTab(String id) {
        if (tabRegistry.select(id)) renderActiveTab();
    }

    private void closeTab(String id) {
        BrowserTabSession session = browserSessions.remove(id);
        if (session == null && tabRegistry.find(id) == null) return;
        tabRegistry.close(id);
        if (session != null) closeBrowserSession(session);
        if (tabRegistry.size() == 0) createBrowserTab(null, null);
        renderActiveTab();
    }

    private void closeBrowserSession(BrowserTabSession session) {
        GeckoViewBrowserAdapter browser = session.browser;
        browser.stopLoading();
        View surface = browser.getSurfaceContainer();
        ViewGroup parent = (ViewGroup) surface.getParent();
        if (parent != null) parent.removeView(surface);
        browser.close();
    }

    private void renderActiveTab() {
        if (tabRegistry == null || contentFrame == null) return;
        BrowserTabRegistry.Tab active = activeTab();
        for (BrowserTabSession session : browserSessions.values()) {
            boolean visible = active != null && session.tab == active && !active.showingHome;
            session.browser.getSurfaceContainer().setVisibility(visible ? View.VISIBLE : View.GONE);
            session.browser.setPageVisible(visible);
        }
        boolean showHome = active == null || (active.showingHome && !active.failed);
        if (wallpaperBackdrop != null) wallpaperBackdrop.setVisibility(showHome ? View.VISIBLE : View.GONE);
        if (wallpaperScrim != null) wallpaperScrim.setVisibility(showHome ? View.VISIBLE : View.GONE);
        if (browserAddressBar != null) browserAddressBar.setVisibility(showHome ? View.GONE : View.VISIBLE);
        if (showHome) homeView.bringToFront();
        if (active != null && active.failed) errorPanel.bringToFront();
        homeView.setVisibility(showHome ? View.VISIBLE : View.GONE);
        errorPanel.setVisibility(active != null && active.failed ? View.VISIBLE : View.GONE);
        if (active != null) {
            errorMessage.setText(active.errorMessage == null
                    ? getString(R.string.network_error) : active.errorMessage);
            String address = active.showingHome ? "" : (active.url == null ? "" : active.url);
            if (!addressInput.hasFocus() && !address.equals(addressInput.getText().toString())) {
                addressInput.setText(address);
                addressInput.setSelection(addressInput.length());
            }
            if (active.loading) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(Math.max(0, Math.min(active.progress, 100)));
                progressBar.setVisibility(View.VISIBLE);
            } else {
                progressBar.setVisibility(View.GONE);
            }
        } else {
            addressInput.setText("");
            progressBar.setVisibility(View.GONE);
        }
        renderTabStrip();
    }

    private void showHome() {
        BrowserTabRegistry.Tab tab = activeTab();
        BrowserTabSession session = activeSession();
        if (tab == null) return;
        if (session != null && tab.loading) session.browser.stopLoading();
        tab.showingHome = true;
        tab.failed = false;
        tab.errorMessage = null;
        tab.loading = false;
        tab.progress = 0;
        renderActiveTab();
    }

    private void goBack() {
        BrowserTabRegistry.Tab tab = activeTab();
        GeckoViewBrowserAdapter browser = activeBrowser();
        if (tab != null && browser != null && !tab.showingHome && browser.canGoBack()) {
            browser.goBack();
        } else {
            showHome();
        }
    }

    private void goForward() {
        BrowserTabRegistry.Tab tab = activeTab();
        GeckoViewBrowserAdapter browser = activeBrowser();
        if (tab != null && browser != null && !tab.showingHome && tab.canGoForward) {
            browser.goForward();
        }
    }

    private void hideKeyboard(EditText input) {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        input.clearFocus();
    }

    private TextView label(String text, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        List<BrowserTabRegistry.Tab> openTabs = tabRegistry.all();
        String[] ids = new String[openTabs.size()];
        for (int i = 0; i < openTabs.size(); i++) {
            BrowserTabRegistry.Tab tab = openTabs.get(i);
            ids[i] = tab.id;
            outState.putString(STATE_TAB_URL_PREFIX + tab.id, tab.url);
            outState.putString(STATE_TAB_TITLE_PREFIX + tab.id, tab.title);
            outState.putBoolean(STATE_TAB_HOME_PREFIX + tab.id, tab.showingHome);
            BrowserTabSession session = browserSessions.get(tab.id);
            if (session != null) {
                byte[] navigationState = session.browser.serializeNavigationState();
                if (navigationState != null) {
                    outState.putByteArray(STATE_TAB_STATE_PREFIX + tab.id, navigationState);
                }
            }
        }
        outState.putStringArray(STATE_TAB_IDS, ids);
        BrowserTabRegistry.Tab selected = tabRegistry.selected();
        if (selected != null) outState.putString(STATE_SELECTED_TAB, selected.id);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        refreshDailyWallpaper();
        wallpaperRotationChecksActive = true;
        wallpaperRotationHandler.removeCallbacks(wallpaperRotationCheck);
        wallpaperRotationHandler.postDelayed(wallpaperRotationCheck, 60_000L);
        BrowserTabRegistry.Tab active = activeTab();
        for (BrowserTabSession session : browserSessions.values()) {
            session.browser.onResume();
            session.browser.setPageVisible(session.tab == active && !session.tab.showingHome);
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        wallpaperRotationChecksActive = false;
        wallpaperRotationHandler.removeCallbacks(wallpaperRotationCheck);
        for (BrowserTabSession session : browserSessions.values()) session.browser.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        BrowserTabRegistry.Tab tab = activeTab();
        GeckoViewBrowserAdapter browser = activeBrowser();
        if (tab != null && browser != null && !tab.showingHome && browser.canGoBack()) {
            browser.goBack();
        } else {
            showHome();
        }
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        dismissOverflowMenuSafely();
        if (searchEngineDialog != null && searchEngineDialog.isShowing()) {
            searchEngineDialog.dismiss();
        }
        if (tabSwitcherDialog != null && tabSwitcherDialog.isShowing()) tabSwitcherDialog.dismiss();
        if (settingsDialog != null && settingsDialog.isShowing()) settingsDialog.dismiss();
        if (pluginManagerDialog != null && pluginManagerDialog.isShowing()) {
            pluginManagerDialog.dismiss();
        }
        if (pluginEditorDialog != null && pluginEditorDialog.isShowing()) {
            pluginEditorDialog.dismiss();
        }
        if (extensionManagerDialog != null && extensionManagerDialog.isShowing()) {
            extensionManagerDialog.dismiss();
        }
        if (wallpaperDialog != null && wallpaperDialog.isShowing()) wallpaperDialog.dismiss();
        if (profileManagerDialog != null && profileManagerDialog.isShowing()) profileManagerDialog.dismiss();
        searchEngineFilterHandler.removeCallbacksAndMessages(null);
        searchEngineIconHandler.removeCallbacksAndMessages(null);
        searchEngineIconExecutor.shutdownNow();
        tabRegistry = new BrowserTabRegistry();
        for (BrowserTabSession session : new ArrayList<>(browserSessions.values())) {
            closeBrowserSession(session);
        }
        browserSessions.clear();
        super.onDestroy();
    }
}
