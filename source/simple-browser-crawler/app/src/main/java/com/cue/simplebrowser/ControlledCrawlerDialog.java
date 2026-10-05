package com.cue.simplebrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** User-started, serial crawler UI with persistent user-selected policy values. */
final class ControlledCrawlerDialog {
    private static final int INK = Color.rgb(31, 41, 63);
    private static final int SECONDARY = Color.rgb(113, 123, 143);
    private static final int ACCENT = Color.rgb(72, 101, 218);
    private static final int WHITE = Color.WHITE;
    private static final int BORDER = Color.rgb(226, 230, 238);
    private static final String PREFS = "simple-browser.preferences";
    private static final String PAGES = "crawler.page_limit.v2";
    private static final String QUEUE_LIMIT = "crawler.queue_limit.v3";
    private static final String DURATION = "crawler.duration_seconds.v2";
    private static final String BYTES_MIB = "crawler.page_mib.v2";
    private static final String DELAY_MS = "crawler.delay_ms.v2";
    private static final String RATE_LIMIT_WAIT_SECONDS = "crawler.rate_limit_wait_seconds.v3";
    private static final String REDIRECT_LIMIT = "crawler.redirect_limit.v3";
    private static final String ROBOTS_BYTES_KIB = "crawler.robots_bytes_kib.v3";
    private static final String ROBOTS = "crawler.robots_mode.v2";
    private static final String CROSS_SITE = "crawler.cross_site.v2";
    private static final String RESPONSE_PROMPT = "crawler.response_prompt.v2";

    private final Activity activity;
    private final ExecutorService executor;
    private final Consumer<String> openPage;
    private Dialog dialog;
    private ControlledCrawler activeCrawler;
    private Dialog activeDecisionDialog;
    private Consumer<Boolean> activeDecisionResponse;

