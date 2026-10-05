package com.cue.simplebrowser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/** Recycled ListView rows for the engine picker; no row views are built during filtering. */
final class SearchEnginePickerAdapter extends BaseAdapter {
    interface IconBinder {
        void bind(SearchEngine engine, ImageView target);
    }

    private static final int INK = Color.rgb(31, 41, 63);
    private static final int SECONDARY = Color.rgb(113, 123, 143);
    private static final int ACCENT = Color.rgb(72, 101, 218);
    private static final int BORDER = Color.rgb(226, 230, 238);
    private static final int WHITE = Color.WHITE;

    private final Context context;
    private final IconBinder iconBinder;
    private List<SearchEngine> engines = Collections.emptyList();
    private List<SearchEngine> filtered = Collections.emptyList();
    private SearchEngine.Category accessCategory;
    private String featureCategory = "";
    private String query = "";
    private String selectedId;

    SearchEnginePickerAdapter(Context context, List<SearchEngine> engines, String selectedId,
                              IconBinder iconBinder) {
        this.context = context;
        this.selectedId = selectedId;
        this.iconBinder = iconBinder;
        setEngines(engines);
    }

    void setEngines(List<SearchEngine> replacement) {
        engines = replacement == null ? Collections.emptyList() : replacement;
        applyFilter();
    }

    List<SearchEngine> getEngines() {
        return engines;
    }

    void setSelectedId(String id) {
        if (id == null ? selectedId != null : !id.equals(selectedId)) {
            selectedId = id;
            notifyDataSetChanged();
        }
    }

    void setFilters(SearchEngine.Category accessCategory, String featureCategory, String rawQuery) {
        this.accessCategory = accessCategory;
        this.featureCategory = featureCategory == null ? "" : featureCategory;
        this.query = rawQuery == null ? "" : rawQuery;
        applyFilter();
    }

    private void applyFilter() {
        filtered = SearchEngineCatalog.filter(engines, accessCategory, featureCategory, query);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return filtered.size();
    }

    @Override
    public SearchEngine getItem(int position) {
        return filtered.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        RowHolder holder;
        if (convertView == null) {
            holder = createRow();
            convertView = holder.row;
            convertView.setTag(holder);
        } else {
            holder = (RowHolder) convertView.getTag();
        }
        SearchEngine engine = getItem(position);
        boolean selected = engine.id.equals(selectedId);
        holder.row.setBackground(background(selected));
        holder.row.setContentDescription(engine.name + "，" + engine.category.label + "，"
                + engine.pdfPrimaryCategory + "，" + engine.pdfSecondaryCategory + "，"
                + join(engine.functionalSubcategories) + "。" + engine.purpose + "。"
                + engine.accessNote + "。" + accessWarning(engine)
                + (engine.urlTemplate.isEmpty() ? "。暂无可执行搜索模板" : ""));
        holder.name.setText(engine.name);
        holder.name.setTextColor(selected ? ACCENT : INK);
        String details = join(engine.functionalSubcategories);
        if (details.isEmpty()) details = engine.featureCategory;
        if (engine.urlTemplate.isEmpty()) details += " · 无可执行模板";
        details += " · " + engine.purpose;
        holder.details.setText(details);
        String warning = accessWarning(engine);
        holder.warning.setText(warning);
        holder.warning.setVisibility(warning.isEmpty() ? View.GONE : View.VISIBLE);
        holder.check.setText(selected ? "✓" : "");
        holder.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        if (iconBinder != null) {
            iconBinder.bind(engine, holder.icon);
        }
        return convertView;
    }

    private RowHolder createRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setMinimumHeight(dp(64));
        row.setFocusable(false);
        row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        icon.setBackground(background(false));
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(12);
        copyParams.rightMargin = dp(7);
        row.addView(copy, copyParams);

        TextView name = textView(14, INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(name);

        TextView details = textView(12, SECONDARY, false);
        details.setMaxLines(2);
        details.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(details);

        TextView warning = textView(10, Color.rgb(155, 93, 20), false);
        warning.setSingleLine(true);
        warning.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(warning);

        TextView check = textView(16, ACCENT, true);
        check.setGravity(Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(30)));
        return new RowHolder(row, icon, name, details, warning, check);
    }

    private TextView textView(float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private GradientDrawable background(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? Color.rgb(248, 250, 255) : WHITE);
        drawable.setCornerRadius(dp(15));
        drawable.setStroke(dp(1), selected ? ACCENT : BORDER);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) continue;
                if (result.length() > 0) result.append(" / ");
                result.append(value.trim());
            }
        }
        return result.toString();
    }

    private static String accessWarning(SearchEngine engine) {
        String assessment = engine.accessAssessment == null ? "" : engine.accessAssessment;
        if (assessment.contains("deployment_required")) {
            return "需自建/部署；不是公开搜索服务（不等于已证实收费）";
        }
        if (assessment.contains("uncertain_cost")) {
            return "API 凭据/费用未确认；暂列非免费类别";
        }
        if (assessment.contains("api_or") || engine.pdfPrimaryCategory.contains("API与企业检索")) {
            return "API/服务配置或额度要求见访问提示";
        }
        if (assessment.startsWith("uncertain_") || assessment.startsWith("availability_or_")
                || assessment.startsWith("fee_and_access_")) {
            return "费用/可用性未确认；暂列非免费类别，不代表已证实收费";
        }
        if (assessment.contains("paid_required_for_core_service")) {
            return "核心服务需付费/订阅；具体方案见访问提示";
        }
        if (assessment.contains("account_or_credential_gate")) {
            return "需要账号或凭据；具体限制见访问提示";
        }
        if (engine.category == SearchEngine.Category.FREE
                && "可能付费".equals(engine.pdfFeeClassification)) {
            return "仅核心入口按免费分类；附加功能/API 费用可能另计";
        }
        return "";
    }

    private static final class RowHolder {
        final LinearLayout row;
        final ImageView icon;
        final TextView name;
        final TextView details;
        final TextView warning;
        final TextView check;

        RowHolder(LinearLayout row, ImageView icon, TextView name, TextView details,
                  TextView warning, TextView check) {
            this.row = row;
            this.icon = icon;
            this.name = name;
            this.details = details;
            this.warning = warning;
            this.check = check;
        }
    }
}
