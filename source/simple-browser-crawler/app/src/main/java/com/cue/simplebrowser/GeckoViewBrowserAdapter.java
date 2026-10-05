package com.cue.simplebrowser;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.ExperimentalGeckoViewApi;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** GeckoView 157 backend; page content receives no application JavaScript bridge. */
final class GeckoViewBrowserAdapter {
    static final String QUAD9_DOH_URI = "https://dns.quad9.net/dns-query";
    private static final String WEBRTC_PEER_CONNECTION_PREF = "media.peerconnection.enabled";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<WeakReference<GeckoViewBrowserAdapter>> INSTANCES =
            new CopyOnWriteArrayList<>();

    private static GeckoRuntime runtime;
    private static Context applicationContext;
    private static String activeProfilePath;
    private static volatile boolean webRtcProtectionEnabled = true;
    private static volatile boolean webRtcPreferenceReady;
    private static volatile boolean javascriptFallback;
    private static boolean webRtcPolicyInitializationRequested;
    private static long webRtcPolicyGeneration;

    interface Callback {
        void onUrlChanged(String url);
        void onTitleChanged(String title);
        void onLoadingStateChanged(boolean loading, boolean canGoBack, boolean canGoForward);
    }

    interface RequestInterceptor {
        boolean shouldBlockRequest(String url, String method, boolean mainFrame);
    }

    interface LoadHandler {
        void onLoadStart(String url);
        void onLoadError(int errorCode, String failedUrl);
        void onLoadComplete(String url, boolean success);
    }

    interface PopupHandler {
        GeckoSession onPopup(String targetUrl, boolean userGesture);
    }

    interface DownloadHandler {
        void onDownload(WebResponse response);
    }

    interface PermissionPromptHandler {
        void onSystemPermissionBlocked(String permissionLabel, String origin);
        void onUnsupportedPermissionRequest(String origin);
        void requestSitePermission(String permissionLabel, String origin, Consumer<Boolean> decision);
    }

    interface RenderProcessTerminatedListener {
        void onTerminated(int status, int errorCode);
    }

    static final class WebRtcPolicyResult {
        final boolean protectedModeEnabled;
        final boolean nativePreferenceApplied;
        final boolean javascriptFallback;

        WebRtcPolicyResult(boolean protectedModeEnabled, boolean nativePreferenceApplied,
                           boolean javascriptFallback) {
            this.protectedModeEnabled = protectedModeEnabled;
            this.nativePreferenceApplied = nativePreferenceApplied;
            this.javascriptFallback = javascriptFallback;
        }
    }

    private final Context appContext;
    private final GeckoRuntime engineRuntime;
    private final GeckoSession session;
    private final GeckoView geckoView;
    private final FrameLayout surfaceContainer;
    private volatile String currentUrl;
    private volatile boolean canGoBack;
    private volatile boolean canGoForward;
    private volatile boolean pageVisible;
    private volatile String pendingUrl;
    private volatile boolean pendingPopupHasUserGesture;
    private volatile Callback callback;
    private volatile RequestInterceptor requestInterceptor;
    private volatile LoadHandler loadHandler;
    private volatile PopupHandler popupHandler;
    private volatile DownloadHandler downloadHandler;
    private volatile PermissionPromptHandler permissionPromptHandler;
    private volatile RenderProcessTerminatedListener renderProcessTerminatedListener;

    static void initializeRuntime(Context context, String profileDirectory,
                                  boolean webRtcProtectionEnabled,
                                  Consumer<WebRtcPolicyResult> callback) {
        ensureRuntime(context, profileDirectory);
        synchronized (GeckoViewBrowserAdapter.class) {
            webRtcPolicyInitializationRequested = true;
        }
        configureWebRtcProtection(context, webRtcProtectionEnabled, callback);
    }