    ControlledCrawlerDialog(Activity activity, ExecutorService executor, Consumer<String> openPage) {
        this.activity = activity;
        this.executor = executor;
        this.openPage = openPage;
    }

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new Dialog(activity);
        Dialog thisDialog = dialog;
        thisDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        thisDialog.setCanceledOnTouchOutside(true);

        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(23), BORDER));
        sheet.addView(label(activity.getString(R.string.crawler_title), 18, INK, true));

        ScrollView contentScroll = new ScrollView(activity);
        contentScroll.setFillViewport(false);
        contentScroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        addDisclosure(content, R.string.crawler_intro, 11, dp(8));
        addDisclosure(content, R.string.crawler_scope, 11, dp(8));
        addDisclosure(content, R.string.crawler_dns_note, 10, dp(8));
        addDisclosure(content, R.string.crawler_limits_note, 10, dp(8));

        EditText urlInput = new EditText(activity);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14);
        urlInput.setTextColor(INK);
        urlInput.setHintTextColor(SECONDARY);
        urlInput.setHint(R.string.crawler_url_hint);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setBackground(rounded(Color.rgb(249, 250, 252), dp(13), BORDER));
        urlInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        addField(content, R.string.crawler_url_label, urlInput);

        EditText pageLimit = numericField(preferences.getInt(PAGES, ControlledCrawler.MAX_PAGES));
        EditText queueLimit = numericField(preferences.getInt(QUEUE_LIMIT,
                ControlledCrawler.DEFAULT_QUEUE_LIMIT));
        EditText durationSeconds = numericField(preferences.getLong(DURATION,
                ControlledCrawler.MAX_CRAWL_DURATION_MS / 1000L));
        EditText pageMib = numericField(preferences.getLong(BYTES_MIB,
                ControlledCrawler.DEFAULT_BODY_BYTES / (1024L * 1024L)));
        EditText delayMillis = numericField(preferences.getLong(DELAY_MS, ControlledCrawler.MIN_REQUEST_GAP_MS));
        EditText rateLimitWaitSeconds = numericField(preferences.getLong(RATE_LIMIT_WAIT_SECONDS,
                ControlledCrawler.DEFAULT_RATE_LIMIT_WAIT_MS / 1000L));
        EditText redirectLimit = numericField(preferences.getInt(REDIRECT_LIMIT,
                ControlledCrawler.DEFAULT_REDIRECT_LIMIT));
        EditText robotsBytesKib = numericField(preferences.getLong(ROBOTS_BYTES_KIB,
                ControlledCrawler.DEFAULT_ROBOTS_BYTES / 1024L));
        addField(content, R.string.crawler_page_limit_label, pageLimit);
        addField(content, R.string.crawler_queue_limit_label, queueLimit);
        addField(content, R.string.crawler_duration_label, durationSeconds);
        addField(content, R.string.crawler_page_bytes_label, pageMib);
        addField(content, R.string.crawler_delay_label, delayMillis);
        addField(content, R.string.crawler_rate_limit_wait_label, rateLimitWaitSeconds);
        addField(content, R.string.crawler_redirect_limit_label, redirectLimit);
        addField(content, R.string.crawler_robots_bytes_label, robotsBytesKib);

        TextView robotsLabel = label(activity.getString(R.string.crawler_robots_label), 12, INK, true);
        LinearLayout.LayoutParams robotsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        robotsParams.topMargin = dp(9);
        content.addView(robotsLabel, robotsParams);
        RadioGroup robotsGroup = new RadioGroup(activity);
        robotsGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton respect = radio(R.string.crawler_robots_respect);
        respect.setId(View.generateViewId());
        RadioButton askEach = radio(R.string.crawler_robots_ask);
        askEach.setId(View.generateViewId());
        RadioButton ignore = radio(R.string.crawler_robots_ignore);
        ignore.setId(View.generateViewId());
        robotsGroup.addView(respect);
        robotsGroup.addView(askEach);
        robotsGroup.addView(ignore);
        String savedRobots = preferences.getString(ROBOTS, ControlledCrawler.RobotsMode.RESPECT.name());
        robotsGroup.check(ControlledCrawler.RobotsMode.IGNORE.name().equals(savedRobots) ? ignore.getId()
                : ControlledCrawler.RobotsMode.ASK_EACH_BLOCKED.name().equals(savedRobots)
                ? askEach.getId() : respect.getId());
        content.addView(robotsGroup);

        SwitchCompat crossSite = toggle(R.string.crawler_cross_site_toggle,
                preferences.getBoolean(CROSS_SITE, false));
        SwitchCompat promptResponses = toggle(R.string.crawler_response_toggle,
                preferences.getBoolean(RESPONSE_PROMPT, true));
        content.addView(crossSite);
        content.addView(promptResponses);

        TextView status = label(activity.getString(R.string.crawler_empty), 11, SECONDARY, false);
        status.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        content.addView(status, statusParams);
        ProgressBar progress = new ProgressBar(activity);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.topMargin = dp(6);
        content.addView(progress, progressParams);
        LinearLayout results = new LinearLayout(activity);
        results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resultsParams.topMargin = dp(8);
        content.addView(results, resultsParams);
        contentScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(8);
        sheet.addView(contentScroll, scrollParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        actionsParams.topMargin = dp(10);
        TextView start = label(activity.getString(R.string.crawler_start), 13, WHITE, true);
        start.setGravity(Gravity.CENTER);
        start.setBackground(rounded(ACCENT, dp(14), ACCENT));
        start.setFocusable(true);
        actions.addView(start, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        TextView close = label(activity.getString(R.string.crawler_close), 13, SECONDARY, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.rgb(249, 250, 252), dp(14), BORDER));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(94),
                ViewGroup.LayoutParams.MATCH_PARENT);
        closeParams.leftMargin = dp(8);
        actions.addView(close, closeParams);
        sheet.addView(actions, actionsParams);

        start.setOnClickListener(view -> {
            String target = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();
            if (target.isEmpty()) {
                status.setText(R.string.crawler_url_hint);
                return;
            }
            final ControlledCrawler.Configuration configuration;
            try {
                int pages = parseInt(pageLimit, R.string.crawler_page_limit_label);
                int queuedUrls = parseInt(queueLimit, R.string.crawler_queue_limit_label);
                long seconds = parseLong(durationSeconds, R.string.crawler_duration_label);
                long mib = parseLong(pageMib, R.string.crawler_page_bytes_label);
                long delay = parseLong(delayMillis, R.string.crawler_delay_label);
                long retryWaitSeconds = parseLong(rateLimitWaitSeconds, R.string.crawler_rate_limit_wait_label);
                int redirects = parseInt(redirectLimit, R.string.crawler_redirect_limit_label);
                long robotsKib = parseLong(robotsBytesKib, R.string.crawler_robots_bytes_label);
                if (pages < 0 || queuedUrls < 0 || seconds < 0
                        || seconds > Long.MAX_VALUE / 1000L || mib < 0
                        || mib > Long.MAX_VALUE / (1024L * 1024L) || delay < 0
                        || retryWaitSeconds < 0 || retryWaitSeconds > Long.MAX_VALUE / 1000L
                        || redirects < 0 || robotsKib < 0 || robotsKib > Long.MAX_VALUE / 1024L) {
                    throw new IllegalArgumentException(activity.getString(R.string.crawler_values_invalid));
                }
                ControlledCrawler.RobotsMode robotsMode = robotsGroup.getCheckedRadioButtonId() == ignore.getId()
                        ? ControlledCrawler.RobotsMode.IGNORE
                        : robotsGroup.getCheckedRadioButtonId() == askEach.getId()
                        ? ControlledCrawler.RobotsMode.ASK_EACH_BLOCKED : ControlledCrawler.RobotsMode.RESPECT;
                configuration = new ControlledCrawler.Configuration(pages, queuedUrls,
                        seconds * 1000L, mib * 1024L * 1024L, delay, retryWaitSeconds * 1000L,
                        redirects, robotsKib * 1024L,
                        robotsMode, crossSite.isChecked(), promptResponses.isChecked());
                if (!preferences.edit().putInt(PAGES, pages).putInt(QUEUE_LIMIT, queuedUrls)
                        .putLong(DURATION, seconds).putLong(BYTES_MIB, mib).putLong(DELAY_MS, delay)
                        .putLong(RATE_LIMIT_WAIT_SECONDS, retryWaitSeconds)
                        .putInt(REDIRECT_LIMIT, redirects).putLong(ROBOTS_BYTES_KIB, robotsKib)
                        .putString(ROBOTS, robotsMode.name()).putBoolean(CROSS_SITE, crossSite.isChecked())
                        .putBoolean(RESPONSE_PROMPT, promptResponses.isChecked()).commit()) {
                    status.setText(R.string.crawler_settings_save_failed);
                    return;
                }
            } catch (IllegalArgumentException error) {
                status.setText(error.getMessage() == null
                        ? activity.getString(R.string.crawler_values_invalid) : error.getMessage());
                return;
            }
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.crawler_start_warning_title)
                    .setMessage(R.string.crawler_start_warning)
                    .setPositiveButton(R.string.crawler_start_confirm, (warning, which) -> startCrawl(
                            target, configuration, thisDialog, start, close, progress, status, results))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        close.setOnClickListener(view -> dismiss());
        thisDialog.setOnCancelListener(ignored -> cancelActive());
        thisDialog.setOnDismissListener(ignored -> cancelActive());
        showBottomDialog(thisDialog, sheet, dp(520), dp(820));
    }

    private void startCrawl(String target, ControlledCrawler.Configuration configuration, Dialog thisDialog,
                            TextView start, TextView close, ProgressBar progress, TextView status,
                            LinearLayout results) {
        if (activeCrawler != null) activeCrawler.cancel();
        ControlledCrawler crawler = new ControlledCrawler(
                url -> (java.net.HttpURLConnection) url.openConnection(),
                ControlledCrawler::validatePublicHttpsTarget, configuration);
        activeCrawler = crawler;
        start.setEnabled(false);
        start.setAlpha(0.55f);
        close.setText(R.string.crawler_cancel);
        progress.setVisibility(View.VISIBLE);
        results.removeAllViews();
        status.setText(R.string.crawler_checking_robots);
        executor.execute(() -> crawler.crawl(target, new ControlledCrawler.Listener() {
            @Override public void onStatus(String message) {
                activity.runOnUiThread(() -> {
                    if (activeCrawler == crawler && thisDialog.isShowing()) status.setText(message);
                });
            }
            @Override public void onPage(ControlledCrawler.Page page) {
                activity.runOnUiThread(() -> {
                    if (activeCrawler == crawler && thisDialog.isShowing()) addResult(results, page, thisDialog);
                });
            }
            @Override public void onFinished(ControlledCrawler.Finish finish) {
                activity.runOnUiThread(() -> {
                    if (activeCrawler != crawler) return;
                    activeCrawler = null;
                    if (!thisDialog.isShowing()) return;
                    progress.setVisibility(View.GONE);
                    start.setEnabled(true);
                    start.setAlpha(1f);
                    close.setText(R.string.crawler_close);
                    status.setText(finish.message);
                });
            }
            @Override public void onDecision(ControlledCrawler.Decision prompt, Consumer<Boolean> decision) {
                activity.runOnUiThread(() -> showDecision(prompt, decision, crawler, thisDialog));
            }
        }));
    }

    private void showDecision(ControlledCrawler.Decision prompt, Consumer<Boolean> decision,
                              ControlledCrawler crawler, Dialog parent) {
        if (activeCrawler != crawler || !parent.isShowing() || activity.isFinishing()) {
            decision.accept(false);
            return;
        }
        boolean[] resolved = {false};
        Consumer<Boolean> answer = allowed -> {
            if (resolved[0]) return;
            resolved[0] = true;
            activeDecisionResponse = null;
            activeDecisionDialog = null;
            decision.accept(Boolean.TRUE.equals(allowed));
        };
        activeDecisionResponse = answer;
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(prompt.allowLabel, (dialog, which) -> answer.accept(true));
        if (prompt.openInBrowserUrl == null) {
            builder.setNegativeButton(prompt.denyLabel, (dialog, which) -> answer.accept(false));
        } else {
            builder.setNegativeButton(prompt.denyLabel, (dialog, which) -> {
                answer.accept(false);
                parent.dismiss();
                openPage.accept(prompt.openInBrowserUrl);
            });
        }
        android.app.AlertDialog promptDialog = builder
                .setOnCancelListener(ignored -> answer.accept(false))
                .create();
        activeDecisionDialog = promptDialog;
        promptDialog.setOnDismissListener(ignored -> {
            if (!resolved[0]) answer.accept(false);
        });
        promptDialog.show();
    }

    void dismiss() {
        cancelActive();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void cancelActive() {
        Consumer<Boolean> pendingDecision = activeDecisionResponse;
        Dialog pendingDialog = activeDecisionDialog;
        activeDecisionResponse = null;
        activeDecisionDialog = null;
        if (pendingDecision != null) pendingDecision.accept(false);
        if (pendingDialog != null && pendingDialog.isShowing()) pendingDialog.dismiss();
        ControlledCrawler crawler = activeCrawler;
        activeCrawler = null;
        if (crawler != null) crawler.cancel();
    }

    private void addDisclosure(LinearLayout parent, int textId, int textSize, int topMargin) {
        TextView text = label(activity.getString(textId), textSize, SECONDARY, false);
        text.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        parent.addView(text, params);
    }

    private void addField(LinearLayout parent, int labelId, EditText input) {
        TextView label = label(activity.getString(labelId), 11, SECONDARY, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(9);
        parent.addView(label, labelParams);
        input.setSingleLine(true);
        input.setTextSize(13);
        input.setTextColor(INK);
        input.setHintTextColor(SECONDARY);
        input.setBackground(rounded(Color.rgb(249, 250, 252), dp(11), BORDER));
        input.setPadding(dp(10), dp(5), dp(10), dp(5));
        parent.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private EditText numericField(long value) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        field.setText(String.valueOf(value));
        return field;
    }

    private RadioButton radio(int labelId) {
        RadioButton button = new RadioButton(activity);
        button.setText(labelId);
        button.setTextColor(INK);
        button.setTextSize(12);
        return button;
    }

    private SwitchCompat toggle(int labelId, boolean checked) {
        SwitchCompat toggle = new SwitchCompat(activity);
        toggle.setText(labelId);
        toggle.setTextColor(INK);
        toggle.setTextSize(12);
        toggle.setChecked(checked);
        return toggle;
    }

    private int parseInt(EditText input, int labelId) {
        long value = parseLong(input, labelId);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(activity.getString(R.string.crawler_values_invalid));
        }
        return (int) value;
    }

    private long parseLong(EditText input, int labelId) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(activity.getString(R.string.crawler_value_invalid,
                    activity.getString(labelId)));
        }
    }

    private void addResult(LinearLayout results, ControlledCrawler.Page page, Dialog parentDialog) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rounded(Color.rgb(249, 250, 252), dp(15), BORDER));
        card.addView(label(page.title, 14, INK, true));
        if (page.serverResponseOnly) {
            TextView responseNote = label(activity.getString(R.string.crawler_response_only_note),
                    10, SECONDARY, false);
            responseNote.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams responseNoteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            responseNoteParams.topMargin = dp(5);
            card.addView(responseNote, responseNoteParams);
        }
        TextView sourceLabel = label(activity.getString(R.string.crawler_source_label), 10, SECONDARY, true);
        LinearLayout.LayoutParams sourceLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceLabelParams.topMargin = dp(6);
        card.addView(sourceLabel, sourceLabelParams);
        TextView source = label(page.url, 10, ACCENT, false);
        source.setTextIsSelectable(true);
        card.addView(source);
        TextView summaryLabel = label(activity.getString(R.string.crawler_summary_label), 10, SECONDARY, true);
        LinearLayout.LayoutParams summaryLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLabelParams.topMargin = dp(6);
        card.addView(summaryLabel, summaryLabelParams);
        TextView summary = label(page.summary, 12, INK, false);
        summary.setLineSpacing(dp(2), 1f);
        card.addView(summary);
        TextView open = label(activity.getString(R.string.crawler_open_source), 11, ACCENT, true);
        open.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        open.setPadding(0, dp(8), 0, 0);
        open.setFocusable(true);
        open.setOnClickListener(view -> {
            parentDialog.dismiss();
            openPage.accept(page.url);
        });
        card.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        results.addView(card, cardParams);
    }

    private void showBottomDialog(Dialog dialog, View content, int minHeight, int maxHeight) {
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.34f;
        attributes.width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(24), dp(520));
        attributes.height = Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(48), maxHeight);
        attributes.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        window.setAttributes(attributes);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        window.setLayout(attributes.width, Math.min(attributes.height, Math.max(minHeight,
                content.getMeasuredHeight())));
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
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
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
