package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MarkdownTreeViewHelper {
    public interface SectionSelectionListener {
        void onSelected(MarkdownSectionParser.Section section);
    }

    private MarkdownTreeViewHelper() {
    }

    public static LinearLayout create(final Context context,
            final List<MarkdownSectionParser.Section> sections,
            final SectionSelectionListener listener) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final Set<Integer> collapsed = new HashSet<>();
        final Set<Integer> expandedBullets = new HashSet<>();
        final TextView toggle = new TextView(context);
        toggle.setText("Collapse All");
        toggle.setTextSize(12);
        toggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toggle.setTextColor(AiUiTheme.ACCENT);
        toggle.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
        toggle.setBackground(AiUiTheme.rounded(AiUiTheme.SECONDARY,
                dp(context, 16), AiUiTheme.ACCENT, dp(context, 1)));
        toggle.setClickable(true);
        toggle.setFocusable(true);
        final LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(0, 0, 0, dp(context, 10));
        root.addView(toggle, toggleParams);

        final LinearLayout tree = new LinearLayout(context);
        tree.setOrientation(LinearLayout.VERTICAL);
        root.addView(tree, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toggle.setOnClickListener(view -> {
            if (collapsed.isEmpty()) {
                for (MarkdownSectionParser.Section section : sections) {
                    if (section.hasChildren()) {
                        collapsed.add(section.getIndex());
                    }
                }
                expandedBullets.clear();
                toggle.setText("Expand All");
            } else {
                collapsed.clear();
                expandedBullets.clear();
                toggle.setText("Collapse All");
            }
            render(context, tree, sections, listener, collapsed, expandedBullets);
        });
        render(context, tree, sections, listener, collapsed, expandedBullets);
        return root;
    }

    private static void render(final Context context, final LinearLayout tree,
            final List<MarkdownSectionParser.Section> sections,
            final SectionSelectionListener listener, final Set<Integer> collapsed,
            final Set<Integer> expandedBullets) {
        tree.removeAllViews();
        for (MarkdownSectionParser.Section section : sections) {
            if (!isVisible(section, sections, collapsed)) {
                continue;
            }
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(context, 48));
            final int level = Math.min(section.getLevel(), 4);
            final int cardColor = level == 1 ? Color.rgb(24, 24, 31)
                    : level == 2 ? Color.rgb(20, 20, 26) : Color.rgb(17, 17, 22);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                    AiUiTheme.rounded(cardColor, dp(context, 10),
                            level == 1 ? AiUiTheme.ACCENT : AiUiTheme.DIVIDER, dp(context, 1)), null));

            final int indent = level == 1 ? 0 : level == 2 ? dp(context, 16)
                    : level == 3 ? dp(context, 32) : dp(context, 44);
            row.setPadding(indent + dp(context, 8), dp(context, 6),
                    dp(context, 8), dp(context, 6));

            if (level == 1) {
                final View accent = new View(context);
                accent.setBackgroundColor(AiUiTheme.ACCENT);
                row.addView(accent, new LinearLayout.LayoutParams(
                        dp(context, 3), dp(context, 36)));
            } else if (level == 2) {
                final View connector = new View(context);
                connector.setBackgroundColor(Color.rgb(58, 32, 38));
                row.addView(connector, new LinearLayout.LayoutParams(
                        dp(context, 2), dp(context, 36)));
            }

            if (section.hasChildren()) {
                final TextView chevron = new TextView(context);
                chevron.setText(collapsed.contains(section.getIndex()) ? "▸" : "▾");
                chevron.setTextColor(AiUiTheme.ACCENT);
                chevron.setTextSize(16);
                chevron.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                chevron.setGravity(Gravity.CENTER);
                chevron.setClickable(true);
                chevron.setFocusable(true);
                chevron.setOnClickListener(view -> {
                    if (collapsed.contains(section.getIndex())) {
                        collapsed.remove(section.getIndex());
                    } else {
                        collapsed.add(section.getIndex());
                    }
                    render(context, tree, sections, listener, collapsed, expandedBullets);
                });
                row.addView(chevron, new LinearLayout.LayoutParams(
                        dp(context, 24), dp(context, 36)));
            } else {
                final View chevronSpace = new View(context);
                row.addView(chevronSpace, new LinearLayout.LayoutParams(dp(context, 24),
                        dp(context, 36)));
            }

            if (level >= 3) {
                final TextView branch = new TextView(context);
                branch.setText("└─");
                branch.setTextColor(Color.rgb(58, 32, 38));
                branch.setTextSize(12);
                branch.setTypeface(Typeface.MONOSPACE);
                row.addView(branch, new LinearLayout.LayoutParams(
                        dp(context, 24), ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            final TextView title = new TextView(context);
            title.setText(section.getTitle());
            title.setTextSize(level == 1 ? 14.5f : level == 2 ? 13.5f : 13);
            title.setTextColor(level == 1 ? Color.WHITE
                    : level == 2 ? Color.rgb(232, 232, 238) : Color.rgb(188, 188, 200));
            title.setTypeface(Typeface.DEFAULT, level <= 2 ? Typeface.BOLD : Typeface.NORMAL);
            title.setClickable(true);
            title.setFocusable(true);
            title.setOnClickListener(view -> listener.onSelected(section));
            final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            titleParams.setMargins(dp(context, 4), 0, dp(context, 6), 0);
            row.addView(title, titleParams);

            final TextView badge = new TextView(context);
            badge.setText("H" + level);
            badge.setTextSize(10);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setTextColor(level == 1 ? AiUiTheme.ACCENT : Color.rgb(188, 188, 200));
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(context, 7), dp(context, 3), dp(context, 7), dp(context, 3));
            badge.setBackground(AiUiTheme.rounded(level == 1 ? Color.rgb(58, 32, 38)
                            : level == 2 ? Color.rgb(42, 20, 24) : Color.rgb(28, 28, 34),
                    dp(context, 10), 0, 0));
            row.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(context, 7));

            if (section.getBulletCount() > 0) {
                final boolean bulletsExpanded = expandedBullets.contains(section.getIndex());
                final TextView bulletToggle = new TextView(context);
                bulletToggle.setText((bulletsExpanded ? "▾" : "▸") + " •"
                        + section.getBulletCount());
                bulletToggle.setTextSize(10);
                bulletToggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                bulletToggle.setTextColor(bulletsExpanded
                        ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY_TEXT);
                bulletToggle.setGravity(Gravity.CENTER);
                bulletToggle.setPadding(dp(context, 8), dp(context, 4),
                        dp(context, 8), dp(context, 4));
                bulletToggle.setBackground(AiUiTheme.rounded(AiUiTheme.SURFACE,
                        dp(context, 14), bulletsExpanded
                                ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY_BORDER, dp(context, 1)));
                bulletToggle.setContentDescription((bulletsExpanded
                        ? "Collapse " : "Expand ") + section.getBulletCount()
                        + " bullet points under " + section.getTitle());
                bulletToggle.setClickable(true);
                bulletToggle.setFocusable(true);
                bulletToggle.setOnClickListener(view -> {
                    if (bulletsExpanded) {
                        expandedBullets.remove(section.getIndex());
                    } else {
                        expandedBullets.add(section.getIndex());
                    }
                    render(context, tree, sections, listener, collapsed, expandedBullets);
                });
                final LinearLayout.LayoutParams bulletToggleParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bulletToggleParams.setMargins(dp(context, 6), 0, 0, 0);
                row.addView(bulletToggle, bulletToggleParams);
            }

            tree.addView(row, rowParams);
            if (section.getBulletCount() > 0
                    && expandedBullets.contains(section.getIndex())) {
                for (MarkdownSectionParser.Section bullet : section.getBulletItems()) {
                    addBulletCard(context, tree, section, bullet, listener);
                }
            }
        }
    }

    private static void addBulletCard(final Context context, final LinearLayout tree,
            final MarkdownSectionParser.Section heading,
            final MarkdownSectionParser.Section bullet,
            final SectionSelectionListener listener) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 38));
        row.setPadding(dp(context, 24 + Math.min(heading.getLevel() - 1, 3) * 12),
                dp(context, 5), dp(context, 10), dp(context, 5));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                AiUiTheme.rounded(Color.rgb(18, 18, 24), dp(context, 8),
                        AiUiTheme.DIVIDER, dp(context, 1)), null));

        final TextView dot = new TextView(context);
        dot.setText("•");
        dot.setTextColor(AiUiTheme.ACCENT);
        dot.setTextSize(16);
        dot.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(dot, new LinearLayout.LayoutParams(dp(context, 20),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView preview = new TextView(context);
        preview.setText(bullet.getTitle());
        preview.setTextColor(Color.rgb(212, 212, 222));
        preview.setTextSize(12);
        preview.setMaxLines(2);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(preview, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setContentDescription("Bullet under " + heading.getTitle() + ": "
                + bullet.getTitle());
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> listener.onSelected(bullet));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 22), 0, 0, dp(context, 4));
        tree.addView(row, params);
    }

    private static boolean isVisible(final MarkdownSectionParser.Section section,
            final List<MarkdownSectionParser.Section> sections,
            final Set<Integer> collapsed) {
        int parent = section.getParentIndex();
        while (parent >= 0) {
            if (collapsed.contains(parent)) {
                return false;
            }
            parent = sections.get(parent).getParentIndex();
        }
        return true;
    }

    private static int dp(final Context context, final int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