    static GeckoViewBrowserAdapter createWithSurface(Context context) {
        Context applicationContext = context.getApplicationContext();
        GeckoRuntime engineRuntime = ensureRuntime(applicationContext, activeProfilePath);
        ensureWebRtcPolicyInitialized(applicationContext);

        ContentBlocking.Settings contentBlocking = new ContentBlocking.Settings.Builder()
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .strictSocialTrackingProtection(true)
                .queryParameterStrippingEnabled(true)
                .build();
        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .useTrackingProtection(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build();
        GeckoSession session = new GeckoSession(sessionSettings);
        GeckoView view = new GeckoView(context);
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GeckoViewBrowserAdapter adapter = new GeckoViewBrowserAdapter(applicationContext,
                engineRuntime, session, view, wrapper);
        adapter.installDelegates();
        session.open(engineRuntime);
        view.setSession(session);
        session.setActive(false);
        session.setFocused(false);
        INSTANCES.add(new WeakReference<>(adapter));
        return adapter;
    }

    private GeckoViewBrowserAdapter(Context appContext, GeckoRuntime engineRuntime,
                                    GeckoSession session, GeckoView geckoView,
                                    FrameLayout surfaceContainer) {
        this.appContext = appContext;
        this.engineRuntime = engineRuntime;
        this.session = session;
        this.geckoView = geckoView;
        this.surfaceContainer = surfaceContainer;
    }

    private static synchronized GeckoRuntime ensureRuntime(Context context, String profileDirectory) {
        if (profileDirectory == null || profileDirectory.trim().isEmpty()) {
            throw new IllegalStateException("An app-private Gecko profile directory is required");
        }
        if (runtime != null) {
            if (!profileDirectory.equals(activeProfilePath)) {
                throw new IllegalStateException("GeckoRuntime is already bound to another profile; restart the app to switch");
            }
            return runtime;
        }
        applicationContext = context.getApplicationContext();
        ContentBlocking.Settings contentBlocking = new ContentBlocking.Settings.Builder()
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .strictSocialTrackingProtection(true)
                .queryParameterStrippingEnabled(true)
                .build();
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .globalPrivacyControlEnabled(true)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                .consoleOutput(false)
                .debugLogging(false)
                .loginAutofillEnabled(false)
                .extensionsWebAPIEnabled(false)
                .contentBlocking(contentBlocking)
                .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                .trustedRecursiveResolverUri(QUAD9_DOH_URI)
                .arguments(new String[] {"-profile", profileDirectory})
                .build();
        runtime = GeckoRuntime.create(applicationContext, settings);
        activeProfilePath = profileDirectory;
        return runtime;
    }

    private static void ensureWebRtcPolicyInitialized(Context context) {
        synchronized (GeckoViewBrowserAdapter.class) {
            if (webRtcPolicyInitializationRequested) return;
            webRtcPolicyInitializationRequested = true;
        }
        configureWebRtcProtection(context, true, null);
    }

    @OptIn(markerClass = ExperimentalGeckoViewApi.class)
    static void configureWebRtcProtection(Context context, boolean enabled,
                                          Consumer<WebRtcPolicyResult> callback) {
        String profilePath;
        synchronized (GeckoViewBrowserAdapter.class) {
            profilePath = activeProfilePath;
        }
        GeckoRuntime ignored = ensureRuntime(context, profilePath);
        final long generation;
        synchronized (GeckoViewBrowserAdapter.class) {
            webRtcProtectionEnabled = enabled;
            webRtcPreferenceReady = false;
            generation = ++webRtcPolicyGeneration;
        }
        GeckoPreferenceController.setGeckoPref(WEBRTC_PEER_CONNECTION_PREF, !enabled,
                GeckoPreferenceController.PREF_BRANCH_USER)
                .accept(value -> finishWebRtcConfiguration(generation, enabled, true, callback),
                        error -> finishWebRtcConfiguration(generation, enabled, false, callback));
    }

    private static void finishWebRtcConfiguration(long generation, boolean requestedEnabled,
                                                  boolean nativePreferenceApplied,
                                                  Consumer<WebRtcPolicyResult> callback) {
        MAIN.post(() -> {
            final boolean currentEnabled;
            final boolean fallback;
            synchronized (GeckoViewBrowserAdapter.class) {
                if (generation != webRtcPolicyGeneration) return;
                currentEnabled = webRtcProtectionEnabled;
                fallback = currentEnabled && !nativePreferenceApplied;
                javascriptFallback = fallback;
                webRtcPreferenceReady = true;
            }
            applyWebRtcPolicyToInstances();
            WebRtcPolicyResult result = new WebRtcPolicyResult(currentEnabled,
                    nativePreferenceApplied, fallback);
            if (callback != null) callback.accept(result);
        });
    }

    private static void applyWebRtcPolicyToInstances() {
        boolean ready;
        boolean protectionEnabled;
        boolean fallback;
        synchronized (GeckoViewBrowserAdapter.class) {
            ready = webRtcPreferenceReady;
            protectionEnabled = webRtcProtectionEnabled;
            fallback = javascriptFallback;
        }
        if (!ready) return;
        for (WeakReference<GeckoViewBrowserAdapter> reference : INSTANCES) {
            GeckoViewBrowserAdapter adapter = reference.get();
            if (adapter == null) {
                INSTANCES.remove(reference);
                continue;
            }
            adapter.session.getSettings().setAllowJavascript(!protectionEnabled || !fallback);
            String queued = adapter.pendingUrl;
            if (queued != null) {
                adapter.pendingUrl = null;
                adapter.loadUrl(queued);
            }
        }
    }

    static boolean isJavaScriptFallback() {
        return javascriptFallback;
    }

    private void installDelegates() {
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(GeckoSession ignored, String[] permissions,
                    GeckoSession.PermissionDelegate.Callback permissionCallback) {
                if (!hasOnlySupportedAndroidPermissions(permissions)) {
                    PermissionPromptHandler handler = permissionPromptHandler;
                    if (handler != null) handler.onUnsupportedPermissionRequest(displayOrigin(currentUrl));
                    permissionCallback.reject();
                    return;
                }
                String missing = missingAndroidPermissionLabel(permissions);
                if (missing != null) {
                    notifySystemPermissionBlocked(missing, currentUrl);
                    permissionCallback.reject();
                    return;
                }
                permissionCallback.grant();
            }

            @OptIn(markerClass = ExperimentalGeckoViewApi.class)
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession ignored, GeckoSession.PermissionDelegate.ContentPermission permission) {
                if (permission.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                    PermissionPromptHandler handler = permissionPromptHandler;
                    if (handler != null) handler.onUnsupportedPermissionRequest(displayOrigin(permission.uri));
                    return GeckoResult.fromValue(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                }
                if (!hasLocationPermission()) {
                    notifySystemPermissionBlocked(appContext.getString(R.string.browser_permission_location),
                            permission.uri);
                    return GeckoResult.fromValue(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                }
                if (permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        || permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) {
                    return GeckoResult.fromValue(permission.value);
                }
                PermissionPromptHandler handler = permissionPromptHandler;
                if (handler == null) {
                    return GeckoResult.fromValue(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                }
                GeckoResult<Integer> result = new GeckoResult<>();
                permission.notifyShown();
                handler.requestSitePermission(appContext.getString(R.string.browser_permission_location),
                        displayOrigin(permission.uri), allowed -> result.complete(allowed
                                ? GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                : GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY));
                return result;
            }

            @Override
            public void onMediaPermissionRequest(GeckoSession ignored, String uri,
                    GeckoSession.PermissionDelegate.MediaSource[] video,
                    GeckoSession.PermissionDelegate.MediaSource[] audio,
                    GeckoSession.PermissionDelegate.MediaCallback mediaCallback) {
                GeckoSession.PermissionDelegate.MediaSource videoSource = firstMediaSource(video,
                        GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA);
                GeckoSession.PermissionDelegate.MediaSource audioSource = firstMediaSource(audio,
                        GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE);
                if ((video != null && videoSource == null) || (audio != null && audioSource == null)
                        || (video == null && audio == null)) {
                    PermissionPromptHandler handler = permissionPromptHandler;
                    if (handler != null) handler.onUnsupportedPermissionRequest(displayOrigin(uri));
                    mediaCallback.reject();
                    return;
                }
                if ((video != null && !appContext.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                        || (audio != null && !appContext.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_MICROPHONE))) {
                    PermissionPromptHandler handler = permissionPromptHandler;
                    if (handler != null) handler.onUnsupportedPermissionRequest(displayOrigin(uri));
                    mediaCallback.reject();
                    return;
                }
                String missing = missingMediaPermissionLabel(video != null, audio != null);
                if (missing != null) {
                    notifySystemPermissionBlocked(missing, uri);
                    mediaCallback.reject();
                    return;
                }
                PermissionPromptHandler handler = permissionPromptHandler;
                if (handler == null) {
                    mediaCallback.reject();
                    return;
                }
                String capability = video != null && audio != null
                        ? appContext.getString(R.string.browser_permission_camera_microphone)
                        : video != null ? appContext.getString(R.string.browser_permission_camera)
                        : appContext.getString(R.string.browser_permission_microphone);
                handler.requestSitePermission(capability, displayOrigin(uri), allowed -> {
                    if (allowed) mediaCallback.grant(videoSource, audioSource);
                    else mediaCallback.reject();
                });
            }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession ignored, String url,
                    java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean hasUserGesture) {
                currentUrl = url;
                Callback listener = callback;
                if (listener != null) listener.onUrlChanged(url);
            }

            @Override
            public void onCanGoBack(GeckoSession ignored, boolean value) {
                canGoBack = value;
                dispatchNavigationState();
            }

            @Override
            public void onCanGoForward(GeckoSession ignored, boolean value) {
                canGoForward = value;
                dispatchNavigationState();
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession ignored,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                if (request.target == TARGET_WINDOW_NEW) {
                    pendingPopupHasUserGesture = request.hasUserGesture;
                    return request.hasUserGesture ? GeckoResult.allow() : GeckoResult.deny();
                }
                return allowRequest(request.uri, true) ? GeckoResult.allow() : GeckoResult.deny();
            }

            @Override
            public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession ignored,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                return allowRequest(request.uri, false) ? GeckoResult.allow() : GeckoResult.deny();
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession ignored, String uri) {
                boolean userGesture = pendingPopupHasUserGesture;
                pendingPopupHasUserGesture = false;
                PopupHandler listener = popupHandler;
                if (!userGesture || listener == null || !isHttpUri(uri)) {
                    return GeckoResult.fromValue(null);
                }
                return GeckoResult.fromValue(listener.onPopup(uri, true));
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession ignored, String uri,
                                                   WebRequestError error) {
                LoadHandler listener = loadHandler;
                if (listener != null) listener.onLoadError(error == null ? WebRequestError.ERROR_UNKNOWN
                        : error.code, uri);
                return GeckoResult.fromValue(null);
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession ignored, String title) {
                Callback listener = callback;
                if (listener != null) listener.onTitleChanged(title);
            }

            @Override
            public void onExternalResponse(GeckoSession ignored, WebResponse response) {
                DownloadHandler listener = downloadHandler;
                if (listener != null) listener.onDownload(response);
            }

            @Override
            public void onCrash(GeckoSession ignored) {
                notifyRenderProcessTerminated();
            }

            @Override
            public void onKill(GeckoSession ignored) {
                notifyRenderProcessTerminated();
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession ignored, String url) {
                currentUrl = url;
                LoadHandler listener = loadHandler;
                if (listener != null) listener.onLoadStart(url);
                Callback callbackListener = callback;
                if (callbackListener != null) callbackListener.onLoadingStateChanged(
                        true, canGoBack, canGoForward);
            }

            @Override
            public void onPageStop(GeckoSession ignored, boolean success) {
                Callback listener = callback;
                if (listener != null) listener.onLoadingStateChanged(false, canGoBack, canGoForward);
                LoadHandler completedListener = loadHandler;
                if (completedListener != null) completedListener.onLoadComplete(currentUrl, success);
                if (!success) {
                    LoadHandler errorListener = loadHandler;
                    if (errorListener != null) errorListener.onLoadError(
                            WebRequestError.ERROR_UNKNOWN, currentUrl);
                }
            }
        });
    }

    private boolean allowRequest(String url, boolean mainFrame) {
        if (url == null || url.trim().isEmpty()) return false;
        if ("about:blank".equalsIgnoreCase(url)) return true;
        if (!isHttpUri(url)) return false;
        RequestInterceptor interceptor = requestInterceptor;
        return interceptor == null || !interceptor.shouldBlockRequest(url, "GET", mainFrame);
    }

    private static boolean isHttpUri(String value) {
        if (value == null) return false;
        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (RuntimeException ignored) {
            return false;
        }
        String scheme = uri.getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    private void dispatchNavigationState() {
        Callback listener = callback;
        if (listener != null) listener.onLoadingStateChanged(false, canGoBack, canGoForward);
    }

    private boolean hasAndroidPermission(String permission) {
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasAndroidPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                || hasAndroidPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private static boolean hasOnlySupportedAndroidPermissions(String[] permissions) {
        if (permissions == null) return true;
        for (String permission : permissions) {
            if (!android.Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)
                    && !android.Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                    && !android.Manifest.permission.CAMERA.equals(permission)
                    && !android.Manifest.permission.RECORD_AUDIO.equals(permission)) {
                return false;
            }
        }
        return true;
    }

    private String missingAndroidPermissionLabel(String[] permissions) {
        if (permissions == null || permissions.length == 0) return null;
        Set<String> missing = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (android.Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
                if (!hasAndroidPermission(permission)) {
                    missing.add(appContext.getString(R.string.browser_permission_location_approximate));
                }
            } else if (android.Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)) {
                if (!hasAndroidPermission(permission)) {
                    missing.add(appContext.getString(R.string.browser_permission_location_precise));
                }
            } else if (android.Manifest.permission.CAMERA.equals(permission)) {
                if (!hasAndroidPermission(permission)) {
                    missing.add(appContext.getString(R.string.browser_permission_camera));
                }
            } else if (android.Manifest.permission.RECORD_AUDIO.equals(permission)) {
                if (!hasAndroidPermission(permission)) {
                    missing.add(appContext.getString(R.string.browser_permission_microphone));
                }
            } else {
                missing.add(appContext.getString(R.string.browser_permission_unsupported));
            }
        }
        return missing.isEmpty() ? null : String.join("、", missing);
    }

    private String missingMediaPermissionLabel(boolean needsCamera, boolean needsMicrophone) {
        boolean cameraMissing = needsCamera && !hasAndroidPermission(android.Manifest.permission.CAMERA);
        boolean microphoneMissing = needsMicrophone
                && !hasAndroidPermission(android.Manifest.permission.RECORD_AUDIO);
        if (cameraMissing && microphoneMissing) {
            return appContext.getString(R.string.browser_permission_camera_microphone);
        }
        if (cameraMissing) return appContext.getString(R.string.browser_permission_camera);
        if (microphoneMissing) return appContext.getString(R.string.browser_permission_microphone);
        return null;
    }

    private static GeckoSession.PermissionDelegate.MediaSource firstMediaSource(
            GeckoSession.PermissionDelegate.MediaSource[] sources, int expectedSource) {
        if (sources == null) return null;
        for (GeckoSession.PermissionDelegate.MediaSource source : sources) {
            if (source != null && source.source == expectedSource) return source;
        }
        return null;
    }

    private void notifySystemPermissionBlocked(String permissionLabel, String uri) {
        PermissionPromptHandler handler = permissionPromptHandler;
        if (handler != null) handler.onSystemPermissionBlocked(permissionLabel, displayOrigin(uri));
    }

    private static String displayOrigin(String uriText) {
        if (uriText == null || uriText.trim().isEmpty()) return "未知网站";
        try {
            Uri uri = Uri.parse(uriText);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "未知网站";
            String displayHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            int port = uri.getPort();
            return port < 0 ? displayHost : displayHost + ":" + port;
        } catch (RuntimeException ignored) {
            return "未知网站";
        }
    }

    private void notifyRenderProcessTerminated() {
        RenderProcessTerminatedListener listener = renderProcessTerminatedListener;
        if (listener != null) listener.onTerminated(0, 0);
    }

    GeckoSession getGeckoSession() {
        return session;
    }

    void setJavaScriptEnabled(boolean enabled) {
        session.getSettings().setAllowJavascript(enabled);
    }

    void setPermissionHandler(PermissionPromptHandler handler) {
        permissionPromptHandler = handler;
    }

    FrameLayout getSurfaceContainer() {
        return surfaceContainer;
    }

    void setPageVisible(boolean visible) {
        pageVisible = visible;
        session.setActive(visible);
        session.setFocused(visible);
        geckoView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void setPinchToZoomEnabled(boolean enabled) {
        // GeckoView's PanZoomController provides native pinch zoom; no separate public toggle is available.
    }

    void setUserAgentOverride(String override) {
        boolean desktop = "gecko-desktop-mode".equals(override);
        session.getSettings().setUserAgentMode(desktop
                ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
    }

    byte[] serializeNavigationState() {
        return currentUrl == null ? new byte[0] : currentUrl.getBytes(StandardCharsets.UTF_8);
    }

    void restoreNavigationState(byte[] state) {
        if (state == null || state.length == 0) return;
        String restored = new String(state, StandardCharsets.UTF_8);
        if (isHttpUri(restored)) loadUrl(restored);
    }

    void setRequestInterceptor(RequestInterceptor interceptor) {
        requestInterceptor = interceptor;
    }

    void setPopupHandler(PopupHandler handler) {
        popupHandler = handler;
    }

    void setDownloadHandler(DownloadHandler handler) {
        downloadHandler = handler;
    }

    void setLoadHandler(LoadHandler handler) {
        loadHandler = handler;
    }

    void setCallback(Callback listener) {
        callback = listener;
    }

    void setOnRenderProcessTerminatedListener(RenderProcessTerminatedListener listener) {
        renderProcessTerminatedListener = listener;
    }

    boolean onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        return false;
    }

    String getUrl() {
        return currentUrl;
    }

    boolean canGoBack() {
        return canGoBack;
    }

    boolean canGoForward() {
        return canGoForward;
    }

    void loadUrl(String url) {
        if (!isHttpUri(url)) return;
        currentUrl = url;
        synchronized (GeckoViewBrowserAdapter.class) {
            if (!webRtcPreferenceReady) {
                pendingUrl = url;
                return;
            }
        }
        session.getSettings().setAllowJavascript(!webRtcProtectionEnabled || javascriptFallback);
        session.loadUri(url);
    }

    void reload() {
        if (session.isOpen()) session.reload();
    }

    void stopLoading() {
        if (session.isOpen()) session.stop();
    }

    void goBack() {
        if (session.isOpen() && canGoBack) session.goBack();
    }

    void goForward() {
        if (session.isOpen() && canGoForward) session.goForward();
    }

    void onResume() {
        if (pageVisible) session.setActive(true);
    }

    void onPause() {
        session.setActive(false);
    }

    void close() {
        try {
            geckoView.releaseSession();
        } catch (RuntimeException ignored) {
            // Closing an already-detached session is harmless.
        }
        if (session.isOpen()) session.close();
        INSTANCES.removeIf(reference -> reference.get() == null || reference.get() == this);
    }

    private void flushAndCloseForProfileSwitch() {
        try {
            if (session.isOpen()) session.flushSessionState();
        } catch (RuntimeException ignored) {
            // Runtime shutdown remains best effort; no session from this process survives the switch.
        }
        close();
    }

    static void shutdownForProfileSwitch() {
        final GeckoRuntime oldRuntime;
        synchronized (GeckoViewBrowserAdapter.class) {
            oldRuntime = runtime;
            runtime = null;
            activeProfilePath = null;
        }
        for (WeakReference<GeckoViewBrowserAdapter> reference : INSTANCES) {
            GeckoViewBrowserAdapter adapter = reference.get();
            if (adapter != null) adapter.flushAndCloseForProfileSwitch();
        }
        if (oldRuntime != null) oldRuntime.shutdown();
    }

    static void configureWebRtcProtection(boolean enabled,
                                          Consumer<WebRtcPolicyResult> callback) {
        Context context;
        synchronized (GeckoViewBrowserAdapter.class) {
            context = applicationContext;
        }
        if (context == null) throw new IllegalStateException("Gecko runtime is not initialized");
        configureWebRtcProtection(context, enabled, callback);
    }
}
