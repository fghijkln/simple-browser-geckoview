package com.cue.simplebrowser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** User-facing, explicitly started crawler UI. */
final class ControlledCrawlerDialog {
    private static final int INK = Color.rgb(31, 41, 63);
    private static final int SECONDARY = Color.rgb(113, 123, 143);
    private static final int ACCENT = Color.rgb(72, 101, 218);
    private static final int WHITE = Color.WHITE;
    private static final int BORDER = Color.rgb(226, 230, 238);

    private final Activity activity;
    private final ExecutorService executor;
    private final Consumer<String> openPage;
    private Dialog dialog;
    private ControlledCrawler activeCrawler;

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

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(14));
        sheet.setBackground(rounded(WHITE, dp(23), BORDER));
        TextView title = label(activity.getString(R.string.crawler_title), 18, INK, true);
        sheet.addView(title);

        ScrollView contentScroll = new ScrollView(activity);
        contentScroll.setFillViewport(false);
        contentScroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        addDisclosure(content, R.string.crawler_intro, 11, dp(8));
        addDisclosure(content, R.string.crawler_scope, 11, dp(8));
        addDisclosure(content, R.string.crawler_dns_note, 10, dp(8));

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
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        inputParams.topMargin = dp(12);
        content.addView(urlInput, inputParams);

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
            if (activeCrawler != null) activeCrawler.cancel();
            ControlledCrawler crawler = new ControlledCrawler();
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
            }));
        });
        close.setOnClickListener(view -> dismiss());
        thisDialog.setOnCancelListener(ignored -> cancelActive());
        thisDialog.setOnDismissListener(ignored -> cancelActive());
        showBottomDialog(thisDialog, sheet, dp(420), dp(760));
    }

    void dismiss() {
        cancelActive();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void cancelActive() {
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

    private void addResult(LinearLayout results, ControlledCrawler.Page page, Dialog parentDialog) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rounded(Color.rgb(249, 250, 252), dp(15), BORDER));
        card.addView(label(page.title, 14, INK, true));
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
