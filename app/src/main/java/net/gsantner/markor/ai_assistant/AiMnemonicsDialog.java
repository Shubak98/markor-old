package net.gsantner.markor.ai_assistant;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class AiMnemonicsDialog {
    public interface Generator {
        String generate(MarkdownSectionParser.Section section) throws IOException;
    }

    public interface Actions {
        void addToNote(MarkdownSectionParser.Section section, String title,
                String breakdown, AiMnemonicsStore store);
        void jumpToHeading(String headingTitle);
        void showError(String message);
    }

    private AiMnemonicsDialog() {
    }

    static String buildSystemPrompt() {
        return AiLanguagePromptHelper.ensureDefaultEnglishRule(
                "You are creating a UPSC Civil Services (UPSC) study mnemonic from exactly this Markdown "
                        + "section. Use a crisp, memorable acronym or English-language memory hook. Give a clean "
                        + "line-by-line mapping of each letter or word to the exact facts in the section; do not "
                        + "add unsupported facts.\nReturn strict JSON only: { \"title\": \"...\", "
                        + "\"hook\": \"...\", \"breakdown\": \"...\" }");
    }

    public static void show(final Context context, final String markdown, final File file,
            final AiMnemonicsStore store, final MarkdownSectionParser.Section selectedSection,
            final Generator generator, final Actions actions,
            final AiOutputRefineHelper.RefineRequest refineRequest,
            final AiOutputRefineHelper.MicCallback refineMic) {
        if (selectedSection != null) {
            generateAndPreview(context, markdown, file, selectedSection, store, generator,
                    actions, refineRequest, refineMic);
            return;
        }
        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(close, AiUiTheme.weightedButtonParams(context));
        final Dialog[] holder = new Dialog[1];
        holder[0] = AiUiTheme.showDialog(context, "Mnemonics",
                tabs(context, markdown, file, store, selectedSection, generator, actions,
                        refineRequest, refineMic), footer, null);
        close.setOnClickListener(view -> holder[0].dismiss());
        final Dialog dialog = holder[0];
        dialog.setCanceledOnTouchOutside(true);
    }

    public static void showVault(final Context context, final AiMnemonicsStore store,
            final Actions actions) {
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        renderVault(context, body, null, store, actions);
        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(close, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Mnemonics Vault", body, footer, null);
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private static LinearLayout tabs(final Context context, final String markdown, final File file,
            final AiMnemonicsStore store, final MarkdownSectionParser.Section selectedSection,
            final Generator generator, final Actions actions,
            final AiOutputRefineHelper.RefineRequest refineRequest,
            final AiOutputRefineHelper.MicCallback refineMic) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout switcher = AiUiTheme.actionRow(context);
        final TextView createTab = tab(context, "Create", true);
        final TextView vaultTab = tab(context, "All Mnemonics", false);
        switcher.addView(createTab, weighted(context));
        switcher.addView(vaultTab, weighted(context));
        root.addView(switcher);

        final LinearLayout create = createView(context, markdown, file, store, selectedSection,
                generator, actions, refineRequest, refineMic);
        final LinearLayout vault = new LinearLayout(context);
        vault.setOrientation(LinearLayout.VERTICAL);
        vault.setVisibility(View.GONE);
        root.addView(create);
        root.addView(vault);

        createTab.setOnClickListener(view -> {
            styleTab(createTab, true);
            styleTab(vaultTab, false);
            create.setVisibility(View.VISIBLE);
            vault.setVisibility(View.GONE);
        });
        vaultTab.setOnClickListener(view -> {
            styleTab(createTab, false);
            styleTab(vaultTab, true);
            create.setVisibility(View.GONE);
            vault.setVisibility(View.VISIBLE);
            renderVault(context, vault, file, store, actions);
        });
        return root;
    }

    private static LinearLayout createView(final Context context, final String markdown,
            final File file, final AiMnemonicsStore store,
            final MarkdownSectionParser.Section selectedSection, final Generator generator,
            final Actions actions, final AiOutputRefineHelper.RefineRequest refineRequest,
            final AiOutputRefineHelper.MicCallback refineMic) {
        final LinearLayout create = new LinearLayout(context);
        create.setOrientation(LinearLayout.VERTICAL);
        final List<MarkdownSectionParser.Section> sections = MarkdownSectionParser.parse(markdown);
        if (selectedSection != null) {
            generateAndPreview(context, markdown, file, selectedSection, store, generator,
                    actions, refineRequest, refineMic);
        } else if (sections.isEmpty()) {
            final TextView empty = AiUiTheme.label(context,
                    "This note has no Markdown headings.", 14);
            empty.setTextColor(AiUiTheme.SECONDARY_TEXT);
            create.addView(empty);
        } else {
            create.addView(MarkdownTreeViewHelper.create(context, sections, section ->
                    generateAndPreview(context, markdown, file, section, store,
                            generator, actions, refineRequest, refineMic)));
        }
        final TextView wholeNote = AiUiTheme.label(context, "Whole Note", 14);
        wholeNote.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        wholeNote.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                AiUiTheme.ACCENT, dp(context, 1)));
        wholeNote.setClickable(true);
        wholeNote.setFocusable(true);
        final LinearLayout.LayoutParams wholeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wholeParams.topMargin = dp(context, 8);
        create.addView(wholeNote, wholeParams);
        wholeNote.setOnClickListener(view -> generateAndPreview(context, markdown, file,
                MarkdownSectionParser.wholeNote(markdown), store, generator, actions, refineRequest,
                refineMic));
        return create;
    }

    private static void generateAndPreview(final Context context, final String markdown,
            final File file, final MarkdownSectionParser.Section section,
            final AiMnemonicsStore store, final Generator generator, final Actions actions,
            final AiOutputRefineHelper.RefineRequest refineRequest,
            final AiOutputRefineHelper.MicCallback refineMic) {
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(new ProgressBar(context));
        final TextView loading = AiUiTheme.label(context, "Creating mnemonic for “"
                + section.getTitle() + "”…", 14);
        body.addView(loading);
        final LinearLayout actionsRow = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        actionsRow.addView(cancel, AiUiTheme.weightedButtonParams(context));
        final Dialog loadingDialog = AiUiTheme.showCompactDialog(context,
                "Mnemonic Generator", body, actionsRow);
        cancel.setOnClickListener(view -> loadingDialog.dismiss());

        new Thread(() -> {
            try {
                final String raw = generator.generate(section);
                final Mnemonic mnemonic = parseMnemonic(raw);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                        showPreview(context, markdown, file, section, mnemonic, store, actions,
                                generator, refineRequest, refineMic);
                    }
                });
            } catch (Exception exception) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                        actions.showError(exception.getMessage());
                    }
                });
            }
        }).start();
    }

    private static void showPreview(final Context context, final String markdown, final File file,
            final MarkdownSectionParser.Section section, final Mnemonic mnemonic,
            final AiMnemonicsStore store, final Actions actions, final Generator generator,
            final AiOutputRefineHelper.RefineRequest refineRequest,
            final AiOutputRefineHelper.MicCallback refineMic) {
        final Mnemonic[] current = {mnemonic};
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        final TextView hook = AiUiTheme.label(context,
                current[0].title + ": " + current[0].hook, 16);
        hook.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hook.setTextColor(Color.WHITE);
        body.addView(hook);
        final TextView breakdown = AiUiTheme.label(context, current[0].breakdown, 14);
        breakdown.setTextColor(Color.rgb(208, 208, 218));
        breakdown.setPadding(0, dp(context, 10), 0, 0);
        body.addView(breakdown);

        final LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout refineRow = AiUiTheme.actionRow(context);
        final Button refine = AiUiTheme.button(
                context, "✏ Refine / Edit", AiUiTheme.ButtonKind.SECONDARY);
        refineRow.addView(refine, AiUiTheme.weightedButtonParams(context));
        footer.addView(refineRow);
        final LinearLayout actionRow = AiUiTheme.actionRow(context);
        final LinearLayout regenerateRow = AiUiTheme.actionRow(context);
        final Button add = AiUiTheme.button(context, "Add to Note",
                AiUiTheme.ButtonKind.PRIMARY);
        final Button vault = AiUiTheme.button(context, "Vault Only",
                AiUiTheme.ButtonKind.SECONDARY);
        final Button regenerate = AiUiTheme.button(context, "Regenerate",
                AiUiTheme.ButtonKind.SECONDARY);
        actionRow.addView(add, AiUiTheme.weightedButtonParams(context));
        actionRow.addView(vault, AiUiTheme.weightedButtonParams(context));
        regenerateRow.addView(regenerate, AiUiTheme.weightedButtonParams(context));
        footer.addView(actionRow);
        footer.addView(regenerateRow);
        final Dialog preview = AiUiTheme.showDialog(context, "Mnemonic Preview",
                body, footer, null);

        refine.setOnClickListener(view -> {
            try {
                AiOutputRefineHelper.show(context, formatMnemonic(current[0]), refineRequest,
                        refined -> {
                            try {
                                current[0] = parseMnemonic(refined);
                                hook.setText(current[0].title + ": " + current[0].hook);
                                breakdown.setText(current[0].breakdown);
                            } catch (IOException exception) {
                                actions.showError(exception.getMessage());
                            }
                        }, refineMic);
            } catch (IOException exception) {
                actions.showError(exception.getMessage());
            }
        });

        vault.setOnClickListener(view -> {
            try {
                store.saveMnemonic(file.getAbsolutePath(), file.getName(),
                        section.getTitle(), section.getRawHeadingLine(),
                        current[0].title + ": " + current[0].hook,
                        current[0].breakdown, false);
                preview.dismiss();
                Toast.makeText(context, "Saved to Revision Vault", Toast.LENGTH_SHORT).show();
            } catch (IOException exception) {
                actions.showError(exception.getMessage());
            }
        });
        add.setOnClickListener(view -> {
            preview.dismiss();
            actions.addToNote(section, current[0].title + ": " + current[0].hook,
                    current[0].breakdown, store);
        });
        regenerate.setOnClickListener(view -> {
            preview.dismiss();
            generateAndPreview(context, markdown, file, section, store, generator, actions,
                    refineRequest, refineMic);
        });
    }

    private static String formatMnemonic(final Mnemonic mnemonic) throws IOException {
        try {
            return new JSONObject().put("title", mnemonic.title).put("hook", mnemonic.hook)
                    .put("breakdown", mnemonic.breakdown).toString();
        } catch (org.json.JSONException exception) {
            throw new IOException("Could not prepare the mnemonic for refinement.", exception);
        }
    }

    private static void renderVault(final Context context, final LinearLayout vault,
            final File file, final AiMnemonicsStore store, final Actions actions) {
        vault.removeAllViews();
        final LinearLayout filters = AiUiTheme.actionRow(context);
        final List<AiMnemonicsStore.Entry> allFileEntries = safeAll(store);
        final TextView allFiles = tab(context,
                "All Files (" + allFileEntries.size() + ")", file == null);
        final TextView thisFile = file == null ? null : tab(context,
                "This File (" + safeFilter(store, file.getAbsolutePath()).size() + ")", true);
        if (thisFile != null) {
            filters.addView(thisFile, weighted(context));
        }
        filters.addView(allFiles, weighted(context));
        vault.addView(filters);
        final LinearLayout cards = new LinearLayout(context);
        cards.setOrientation(LinearLayout.VERTICAL);
        vault.addView(cards);

        if (thisFile != null) {
            thisFile.setOnClickListener(view -> {
                styleTab(thisFile, true);
                styleTab(allFiles, false);
                renderCards(context, cards, safeFilter(store, file.getAbsolutePath()),
                        file, store, actions);
            });
        }
        allFiles.setOnClickListener(view -> {
            if (thisFile != null) {
                styleTab(thisFile, false);
            }
            styleTab(allFiles, true);
            renderCards(context, cards, safeAll(store), file, store, actions);
        });
        renderCards(context, cards, file == null ? allFileEntries
                : safeFilter(store, file.getAbsolutePath()), file, store, actions);
    }

    private static void renderCards(final Context context, final LinearLayout cards,
            final List<AiMnemonicsStore.Entry> entries, final File file,
            final AiMnemonicsStore store, final Actions actions) {
        cards.removeAllViews();
        if (entries.isEmpty()) {
            final TextView empty = AiUiTheme.label(context, "No saved mnemonics.", 14);
            empty.setTextColor(AiUiTheme.SECONDARY_TEXT);
            cards.addView(empty);
            return;
        }
        for (AiMnemonicsStore.Entry entry : entries) {
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(AiUiTheme.rounded(Color.rgb(22, 22, 27),
                    dp(context, 12), AiUiTheme.DIVIDER, dp(context, 1)));
            card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, dp(context, 8));
            card.setLayoutParams(cardParams);

            final TextView tags = AiUiTheme.label(context,
                    entry.getHeadingTitle() + "  •  " + entry.getFileName(), 11);
            tags.setTextColor(AiUiTheme.ACCENT);
            tags.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(tags);
            final TextView hook = AiUiTheme.label(context, entry.getMnemonicTitle(), 16);
            hook.setTextColor(Color.WHITE);
            hook.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            hook.setPadding(0, dp(context, 6), 0, 0);
            card.addView(hook);
            final TextView breakdown = AiUiTheme.label(context, entry.getMnemonicBody(), 13);
            breakdown.setTextColor(Color.rgb(208, 208, 218));
            breakdown.setPadding(0, dp(context, 5), 0, dp(context, 8));
            card.addView(breakdown);

            final LinearLayout row = AiUiTheme.actionRow(context);
            final Button copy = AiUiTheme.button(context, "Copy", AiUiTheme.ButtonKind.SECONDARY);
            final Button jump = AiUiTheme.button(context, "Jump", AiUiTheme.ButtonKind.SECONDARY);
            final Button delete = AiUiTheme.button(context, "🗑 Delete",
                    AiUiTheme.ButtonKind.SECONDARY);
            row.addView(copy);
            row.addView(jump);
            row.addView(delete);
            card.addView(row);

            copy.setOnClickListener(view -> {
                final ClipboardManager clipboard =
                        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Mnemonic",
                            entry.getMnemonicTitle() + "\n" + entry.getMnemonicBody()));
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show();
                }
            });
            jump.setOnClickListener(view -> {
                if (file != null && file.getAbsolutePath().equals(entry.getFilePath())) {
                    actions.jumpToHeading(entry.getHeadingTitle());
                } else {
                    Toast.makeText(context, "Open this file, then use Jump.",
                            Toast.LENGTH_SHORT).show();
                }
            });
            delete.setOnClickListener(view -> new AlertDialog.Builder(context)
                    .setTitle("Delete Mnemonic?")
                    .setMessage("Remove this mnemonic from the Revision Vault?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        try {
                            store.deleteMnemonic(entry.getId());
                            renderVault(context, (LinearLayout) cards.getParent(),
                                    file, store, actions);
                        } catch (IOException exception) {
                            actions.showError(exception.getMessage());
                        }
                    }).show());
            cards.addView(card);
        }
    }

    private static List<AiMnemonicsStore.Entry> safeFilter(final AiMnemonicsStore store,
            final String filePath) {
        try {
            return store.getMnemonicsForFile(filePath);
        } catch (IOException exception) {
            return java.util.Collections.emptyList();
        }
    }

    private static List<AiMnemonicsStore.Entry> safeAll(final AiMnemonicsStore store) {
        try {
            return store.getAllMnemonics();
        } catch (IOException exception) {
            return java.util.Collections.emptyList();
        }
    }

    static Mnemonic parseMnemonic(final String raw) throws IOException {
        String json = raw == null ? "" : raw.trim();
        if (json.startsWith("```")) {
            final int firstLine = json.indexOf('\n');
            if (firstLine >= 0) {
                json = json.substring(firstLine + 1);
            }
            final int closingFence = json.lastIndexOf("```");
            if (closingFence >= 0) {
                json = json.substring(0, closingFence).trim();
            }
        }
        try {
            final JSONObject object = new JSONObject(json);
            final String title = firstString(object, "title", "mnemonic", "name");
            final String hook = firstString(object, "hook", "mnemonic", "title");
            final String breakdown = firstString(object, "breakdown", "explanation", "details", "mapping");
            if (!TextUtils.isEmpty(title) || !TextUtils.isEmpty(hook)
                    || !TextUtils.isEmpty(breakdown)) {
                final String resolvedTitle = TextUtils.isEmpty(title)
                        ? (TextUtils.isEmpty(hook) ? "Mnemonic" : hook) : title;
                final String resolvedHook = TextUtils.isEmpty(hook)
                        ? (TextUtils.isEmpty(breakdown) ? resolvedTitle : breakdown) : hook;
                final String resolvedBreakdown = TextUtils.isEmpty(breakdown)
                        ? resolvedHook : breakdown;
                return new Mnemonic(resolvedTitle, resolvedHook, resolvedBreakdown);
            }
        } catch (org.json.JSONException exception) {
            // Plain-text responses are a valid fallback below.
        }
        if (TextUtils.isEmpty(json)) {
            throw new IOException("The provider returned an empty mnemonic.");
        }
        final int newline = json.indexOf('\n');
        if (newline > 0) {
            final String title = json.substring(0, newline).trim();
            final String breakdown = json.substring(newline + 1).trim();
            return new Mnemonic(TextUtils.isEmpty(title) ? "Mnemonic" : title,
                    title, TextUtils.isEmpty(breakdown) ? json : breakdown);
        }
        return new Mnemonic("Mnemonic", json, json);
    }

    private static String firstString(final JSONObject object, final String... keys) {
        for (final String key : keys) {
            if (object.has(key)) {
                final String value = object.optString(key, "").trim();
                if (!TextUtils.isEmpty(value)) {
                    return value;
                }
            }
        }
        for (final String key : keys) {
            final java.util.Iterator<String> names = object.keys();
            while (names.hasNext()) {
                final String name = names.next();
                if (name != null && name.equalsIgnoreCase(key)) {
                    final String value = object.optString(name, "").trim();
                    if (!TextUtils.isEmpty(value)) {
                        return value;
                    }
                }
            }
        }
        return "";
    }

    private static TextView tab(final Context context, final String label, final boolean selected) {
        final TextView tab = AiUiTheme.label(context, label, 12);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        styleTab(tab, selected);
        return tab;
    }

    private static void styleTab(final TextView tab, final boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : AiUiTheme.SECONDARY_TEXT);
        tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        tab.setBackground(AiUiTheme.rounded(selected ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY,
                dp(tab.getContext(), 16),
                selected ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY_BORDER, dp(tab.getContext(), 1)));
    }

    private static LinearLayout.LayoutParams weighted(final Context context) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private static int dp(final Context context, final int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class Mnemonic {
        private final String title;
        private final String hook;
        private final String breakdown;

        private Mnemonic(final String title, final String hook, final String breakdown) {
            this.title = title;
            this.hook = hook;
            this.breakdown = breakdown;
        }

        String getTitle() {
            return title;
        }

        String getHook() {
            return hook;
        }

        String getBreakdown() {
            return breakdown;
        }
    }
}
