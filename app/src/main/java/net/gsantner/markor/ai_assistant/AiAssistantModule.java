package net.gsantner.markor.ai_assistant;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import net.gsantner.markor.ApplicationObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiAssistantModule {
    private static final long MAX_SETTINGS_BACKUP_BYTES = 1L * 1024L * 1024L;

    public interface ScrollResultCallback {
        void onResult(boolean found);
    }

    public interface ScrollCallback {
        void scrollTo(String target, ScrollResultCallback callback);
    }

    public interface OpenFileCallback {
        void open(File file);
    }

    private interface ModelSelectionCallback {
        void onSelected(String model);
    }

    private interface ImageResultCallback {
        void onSelected(String base64);
    }

    private interface SettingsImportCallback {
        void onSelected(String jsonText);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Fragment fragment;
    private final android.content.Context hostContext;
    private final ActivityResultLauncher<Intent> voiceLauncher;
    private final ActivityResultLauncher<String> imageLauncher;
    private final ActivityResultLauncher<String> settingsImportLauncher;
    private final AiSettingsStore settingsStore;
    private final AiDiffHistoryStore diffHistoryStore;
    private final AiMnemonicsStore mnemonicsStore;
    private AiUiTheme.DialogLifecycleHandler dialogLifecycleHandler;
    private LifecycleOwner lifecycleOwner;
    private LifecycleEventObserver lifecycleObserver;
    private final List<MinimizedDialog> minimizedDialogs = new ArrayList<>();
    private EditText activeInstructionInput;
    private View floatingButton;
    private Dialog activeMinimizedDialog;
    private String activeMinimizedLabel;
    private Runnable onAfterApply = () -> { };
    private boolean isViewModeVisible;
    private File currentFile;
    private ScrollCallback scrollCallback;
    private OpenFileCallback openFileCallback;
    private ImageResultCallback pendingImageCallback;
    private SettingsImportCallback pendingSettingsImportCallback;

    private static final class MinimizedDialog {
        private final Dialog dialog;
        private final String label;

        private MinimizedDialog(final Dialog dialog, final String label) {
            this.dialog = dialog;
            this.label = label;
        }
    }

    public AiAssistantModule(final Fragment fragment) {
        this.fragment = fragment;
        hostContext = fragment.requireContext();
        settingsStore = new AiSettingsStore(hostContext);
        diffHistoryStore = new AiDiffHistoryStore(hostContext);
        mnemonicsStore = new AiMnemonicsStore(hostContext);
        voiceLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onVoiceResult);
        imageLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImageSelected);
        settingsImportLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onSettingsImportPicked);
        registerMinimizeHandler();
        observeLifecycle(fragment);
    }

    public AiAssistantModule(final ActivityResultCaller resultCaller,
            final android.content.Context context,
            final OpenFileCallback openFileCallback) {
        if (resultCaller == null || context == null) {
            throw new IllegalArgumentException("Activity result caller and context are required.");
        }
        fragment = null;
        hostContext = context;
        this.openFileCallback = openFileCallback;
        settingsStore = new AiSettingsStore(context);
        diffHistoryStore = new AiDiffHistoryStore(context);
        mnemonicsStore = new AiMnemonicsStore(context);
        voiceLauncher = resultCaller.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onVoiceResult);
        imageLauncher = resultCaller.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImageSelected);
        settingsImportLauncher = resultCaller.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onSettingsImportPicked);
        registerMinimizeHandler();
        observeLifecycle(resultCaller);
    }

    public void openMainAssistantDialog() {
        registerMinimizeHandler();
        if (resumeMinimizedDialog()) {
            return;
        }
        currentFile = null;
        scrollCallback = null;
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        openEditorDialog(new EditText(context));
    }

    public void openGitHubSyncDialog() {
        registerMinimizeHandler();
        currentFile = null;
        showGitHubSyncDialog(getContext(), null);
    }

    private android.content.Context getContext() {
        return fragment == null ? hostContext : fragment.getContext();
    }

    private File getNotebookRoot() {
        return ApplicationObject.settings().getNotebookDirectory();
    }

    private void registerMinimizeHandler() {
        if (dialogLifecycleHandler == null) {
            dialogLifecycleHandler = new AiUiTheme.DialogLifecycleHandler() {
                @Override
                public void onMinimize(final Dialog dialog, final String label) {
                    onDialogMinimized(dialog, label);
                }

                @Override
                public void onDismiss(final Dialog dialog) {
                    onDialogDismissed(dialog);
                }
            };
        }
        AiUiTheme.setDialogLifecycleHandler(dialogLifecycleHandler);
    }

    private void observeLifecycle(final ActivityResultCaller resultCaller) {
        if (!(resultCaller instanceof LifecycleOwner)) {
            return;
        }
        lifecycleOwner = (LifecycleOwner) resultCaller;
        lifecycleObserver = (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) {
                onDestroyView();
                if (lifecycleOwner != null && lifecycleObserver != null) {
                    lifecycleOwner.getLifecycle().removeObserver(lifecycleObserver);
                    lifecycleOwner = null;
                    lifecycleObserver = null;
                }
            }
        };
        lifecycleOwner.getLifecycle().addObserver(lifecycleObserver);
    }

    private void onDialogMinimized(final Dialog dialog, final String label) {
        if (dialog == null) {
            return;
        }
        for (int index = minimizedDialogs.size() - 1; index >= 0; index--) {
            if (minimizedDialogs.get(index).dialog == dialog) {
                minimizedDialogs.remove(index);
            }
        }
        minimizedDialogs.add(new MinimizedDialog(dialog, label));
        updateActiveMinimizedDialog();
        dialog.hide();
        refreshFloatingButton();
        final android.content.Context context = getContext();
        if (context != null) {
            Toast.makeText(context, "Minimized — tap AI button to resume",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void onDialogDismissed(final Dialog dialog) {
        boolean removed = false;
        for (int index = minimizedDialogs.size() - 1; index >= 0; index--) {
            if (minimizedDialogs.get(index).dialog == dialog) {
                minimizedDialogs.remove(index);
                removed = true;
            }
        }
        if (removed) {
            updateActiveMinimizedDialog();
            refreshFloatingButton();
        }
    }

    private void updateActiveMinimizedDialog() {
        if (minimizedDialogs.isEmpty()) {
            activeMinimizedDialog = null;
            activeMinimizedLabel = null;
            return;
        }
        final MinimizedDialog active = minimizedDialogs.get(minimizedDialogs.size() - 1);
        activeMinimizedDialog = active.dialog;
        activeMinimizedLabel = active.label;
    }

    private boolean resumeMinimizedDialog() {
        final Dialog dialog = activeMinimizedDialog;
        if (dialog == null) {
            return false;
        }
        for (int index = minimizedDialogs.size() - 1; index >= 0; index--) {
            if (minimizedDialogs.get(index).dialog == dialog) {
                minimizedDialogs.remove(index);
                break;
            }
        }
        updateActiveMinimizedDialog();
        refreshFloatingButton();
        if (getContext() == null) {
            dialog.dismiss();
            return true;
        }
        dialog.show();
        return true;
    }

    private void refreshFloatingButton() {
        if (floatingButton instanceof TextView) {
            final TextView button = (TextView) floatingButton;
            final boolean minimized = activeMinimizedDialog != null;
            button.setText(minimized ? "AI •" : "AI");
            button.setTextColor(Color.WHITE);
            button.setContentDescription(minimized
                    ? "Resume " + activeMinimizedLabel : "AI Edit");
            final android.content.Context context = button.getContext();
            final GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(AiUiTheme.ACCENT);
            background.setStroke(dp(context, minimized ? 3 : 2),
                    minimized ? Color.WHITE : Color.rgb(77, 0, 0));
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED), background, null));
        }
        setViewModeVisible(isViewModeVisible);
    }

    public void onDestroyView() {
        clearMinimizedDialogs();
        AiUiTheme.clearDialogLifecycleHandler(dialogLifecycleHandler);
        dialogLifecycleHandler = null;
        if (floatingButton != null && floatingButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) floatingButton.getParent()).removeView(floatingButton);
        }
        floatingButton = null;
        activeInstructionInput = null;
    }

    private void clearMinimizedDialogs() {
        final List<MinimizedDialog> minimized = new ArrayList<>(minimizedDialogs);
        minimizedDialogs.clear();
        activeMinimizedDialog = null;
        activeMinimizedLabel = null;
        for (MinimizedDialog item : minimized) {
            item.dialog.dismiss();
        }
        refreshFloatingButton();
    }

    public void attachFloatingButton(final View rootView, final EditText editText, final File currentFile,
            final ScrollCallback scrollCallback, final OpenFileCallback openFileCallback,
            final Runnable onAfterApply) {
        if (getContext() == null || rootView == null || editText == null) {
            return;
        }
        if (!(rootView instanceof FrameLayout)) {
            throw new IllegalArgumentException("AI Edit floating button requires a FrameLayout root view.");
        }
        registerMinimizeHandler();
        if (floatingButton != null && floatingButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) floatingButton.getParent()).removeView(floatingButton);
        }
        this.currentFile = currentFile;
        this.scrollCallback = scrollCallback;
        this.openFileCallback = openFileCallback;
        this.onAfterApply = onAfterApply == null ? () -> { } : onAfterApply;

        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final TextView button = new TextView(context);
        button.setText("AI");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("AI Edit");
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setFocusable(true);
        button.setClickable(true);
        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(AiUiTheme.ACCENT);
        background.setStroke(dp(context, 2), Color.rgb(77, 0, 0));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED), background, null));
        button.setElevation(dp(context, 7));

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(context, 54), dp(context, 54), Gravity.BOTTOM | Gravity.RIGHT);
        params.setMargins(0, 0, dp(context, 20), dp(context, 24));
        final FrameLayout root = (FrameLayout) rootView;
        root.addView(button, params);
        button.setOnClickListener(view -> openEditorDialog(editText));
        floatingButton = button;
        refreshFloatingButton();
        button.bringToFront();
    }

    public void setViewModeVisible(final boolean isViewMode) {
        isViewModeVisible = isViewMode;
        if (floatingButton != null) {
            floatingButton.setVisibility(isViewMode || activeMinimizedDialog != null
                    ? View.VISIBLE : View.GONE);
        }
    }

    public void openEditorDialog(final EditText editText) {
        registerMinimizeHandler();
        if (resumeMinimizedDialog()) {
            return;
        }
        if (getContext() == null || editText == null) {
            return;
        }
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final LinearLayout content = verticalLayout(context);
        final String[] selectedMode = {"auto"};
        final MarkdownSectionParser.Section[] selectedSection = {null};
        final TextView[] modeChips = addModeChips(context, content,
                selectedMode, currentFile != null);
        if (currentFile == null) {
            final TextView availability = AiUiTheme.label(context,
                    "No file is open. Edit, Jump, and note-based study actions require an open note. "
                            + "Folder Search and New Note use the Notebook root.", 12);
            availability.setTextColor(AiUiTheme.SECONDARY_TEXT);
            content.addView(availability);
        }
        final String[] attachedImage = {null};
        final TextView imageChip = AiUiTheme.label(context, "📷 Image attached  ✕", 12);
        imageChip.setTextColor(AiUiTheme.ACCENT);
        imageChip.setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 6));
        imageChip.setBackground(AiUiTheme.rounded(AiUiTheme.SURFACE,
                dp(context, 16), AiUiTheme.ACCENT, dp(context, 1)));
        imageChip.setVisibility(View.GONE);
        content.addView(imageChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        imageChip.setOnClickListener(view -> {
            attachedImage[0] = null;
            imageChip.setVisibility(View.GONE);
        });
        final TextView scopeChip = AiUiTheme.label(context, "", 12);
        scopeChip.setSingleLine(true);
        scopeChip.setMaxLines(1);
        scopeChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        scopeChip.setMaxWidth(dp(context, 280));
        scopeChip.setTextColor(AiUiTheme.ACCENT);
        scopeChip.setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 6));
        scopeChip.setBackground(AiUiTheme.rounded(AiUiTheme.SURFACE,
                dp(context, 16), AiUiTheme.ACCENT, dp(context, 1)));
        scopeChip.setVisibility(View.GONE);
        content.addView(scopeChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scopeChip.setOnClickListener(view -> {
            selectedSection[0] = null;
            scopeChip.setVisibility(View.GONE);
        });
        addVerticalGap(context, content, 10);

        final EditText instructions = new EditText(context);
        instructions.setHint("Ask or instruct in Hindi, Hinglish, or English");
        instructions.setTextColor(AiUiTheme.PRIMARY_TEXT);
        instructions.setHintTextColor(Color.rgb(128, 128, 140));
        instructions.setGravity(Gravity.TOP | Gravity.START);
        instructions.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        instructions.setMinLines(5);
        instructions.setMaxLines(7);
        instructions.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        instructions.setBackground(AiUiTheme.inputBackground(context));
        activeInstructionInput = instructions;

        content.addView(instructions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout inputActions = AiUiTheme.actionRow(context);
        inputActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final Button voiceButton = AiUiTheme.button(context, "Mic", AiUiTheme.ButtonKind.MIC);
        final Button imageButton = AiUiTheme.button(context, "📷 Image", AiUiTheme.ButtonKind.MIC);
        final Button sectionButton = AiUiTheme.button(context, "📑 Section", AiUiTheme.ButtonKind.MIC);
        for (Button utilityButton : new Button[]{voiceButton, imageButton, sectionButton}) {
            utilityButton.setTextSize(12);
            utilityButton.setMinWidth(0);
            utilityButton.setMinHeight(dp(context, 36));
            utilityButton.setSingleLine(true);
            utilityButton.setMaxLines(1);
            utilityButton.setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 6));
        }
        final LinearLayout.LayoutParams inputActionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputActionParams.setMargins(dp(context, 4), dp(context, 6), dp(context, 4), 0);
        inputActions.addView(voiceButton, inputActionParams);
        final LinearLayout.LayoutParams imageActionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imageActionParams.setMargins(dp(context, 4), dp(context, 6), dp(context, 4), 0);
        inputActions.addView(imageButton, imageActionParams);
        final LinearLayout.LayoutParams sectionActionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionActionParams.setMargins(dp(context, 4), dp(context, 6), dp(context, 4), 0);
        inputActions.addView(sectionButton, sectionActionParams);
        content.addView(inputActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        final ProgressBar progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        final LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(context, 22), dp(context, 22));
        progressParams.setMargins(0, dp(context, 12), dp(context, 8), 0);
        statusRow.addView(progress, progressParams);
        final TextView progressLabel = new TextView(context);
        progressLabel.setText("Thinking...");
        progressLabel.setVisibility(View.GONE);
        progressLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        statusRow.addView(progressLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(statusRow);

        final TextView settingsButton = AiUiTheme.headerAction(context, "⚙");
        settingsButton.setContentDescription("Settings");
        final TextView allDiffsButton = createAllDiffsButton(context);
        final TextView syncButton = createSyncButton(context);
        final TextView vaultButton = AiUiTheme.headerAction(context, "Vault");
        vaultButton.setContentDescription("Mnemonics Vault");
        final TextView resetButton = AiUiTheme.headerPill(context, "Reset");
        final LinearLayout headerActions = AiUiTheme.actionRow(context);
        final LinearLayout.LayoutParams allDiffsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
        allDiffsParams.setMargins(0, 0, dp(context, 6), 0);
        headerActions.addView(allDiffsButton, allDiffsParams);
        final LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
        syncParams.setMargins(0, 0, dp(context, 6), 0);
        headerActions.addView(syncButton, syncParams);
        headerActions.addView(vaultButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36)));
        headerActions.addView(settingsButton, new LinearLayout.LayoutParams(
                dp(context, 40), dp(context, 40)));
        headerActions.addView(resetButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36)));
        IOException historyReadError = null;
        try {
            updateAllDiffsButton(allDiffsButton);
        } catch (IOException exception) {
            historyReadError = exception;
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button runButton = AiUiTheme.button(context, "Run AI", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(runButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Assistant", content, actions, headerActions);
        if (historyReadError != null) {
            showError(historyReadError.getMessage());
        }
        voiceButton.setOnClickListener(view -> startVoiceInput());
        imageButton.setOnClickListener(view -> pickImage(base64 -> {
            attachedImage[0] = base64;
            imageChip.setVisibility(View.VISIBLE);
        }));
        sectionButton.setOnClickListener(view -> {
            if (currentFile == null) {
                Toast.makeText(context, "Open a Markdown note to choose a section.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            showScopePicker(context, editText.getText().toString(), section -> {
                selectedSection[0] = section;
                scopeChip.setText("📑 Section: " + section.getTitle() + "  ✕");
                scopeChip.setVisibility(View.VISIBLE);
            });
        });
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        resetButton.setOnClickListener(view -> {
            instructions.setText("");
            attachedImage[0] = null;
            imageChip.setVisibility(View.GONE);
            selectedSection[0] = null;
            scopeChip.setVisibility(View.GONE);
            selectedMode[0] = "auto";
            for (int index = 0; index < modeChips.length; index++) {
                AiUiTheme.styleChip(modeChips[index], index == 0);
            }
            clearMinimizedDialogs();
            dialog.dismiss();
        });
        vaultButton.setOnClickListener(view -> showMnemonicsVault(context));
        allDiffsButton.setOnClickListener(view -> showAllDiffsDialog(context, editText, allDiffsButton));
        syncButton.setOnClickListener(view -> showGitHubSyncDialog(context, editText));
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        AiUiTheme.setOnDismissListener(dialog, ignored -> {
            if (activeInstructionInput == instructions) {
                activeInstructionInput = null;
            }
        });
        runButton.setOnClickListener(view -> {
            final String instruction = instructions.getText().toString().trim();
            final String requestedMode = selectedMode[0];
            final boolean canStartWithoutInstruction = "tutor".equals(requestedMode)
                    || "quiz".equals(requestedMode) || "anki".equals(requestedMode)
                    || "diagram".equals(requestedMode) || "teacher".equals(requestedMode)
                    || "mnemonics".equals(requestedMode);
            if (instruction.isEmpty() && !canStartWithoutInstruction) {
                Toast.makeText(context, "Enter an instruction before running the assistant.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            final String originalMarkdown = editText.getText().toString();
            final MarkdownSectionParser.Section scopedSection = selectedSection[0];
            final String sourceMarkdown = scopedSection == null
                    ? originalMarkdown : scopedSection.getContent();
            if ("teacher".equals(requestedMode)) {
                dialog.dismiss();
                selectTeacherSource(editText, originalMarkdown, scopedSection);
                return;
            }
            if ("mnemonics".equals(requestedMode)) {
                dialog.dismiss();
                showMnemonicsDialog(editText, originalMarkdown, scopedSection);
                return;
            }
            if ("tutor".equals(requestedMode)) {
                dialog.dismiss();
                showTutorChat(editText, sourceMarkdown, attachedImage[0], scopedSection);
                return;
            }
            runButton.setEnabled(false);
            cancelButton.setEnabled(false);
            voiceButton.setEnabled(false);
            imageButton.setEnabled(false);
            settingsButton.setEnabled(false);
            instructions.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            progressLabel.setVisibility(View.VISIBLE);
            final android.view.inputmethod.InputMethodManager inputMethodManager =
                    (android.view.inputmethod.InputMethodManager)
                            context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(instructions.getWindowToken(), 0);
            }
            EXECUTOR.execute(() -> {
                try {
                    if ("quiz".equals(requestedMode)) {
                        final String rawQuiz = new AiProviderRouter(settingsStore).generateStudyAnswer(
                                sourceMarkdown, "Create 5 to 10 multiple-choice questions from the note. "
                                        + "Return strict JSON only: {\"questions\":[{\"question\":\"...\","
                                        + "\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctIndex\":0,"
                                        + "\"explanation\":\"...\"}]}. User request: " + instruction,
                                attachedImage[0]);
                        final List<AiStudyDialogs.Question> questions = AiStudyDialogs.parseQuiz(rawQuiz);
                        MAIN_HANDLER.post(() -> {
                            dialog.dismiss();
                            if (getContext() != null) {
                                AiStudyDialogs.showQuiz(context, questions,
                                        text -> appendToNotePreview(editText, originalMarkdown, text,
                                                instruction, "quiz", "", scopedSection),
                                        createOutputRefineRequest(), createOutputRefineMicCallback());
                            }
                        });
                        return;
                    }
                    if ("anki".equals(requestedMode)) {
                        final String rawCards = new AiProviderRouter(settingsStore).generateStudyAnswer(
                                sourceMarkdown, "Create useful Anki flashcards from this note. Return "
                                        + "strict JSON only: {\"cards\":[{\"front\":\"...\",\"back\":\"...\"}]}. "
                                        + "User request: " + instruction, attachedImage[0]);
                        final List<AnkiCsvHelper.Card> cards = AnkiCsvHelper.parseCards(rawCards);
                        final String csv = AnkiCsvHelper.toCsv(cards);
                        MAIN_HANDLER.post(() -> {
                            dialog.dismiss();
                            if (getContext() != null) {
                                showAnkiPreview(context, cards, csv);
                            }
                        });
                        return;
                    }
                    if ("diagram".equals(requestedMode)) {
                        final String diagram = new AiProviderRouter(settingsStore).generateStudyAnswer(
                                sourceMarkdown, "Create one valid Mermaid flowchart or mindmap that "
                                        + "summarizes the note. Use simple alphanumeric node IDs and safe "
                                        + "labels without quotes, brackets, parentheses, or unescaped "
                                        + "special characters. Return only a ```mermaid fenced block. "
                                        + "User request: " + instruction, attachedImage[0]);
                        final String markdownDiagram = formatMermaid(diagram);
                        MAIN_HANDLER.post(() -> {
                            dialog.dismiss();
                            if (getContext() != null) {
                                appendToNotePreview(editText, originalMarkdown,
                                        "\n\n" + markdownDiagram, instruction, "diagram", "",
                                        scopedSection);
                            }
                        });
                        return;
                    }
                    final boolean searchFolder = "folder".equals(requestedMode)
                            || ("auto".equals(requestedMode)
                            && FolderSearchHelper.isFolderSearchQuery(instruction));
                    final FolderSearchHelper.SearchResult folderResults = searchFolder
                            ? FolderSearchHelper.search(currentFile == null
                                    ? getNotebookRoot() : currentFile.getParentFile(), instruction) : null;
                    final String folderContext = folderResults == null ? ""
                            : folderResults.getContext().isEmpty()
                            ? "No relevant .md or .txt files matched the query in this folder."
                            : folderResults.getContext();
                    final boolean hasImage = attachedImage[0] != null;
                    final String effectiveInstruction = hasImage
                            ? "Transcribe the attached page or handwriting into clean structured Markdown. "
                                    + "Return it as an answer that can be reviewed and appended to the note.\n\n"
                                    + instruction : instruction;
                    final String effectiveMode = hasImage ? "study" : requestedMode;
                    final AiProviderRouter.Result providerResult = new AiProviderRouter(settingsStore)
                            .generateIntent(sourceMarkdown, effectiveInstruction, effectiveMode,
                                    folderContext, attachedImage[0]);
                    final GeminiMarkdownHelper.IntentResult result = providerResult.getIntentResult();
                    final String expectedMode = expectedIntentMode(effectiveMode);
                    if (expectedMode != null && !expectedMode.equals(result.getMode())) {
                        throw new IOException("The AI provider did not follow the selected mode. Please try again.");
                    }
                    final String updatedSection = "edit".equals(result.getMode())
                            ? GeminiMarkdownHelper.applyEdits(sourceMarkdown, result.getEditsJson())
                            : null;
                    final String updatedMarkdown = updatedSection == null ? null
                            : scopedSection == null ? updatedSection
                            : MarkdownSectionReplacement.replace(originalMarkdown,
                                    scopedSection, updatedSection);
                    MAIN_HANDLER.post(() -> {
                        dialog.dismiss();
                        if (getContext() != null) {
                            if (!providerResult.getFailoverNotice().isEmpty()) {
                                Toast.makeText(context, providerResult.getFailoverNotice(),
                                        Toast.LENGTH_SHORT).show();
                            }
                            handleIntentResult(editText, originalMarkdown, result, updatedMarkdown,
                                    folderResults, effectiveInstruction, providerResult.getUsedProviderId(),
                                    providerResult.getUsedModelName(), scopedSection, updatedSection);
                        }
                    });
                } catch (IOException | IllegalArgumentException exception) {
                    MAIN_HANDLER.post(() -> {
                        if (!dialog.isShowing() || getContext() == null) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        progressLabel.setVisibility(View.GONE);
                        cancelButton.setEnabled(true);
                        voiceButton.setEnabled(true);
                        imageButton.setEnabled(true);
                        settingsButton.setEnabled(true);
                        instructions.setEnabled(true);
                        runButton.setEnabled(true);
                        showError(exception.getMessage());
                    });
                }
            });
        });
        if (!settingsStore.hasConfiguredProviders()) {
            showSettingsDialog();
        }
    }

    private static String expectedIntentMode(final String selectedMode) {
        switch (selectedMode) {
            case "edit":
                return "edit";
            case "study":
            case "folder":
                return "answer";
            case "jump":
                return "navigate";
            case "new_note":
                return "create_note";
            default:
                return null;
        }
    }

    private static TextView[] addModeChips(final android.content.Context context,
            final LinearLayout parent,
            final String[] selectedMode, final boolean hasCurrentFile) {
        final String[][] modes = {
                {"auto", "Auto"}, {"teacher", "AI Teacher"}, {"mnemonics", "Mnemonics"}, {"edit", "Edit"},
                {"study", "Ask / Study"},
                {"jump", "Jump"}, {"folder", "Folder"}, {"new_note", "New Note"},
                {"tutor", "Tutor Chat"}, {"quiz", "MCQ Quiz"},
                {"anki", "Anki CSV"}, {"diagram", "Diagram"}
        };
        final HorizontalScrollView horizontalScroll = new HorizontalScrollView(context);
        horizontalScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[modes.length];
        for (int modeIndex = 0; modeIndex < modes.length; modeIndex++) {
            final String[] mode = modes[modeIndex];
            final TextView chip = new TextView(context);
            chips[modeIndex] = chip;
            chip.setText(mode[1]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            AiUiTheme.styleChip(chip, modeIndex == 0);
            final String chipMode = mode[0];
            final boolean requiresCurrentFile = "teacher".equals(chipMode)
                    || "mnemonics".equals(chipMode) || "edit".equals(chipMode)
                    || "jump".equals(chipMode) || "tutor".equals(chipMode)
                    || "quiz".equals(chipMode) || "anki".equals(chipMode)
                    || "diagram".equals(chipMode);
            if (!hasCurrentFile && requiresCurrentFile) {
                chip.setAlpha(0.45f);
                chip.setContentDescription(mode[1] + ", open a note to use this action");
            }
            chip.setOnClickListener(view -> {
                if (!hasCurrentFile && requiresCurrentFile) {
                    Toast.makeText(context, "Open a note to use " + mode[1] + ".",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedMode[0] = chipMode;
                for (int index = 0; index < chips.length; index++) {
                    AiUiTheme.styleChip(chips[index], modes[index][0].equals(selectedMode[0]));
                }
            });
            row.addView(chip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) chip.getLayoutParams();
            params.setMargins(0, 0, dp(context, 6), dp(context, 8));
            chip.setLayoutParams(params);
        }
        horizontalScroll.addView(row);
        parent.addView(horizontalScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return chips;
    }

    private TextView createAllDiffsButton(final android.content.Context context) {
        final TextView button = new TextView(context);
        button.setText("All Diffs");
        button.setTextColor(AiUiTheme.ACCENT);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                AiUiTheme.rounded(AiUiTheme.SECONDARY, dp(context, 18),
                        AiUiTheme.SECONDARY_BORDER, dp(context, 1)), null));
        button.setContentDescription("All Diffs");
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView createSyncButton(final android.content.Context context) {
        final TextView button = new TextView(context);
        button.setText("☁ Sync");
        button.setTextColor(AiUiTheme.ACCENT);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                AiUiTheme.rounded(AiUiTheme.SECONDARY, dp(context, 18),
                        AiUiTheme.ACCENT, dp(context, 1)), null));
        button.setContentDescription("Sync notes with GitHub");
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void updateAllDiffsButton(final TextView button) throws IOException {
        if (currentFile == null) {
            button.setVisibility(View.GONE);
            return;
        }
        final int count = diffHistoryStore.getHistory(currentFile).size();
        button.setText(count == 0 ? "All Diffs" : "All Diffs (" + count + ")");
        button.setContentDescription("All Diffs, " + count + " saved edits");
    }

    private void showGitHubSyncDialog(final android.content.Context context, final EditText editText) {
        if (context == null) {
            return;
        }
        final AiUiTheme.MinimizableDialog dialog = new AiUiTheme.MinimizableDialog(context);
        dialog.setOnMinimize(
                () -> AiUiTheme.minimizeDialog(context, dialog, "GitHub Notes Sync"));
        AiUiTheme.setOnDismissListener(dialog, null);
        final LinearLayout shell = verticalLayout(context);
        shell.setBackground(AiUiTheme.cardBackground(context));

        final LinearLayout header = verticalLayout(context);
        final LinearLayout titleRow = AiUiTheme.actionRow(context);
        titleRow.setPadding(dp(context, 18), dp(context, 10), dp(context, 12), dp(context, 4));
        final TextView title = AiUiTheme.label(context, "GitHub Notes Sync", 18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView settingsButton = AiUiTheme.headerAction(context, "⚙");
        settingsButton.setContentDescription("Sync Settings");
        titleRow.addView(settingsButton, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)));
        titleRow.addView(AiUiTheme.minimizeAction(context, dialog, "GitHub Notes Sync"),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36)));
        header.addView(titleRow);
        final TextView repositoryLine = AiUiTheme.label(context, "", 12);
        repositoryLine.setTextColor(AiUiTheme.ACCENT);
        repositoryLine.setPadding(dp(context, 18), 0, dp(context, 18), dp(context, 2));
        header.addView(repositoryLine);
        final TextView lastSyncLine = AiUiTheme.label(context, "", 12);
        lastSyncLine.setTextColor(AiUiTheme.SECONDARY_TEXT);
        lastSyncLine.setPadding(dp(context, 18), 0, dp(context, 18), dp(context, 10));
        header.addView(lastSyncLine);
        header.addView(AiUiTheme.divider(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        updateSyncHeader(repositoryLine, lastSyncLine);
        shell.addView(header);

        final LinearLayout content = verticalLayout(context);
        final LinearLayout statusRow = AiUiTheme.actionRow(context);
        final ProgressBar progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)));
        final TextView status = AiUiTheme.label(context, "Ready for manual sync.", 13);
        status.setTextColor(AiUiTheme.SECONDARY_TEXT);
        final LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusParams.setMargins(dp(context, 8), 0, 0, 0);
        statusRow.addView(status, statusParams);
        content.addView(statusRow);
        final ScrollView logScroll = new ScrollView(context);
        logScroll.setFillViewport(true);
        final TextView log = AiUiTheme.label(context,
                "Sync runs only when you tap a button. No background sync is scheduled.", 12);
        log.setTextColor(AiUiTheme.UNCHANGED_TEXT);
        log.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        logScroll.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                AiUiTheme.INPUT_BORDER, dp(context, 1)));
        logScroll.addView(log, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 270));
        logParams.setMargins(0, dp(context, 10), 0, 0);
        content.addView(logScroll, logParams);
        final ScrollView contentScroll = new ScrollView(context);
        contentScroll.setFillViewport(false);
        contentScroll.setPadding(dp(context, 16), dp(context, 10),
                dp(context, 16), dp(context, 10));
        contentScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.addView(AiUiTheme.divider(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        final LinearLayout primaryRow = AiUiTheme.actionRow(context);
        final Button twoWay = AiUiTheme.button(context, "Sync Now (Two-Way)",
                AiUiTheme.ButtonKind.PRIMARY);
        primaryRow.addView(twoWay, AiUiTheme.weightedButtonParams(context));
        final LinearLayout secondaryRow = AiUiTheme.actionRow(context);
        final Button pushOnly = AiUiTheme.button(context, "Push Only",
                AiUiTheme.ButtonKind.SECONDARY);
        final Button pullOnly = AiUiTheme.button(context, "Pull Only",
                AiUiTheme.ButtonKind.SECONDARY);
        final Button close = AiUiTheme.button(context, "Close",
                AiUiTheme.ButtonKind.SECONDARY);
        secondaryRow.addView(pushOnly, AiUiTheme.weightedButtonParams(context));
        secondaryRow.addView(pullOnly, AiUiTheme.weightedButtonParams(context));
        secondaryRow.addView(close, AiUiTheme.weightedButtonParams(context));
        final LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryParams.setMargins(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 3));
        footer.addView(primaryRow, primaryParams);
        final LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondaryParams.setMargins(dp(context, 6), 0, dp(context, 6), dp(context, 8));
        footer.addView(secondaryRow, secondaryParams);
        shell.addView(footer);

        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(shell);
        dialog.setCanceledOnTouchOutside(true);
        final android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.64f);
        }
        dialog.show();
        if (window != null) {
            final android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.94f),
                    (int) (metrics.heightPixels * 0.84f));
            window.setGravity(Gravity.CENTER);
        }

        settingsButton.setOnClickListener(view ->
                showGitHubSyncSettingsDialog(context, () -> updateSyncHeader(repositoryLine, lastSyncLine)));
        close.setOnClickListener(view -> dialog.dismiss());
        final Button[] syncButtons = {twoWay, pushOnly, pullOnly};
        twoWay.setOnClickListener(view -> runGitHubSync(context, editText, dialog,
                GitHubNotesSyncHelper.SyncMode.TWO_WAY, syncButtons, progress, status, log,
                logScroll, repositoryLine, lastSyncLine));
        pushOnly.setOnClickListener(view -> runGitHubSync(context, editText, dialog,
                GitHubNotesSyncHelper.SyncMode.PUSH_ONLY, syncButtons, progress, status, log,
                logScroll, repositoryLine, lastSyncLine));
        pullOnly.setOnClickListener(view -> runGitHubSync(context, editText, dialog,
                GitHubNotesSyncHelper.SyncMode.PULL_ONLY, syncButtons, progress, status, log,
                logScroll, repositoryLine, lastSyncLine));

        if (settingsStore.getGitHubPat().isEmpty() || settingsStore.getGitHubRepo().isEmpty()) {
            showGitHubSyncSettingsDialog(context,
                    () -> updateSyncHeader(repositoryLine, lastSyncLine));
        }
    }

    private void updateSyncHeader(final TextView repositoryLine, final TextView lastSyncLine) {
        repositoryLine.setText(settingsStore.getGitHubRepo().isEmpty()
                ? "No private repository configured"
                : settingsStore.getGitHubRepo() + " @ " + settingsStore.getGitHubBranch());
        final long lastSyncMs = settingsStore.getGitHubLastSyncMs();
        final String formatted = lastSyncMs <= 0 ? "Never"
                : new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                        .format(new Date(lastSyncMs));
        final String configuredRoot = settingsStore.getGitHubLocalRoot();
        final File localRoot = configuredRoot.isEmpty()
                ? getNotebookRoot() : new File(configuredRoot);
        lastSyncLine.setText("Sync root: " + localRoot.getAbsolutePath()
                + "\nLast synced: " + formatted);
    }

    private void runGitHubSync(final android.content.Context context, final EditText editText,
            final Dialog dialog, final GitHubNotesSyncHelper.SyncMode mode,
            final Button[] buttons, final ProgressBar progress, final TextView status,
            final TextView log, final ScrollView logScroll,
            final TextView repositoryLine, final TextView lastSyncLine) {
        if (settingsStore.getGitHubPat().isEmpty() || settingsStore.getGitHubRepo().isEmpty()) {
            showGitHubSyncSettingsDialog(context,
                    () -> updateSyncHeader(repositoryLine, lastSyncLine));
            return;
        }
        final String localRootValue = settingsStore.getGitHubLocalRoot();
        final File localRoot = localRootValue.isEmpty()
                ? getNotebookRoot()
                : new File(localRootValue);
        final GitHubNotesSyncHelper.SyncConfig config = new GitHubNotesSyncHelper.SyncConfig(
                settingsStore.getGitHubPat(), settingsStore.getGitHubRepo(),
                settingsStore.getGitHubBranch());
        progress.setVisibility(View.VISIBLE);
        status.setText("Syncing manually with GitHub...");
        log.setText("");
        for (Button button : buttons) {
            button.setEnabled(false);
        }
        final GitHubNotesSyncHelper helper = new GitHubNotesSyncHelper(context, currentFile);
        final GitHubNotesSyncHelper.ProgressCallback progressCallback =
                message -> MAIN_HANDLER.post(() -> {
                    if (dialog.isShowing()) {
                        appendSyncLog(log, logScroll, message);
                    }
                });
        EXECUTOR.execute(() -> {
            try {
                final GitHubNotesSyncHelper.SyncPlan plan = helper.planSync(
                        localRoot, config, mode, progressCallback);
                MAIN_HANDLER.post(() -> {
                    if (!dialog.isShowing() || getContext() == null) {
                        return;
                    }
                    if (mode == GitHubNotesSyncHelper.SyncMode.TWO_WAY && plan.hasDeletions()) {
                        progress.setVisibility(View.GONE);
                        status.setText("Review the deletion list before syncing.");
                        showSyncDeletionConfirmation(context, plan,
                                () -> executeGitHubSync(helper, plan, true, editText, dialog,
                                        buttons, progress, status, log, logScroll,
                                        repositoryLine, lastSyncLine),
                                () -> executeGitHubSync(helper, plan, false, editText, dialog,
                                        buttons, progress, status, log, logScroll,
                                        repositoryLine, lastSyncLine),
                                () -> {
                                    for (Button button : buttons) {
                                        button.setEnabled(true);
                                    }
                                    status.setText("Sync cancelled; no files were deleted.");
                                });
                    } else {
                        executeGitHubSync(helper, plan,
                                mode == GitHubNotesSyncHelper.SyncMode.TWO_WAY,
                                editText, dialog, buttons, progress, status, log, logScroll,
                                repositoryLine, lastSyncLine);
                    }
                });
            } catch (IOException exception) {
                showGitHubSyncFailure(dialog, buttons, progress, status, log, logScroll, exception);
            }
        });
    }

    private void executeGitHubSync(final GitHubNotesSyncHelper helper,
            final GitHubNotesSyncHelper.SyncPlan plan, final boolean applyDeletions,
            final EditText editText, final Dialog dialog, final Button[] buttons,
            final ProgressBar progress, final TextView status, final TextView log,
            final ScrollView logScroll, final TextView repositoryLine,
            final TextView lastSyncLine) {
        progress.setVisibility(View.VISIBLE);
        status.setText("Syncing manually with GitHub...");
        EXECUTOR.execute(() -> {
            try {
                final GitHubNotesSyncHelper.SyncReport report =
                        helper.executeSync(plan, applyDeletions,
                                message -> MAIN_HANDLER.post(() -> {
                                    if (dialog.isShowing()) {
                                        appendSyncLog(log, logScroll, message);
                                    }
                                }));
                String updatedCurrentFile = null;
                String reloadError = null;
                if (report.isCurrentFileModified() && isTextNoteFile(currentFile)) {
                    try {
                        updatedCurrentFile = readUtf8File(currentFile);
                    } catch (IOException exception) {
                        reloadError = exception.getMessage();
                    }
                }
                final String currentFileContent = updatedCurrentFile;
                final String currentFileError = reloadError;
                MAIN_HANDLER.post(() -> {
                    if (!dialog.isShowing() || getContext() == null) {
                        return;
                    }
                    progress.setVisibility(View.GONE);
                    for (Button button : buttons) {
                        button.setEnabled(true);
                    }
                    status.setText("Complete: " + report.getPushedCount() + " pushed, "
                            + report.getPulledCount() + " pulled, " + report.getSkippedCount()
                            + " skipped, " + report.getConflictCount() + " conflicts, "
                            + report.getRemoteDeletedCount() + " remote deletions, "
                            + report.getLocalDeletedCount() + " local deletions.");
                    updateSyncHeader(repositoryLine, lastSyncLine);
                    appendSyncLog(log, logScroll, "Sync completed.");
                    if (currentFileContent != null && editText != null) {
                        editText.getText().replace(0, editText.getText().length(),
                                currentFileContent);
                        onAfterApply.run();
                    } else if (currentFileError != null) {
                        appendSyncLog(log, logScroll, "Could not reload the open note: "
                                + currentFileError);
                    }
                });
            } catch (IOException exception) {
                showGitHubSyncFailure(dialog, buttons, progress, status, log, logScroll, exception);
            }
        });
    }

    private void showSyncDeletionConfirmation(final android.content.Context context,
            final GitHubNotesSyncHelper.SyncPlan plan, final Runnable confirm,
            final Runnable skip, final Runnable cancel) {
        final LinearLayout body = verticalLayout(context);
        final List<String> remotePaths = plan.getRemoteDeletionPaths();
        final List<String> localPaths = plan.getLocalDeletionPaths();
        if (!remotePaths.isEmpty()) {
            addDeletionList(context, body, "Delete from GitHub", remotePaths);
        }
        if (!localPaths.isEmpty()) {
            if (!remotePaths.isEmpty()) {
                addVerticalGap(context, body, 16);
            }
            addDeletionList(context, body, "Delete from This Device", localPaths);
        }

        final LinearLayout footer = verticalLayout(context);
        final Button confirmButton = AiUiTheme.button(context, "Confirm & Delete",
                AiUiTheme.ButtonKind.PRIMARY);
        final LinearLayout secondaryRow = AiUiTheme.actionRow(context);
        final Button skipButton = AiUiTheme.button(context, "Skip Deletions",
                AiUiTheme.ButtonKind.SECONDARY);
        final Button cancelButton = AiUiTheme.button(context, "Cancel",
                AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(confirmButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addVerticalGap(context, footer, 8);
        secondaryRow.addView(skipButton, AiUiTheme.weightedButtonParams(context));
        secondaryRow.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        footer.addView(secondaryRow);
        final Dialog confirmation = AiUiTheme.showDialog(
                context, "Confirm Sync Deletions", body, footer, null);
        final boolean[] resolved = {false};
        AiUiTheme.setOnDismissListener(confirmation, ignored -> {
            if (!resolved[0]) {
                resolved[0] = true;
                cancel.run();
            }
        });
        confirmButton.setOnClickListener(view -> {
            resolved[0] = true;
            confirmation.dismiss();
            confirm.run();
        });
        skipButton.setOnClickListener(view -> {
            resolved[0] = true;
            confirmation.dismiss();
            skip.run();
        });
        cancelButton.setOnClickListener(view -> {
            resolved[0] = true;
            confirmation.dismiss();
            cancel.run();
        });
    }

    private static void addDeletionList(final android.content.Context context,
            final LinearLayout body, final String title, final List<String> paths) {
        final TextView heading = AiUiTheme.label(context, title, 14);
        heading.setTextColor(AiUiTheme.ACCENT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(heading);
        addVerticalGap(context, body, 6);
        for (String path : paths) {
            final TextView item = AiUiTheme.label(context, "• " + path, 13);
            item.setTextColor(AiUiTheme.PRIMARY_TEXT);
            item.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
            item.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 8),
                    AiUiTheme.INPUT_BORDER, dp(context, 1)));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(context, 5);
            body.addView(item, params);
        }
    }

    private static boolean isTextNoteFile(final File file) {
        if (file == null) {
            return false;
        }
        final String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")
                || name.endsWith(".csv") || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".html")
                || name.endsWith(".htm") || name.endsWith(".svg") || name.endsWith(".org")
                || name.endsWith(".log") || name.endsWith(".properties") || name.endsWith(".ini");
    }

    private static void showGitHubSyncFailure(final Dialog dialog, final Button[] buttons,
            final ProgressBar progress, final TextView status, final TextView log,
            final ScrollView logScroll, final IOException exception) {
        MAIN_HANDLER.post(() -> {
            if (!dialog.isShowing()) {
                return;
            }
            progress.setVisibility(View.GONE);
            for (Button button : buttons) {
                button.setEnabled(true);
            }
            status.setText("Sync failed: " + exception.getMessage());
            appendSyncLog(log, logScroll, "Sync failed: " + exception.getMessage());
        });
    }

    private static void appendSyncLog(final TextView log, final ScrollView scroll,
            final String message) {
        final CharSequence current = log.getText();
        log.setText(current.length() == 0 ? message : current + "\n" + message);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showGitHubSyncSettingsDialog(final android.content.Context context,
            final Runnable onSaved) {
        final LinearLayout body = verticalLayout(context);
        final EditText pat = new EditText(context);
        pat.setSingleLine(true);
        pat.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pat.setHint("Personal Access Token");
        styleInput(pat, context);
        pat.setText(settingsStore.getGitHubPat());
        addSettingsLabel(context, body, "GitHub Personal Access Token");
        body.addView(pat);
        addVerticalGap(context, body, 10);

        final EditText repo = new EditText(context);
        repo.setSingleLine(true);
        repo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        repo.setHint("owner/repo");
        styleInput(repo, context);
        repo.setText(settingsStore.getGitHubRepo());
        addSettingsLabel(context, body, "Private Repository");
        body.addView(repo);
        addVerticalGap(context, body, 10);

        final EditText branch = new EditText(context);
        branch.setSingleLine(true);
        branch.setHint("main");
        styleInput(branch, context);
        branch.setText(settingsStore.getGitHubBranch());
        addSettingsLabel(context, body, "Branch");
        body.addView(branch);
        addVerticalGap(context, body, 10);

        final EditText localRoot = new EditText(context);
        localRoot.setSingleLine(true);
        localRoot.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        localRoot.setHint("Empty uses Markor's main Notebook root");
        styleInput(localRoot, context);
        localRoot.setText(settingsStore.getGitHubLocalRoot());
        addSettingsLabel(context, body, "Local Notes Folder (Optional)");
        body.addView(localRoot);

        final LinearLayout testRow = AiUiTheme.actionRow(context);
        final Button testButton = AiUiTheme.button(context, "Test Connection",
                AiUiTheme.ButtonKind.SECONDARY);
        final ProgressBar testProgress = new ProgressBar(context);
        testProgress.setVisibility(View.GONE);
        testProgress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        final TextView testStatus = AiUiTheme.label(context, "", 12);
        testStatus.setTextColor(AiUiTheme.SECONDARY_TEXT);
        testRow.addView(testButton);
        final LinearLayout.LayoutParams testProgressParams = new LinearLayout.LayoutParams(
                dp(context, 20), dp(context, 20));
        testProgressParams.setMargins(dp(context, 8), 0, dp(context, 8), 0);
        testRow.addView(testProgress, testProgressParams);
        testRow.addView(testStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testParams.setMargins(0, dp(context, 12), 0, 0);
        body.addView(testRow, testParams);

        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button save = AiUiTheme.button(context, "Save Settings", AiUiTheme.ButtonKind.PRIMARY);
        footer.addView(cancel, AiUiTheme.weightedButtonParams(context));
        footer.addView(save, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(
                context, "GitHub Sync Settings", body, footer, null);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            settingsStore.saveGitHubSyncSettings(pat.getText().toString(),
                    repo.getText().toString(), branch.getText().toString(),
                    localRoot.getText().toString());
            dialog.dismiss();
            if (onSaved != null) {
                onSaved.run();
            }
        });
        testButton.setOnClickListener(view -> {
            final GitHubNotesSyncHelper.SyncConfig config = new GitHubNotesSyncHelper.SyncConfig(
                    pat.getText().toString(), repo.getText().toString(), branch.getText().toString());
            testButton.setEnabled(false);
            testProgress.setVisibility(View.VISIBLE);
            testStatus.setText("Testing...");
            EXECUTOR.execute(() -> {
                try {
                    new GitHubNotesSyncHelper(context).testConnection(config);
                    MAIN_HANDLER.post(() -> {
                        if (!dialog.isShowing()) {
                            return;
                        }
                        testButton.setEnabled(true);
                        testProgress.setVisibility(View.GONE);
                        testStatus.setTextColor(AiUiTheme.ADDED_TEXT);
                        testStatus.setText("Connected.");
                    });
                } catch (IOException exception) {
                    MAIN_HANDLER.post(() -> {
                        if (!dialog.isShowing()) {
                            return;
                        }
                        testButton.setEnabled(true);
                        testProgress.setVisibility(View.GONE);
                        testStatus.setTextColor(AiUiTheme.REMOVED_TEXT);
                        testStatus.setText(exception.getMessage());
                    });
                }
            });
        });
    }

    private static String readUtf8File(final File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void showAllDiffsDialog(final android.content.Context context, final EditText editText,
            final TextView allDiffsButton) {
        final List<AiDiffHistoryStore.Entry> history;
        final AiDiffHistoryStore.VersionSettings versionSettings;
        try {
            versionSettings = diffHistoryStore.getVersionSettings(currentFile);
            history = diffHistoryStore.getHistory(currentFile);
        } catch (IOException exception) {
            showError(exception.getMessage());
            return;
        }

        final LinearLayout body = verticalLayout(context);
        final Dialog[] historyDialog = new Dialog[1];
        final LinearLayout versionSettingsCard = createVersionSettingsCard(context, versionSettings);
        final LinearLayout.LayoutParams versionCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionCardParams.setMargins(0, 0, 0, dp(context, 12));
        body.addView(versionSettingsCard, versionCardParams);
        if (history.isEmpty()) {
            final TextView emptyState = AiUiTheme.label(context,
                    "No edits have been saved for this note yet.", 14);
            emptyState.setTextColor(AiUiTheme.SECONDARY_TEXT);
            emptyState.setGravity(Gravity.CENTER);
            emptyState.setPadding(dp(context, 12), dp(context, 36), dp(context, 12), dp(context, 36));
            body.addView(emptyState, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (AiDiffHistoryStore.Entry entry : history) {
                final LinearLayout card = createHistoryCard(context, entry, () ->
                        showDeleteDiffConfirmation(context, entry, editText,
                                historyDialog[0], allDiffsButton));
                final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 0, 0, dp(context, 10));
                body.addView(card, cardParams);
                card.setOnClickListener(view -> showDiffDetailDialog(
                        context, entry, editText, historyDialog[0]));
            }
        }

        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button clearButton = AiUiTheme.button(context, "Clear History", AiUiTheme.ButtonKind.SECONDARY);
        clearButton.setTextColor(AiUiTheme.ACCENT);
        clearButton.setEnabled(!history.isEmpty());
        final Button exportButton = AiUiTheme.button(context, "Export .txt", AiUiTheme.ButtonKind.PRIMARY);
        exportButton.setEnabled(!history.isEmpty());
        final Button closeButton = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(clearButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(exportButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(closeButton, AiUiTheme.weightedButtonParams(context));
        historyDialog[0] = AiUiTheme.showDialog(context, "All Diffs", body, actions, null);
        closeButton.setOnClickListener(view -> historyDialog[0].dismiss());
        exportButton.setOnClickListener(view -> showDiffExportChoiceDialog(context, history));
        clearButton.setOnClickListener(view ->
                showClearHistoryConfirmation(context, editText, historyDialog[0], allDiffsButton));
    }

    private LinearLayout createVersionSettingsCard(final android.content.Context context,
            final AiDiffHistoryStore.VersionSettings settings) {
        final LinearLayout card = verticalLayout(context);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setElevation(dp(context, 3));
        card.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 14),
                AiUiTheme.SURFACE_BORDER, dp(context, 1)));

        final LinearLayout toggleRow = AiUiTheme.actionRow(context);
        final TextView toggleLabel = AiUiTheme.label(context, "Save Versions (This File)", 14);
        toggleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toggleRow.addView(toggleLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch toggle = new Switch(context);
        toggle.setContentDescription("Save Versions for This File");
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{AiUiTheme.ACCENT, AiUiTheme.SECONDARY_TEXT}));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{AiUiTheme.ACCENT_PRESSED, AiUiTheme.INPUT_BORDER}));
        toggle.setChecked(settings.isSaveVersionsEnabled());
        toggleRow.addView(toggle);
        card.addView(toggleRow);

        final LinearLayout stepperRow = AiUiTheme.actionRow(context);
        final TextView maxLabel = AiUiTheme.label(context, "Max Versions", 12);
        maxLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        stepperRow.addView(maxLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button minus = AiUiTheme.button(context, "-", AiUiTheme.ButtonKind.SECONDARY);
        final TextView count = AiUiTheme.label(context,
                Integer.toString(settings.getMaxVersionsToSave()), 14);
        count.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                dp(context, 40), ViewGroup.LayoutParams.WRAP_CONTENT);
        final Button plus = AiUiTheme.button(context, "+", AiUiTheme.ButtonKind.SECONDARY);
        stepperRow.addView(minus, new LinearLayout.LayoutParams(
                dp(context, 42), ViewGroup.LayoutParams.WRAP_CONTENT));
        stepperRow.addView(count, countParams);
        stepperRow.addView(plus, new LinearLayout.LayoutParams(
                dp(context, 42), ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams stepperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stepperParams.setMargins(0, dp(context, 8), 0, 0);
        card.addView(stepperRow, stepperParams);

        final TextView status = AiUiTheme.label(context, "", 11);
        status.setTextColor(AiUiTheme.SECONDARY_TEXT);
        final LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(context, 6), 0, 0);
        card.addView(status, statusParams);

        final boolean[] enabled = {settings.isSaveVersionsEnabled()};
        final int[] maxVersions = {settings.getMaxVersionsToSave()};
        final boolean[] updatingToggle = {false};
        final Runnable updateControls = () -> {
            toggle.setChecked(enabled[0]);
            minus.setEnabled(enabled[0] && maxVersions[0] > 1);
            plus.setEnabled(enabled[0] && maxVersions[0] < AiDiffHistoryStore.MAX_VERSIONS_TO_SAVE);
            maxLabel.setEnabled(enabled[0]);
            count.setText(Integer.toString(maxVersions[0]));
            count.setTextColor(enabled[0] ? AiUiTheme.PRIMARY_TEXT : AiUiTheme.SECONDARY_TEXT);
            status.setText(enabled[0]
                    ? "ON — keeping last " + maxVersions[0] + " full versions for this file"
                    : "OFF — diff-only mode (0 extra snapshot storage)");
            status.setTextColor(enabled[0] ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY_TEXT);
        };
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingToggle[0]) {
                return;
            }
            try {
                diffHistoryStore.saveVersionSettings(currentFile, isChecked, maxVersions[0]);
                enabled[0] = isChecked;
                updateControls.run();
            } catch (IOException | IllegalArgumentException exception) {
                updatingToggle[0] = true;
                toggle.setChecked(enabled[0]);
                updatingToggle[0] = false;
                showError(exception.getMessage());
            }
        });
        final View.OnClickListener stepperClick = view -> {
            final int newMax = maxVersions[0] + (view == plus ? 1 : -1);
            if (newMax < 1 || newMax > AiDiffHistoryStore.MAX_VERSIONS_TO_SAVE) {
                return;
            }
            try {
                diffHistoryStore.saveVersionSettings(currentFile, enabled[0], newMax);
                maxVersions[0] = newMax;
                updateControls.run();
            } catch (IOException | IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        };
        minus.setOnClickListener(stepperClick);
        plus.setOnClickListener(stepperClick);
        updateControls.run();
        return card;
    }

    private void showDiffExportChoiceDialog(final android.content.Context context,
            final List<AiDiffHistoryStore.Entry> history) {
        final LinearLayout body = verticalLayout(context);
        final Button addedOnly = AiUiTheme.button(
                context, "Added Content Only (.txt)", AiUiTheme.ButtonKind.PRIMARY);
        final Button fullDiff = AiUiTheme.button(
                context, "Full Diff Log (+ / -) (.txt)", AiUiTheme.ButtonKind.SECONDARY);
        final TextView recommendation = AiUiTheme.label(context,
                "Added-only is best for updating Anki decks.", 12);
        recommendation.setTextColor(AiUiTheme.SECONDARY_TEXT);
        body.addView(recommendation);
        addFooterButton(context, body, addedOnly);
        addFooterButton(context, body, fullDiff);
        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(cancel, AiUiTheme.weightedButtonParams(context));
        final Dialog choiceDialog = AiUiTheme.showCompactDialog(
                context, "Export All Diffs", body, footer);
        cancel.setOnClickListener(view -> choiceDialog.dismiss());
        addedOnly.setOnClickListener(view -> {
            choiceDialog.dismiss();
            exportDiffHistory(context, history, true);
        });
        fullDiff.setOnClickListener(view -> {
            choiceDialog.dismiss();
            exportDiffHistory(context, history, false);
        });
    }

    private void exportDiffHistory(final android.content.Context context,
            final List<AiDiffHistoryStore.Entry> history, final boolean addedOnly) {
        try {
            final String filename = createDiffExportFilename(addedOnly);
            final String text = AiDiffHistoryStore.exportDiffsAsText(
                    history, currentFile == null ? "" : currentFile.getName(), addedOnly);
            final File file = saveDiffExport(filename, text);
            Toast.makeText(context, "Saved " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            showDiffExportSavedDialog(context, file, text);
        } catch (IOException exception) {
            showError(exception.getMessage());
        }
    }

    private String createDiffExportFilename(final boolean addedOnly) throws IOException {
        if (currentFile == null) {
            throw new IOException("The current note is not available for diff export.");
        }
        final String noteName = currentFile.getName();
        final int extensionIndex = noteName.lastIndexOf('.');
        final String noteBaseName = extensionIndex > 0
                ? noteName.substring(0, extensionIndex) : noteName;
        String base = noteBaseName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "note";
        }
        final String kind = addedOnly ? "added_diffs" : "all_diffs";
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT)
                .format(new Date());
        return base + "_" + kind + "_" + timestamp + ".txt";
    }

    private File saveDiffExport(final String filename, final String text) throws IOException {
        final File parent = currentFile == null ? null : currentFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The current note's folder is not available.");
        }
        final int extensionIndex = filename.lastIndexOf('.');
        final String stem = extensionIndex < 0 ? filename : filename.substring(0, extensionIndex);
        final String extension = extensionIndex < 0 ? "" : filename.substring(extensionIndex);
        for (int suffix = 0; ; suffix++) {
            final File file = new File(parent, stem + (suffix == 0 ? "" : "_" + suffix) + extension);
            if (!file.createNewFile()) {
                continue;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
                return file;
            } catch (IOException exception) {
                if (!file.delete()) {
                    exception.addSuppressed(new IOException("Could not remove incomplete diff export: "
                            + file.getAbsolutePath()));
                }
                throw exception;
            }
        }
    }

    private void showDiffExportSavedDialog(final android.content.Context context,
            final File file, final String text) {
        final LinearLayout body = verticalLayout(context);
        final TextView location = AiUiTheme.label(context, file.getAbsolutePath(), 13);
        location.setTextColor(AiUiTheme.SECONDARY_TEXT);
        body.addView(location);
        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button copy = AiUiTheme.button(context, "Copy Text", AiUiTheme.ButtonKind.PRIMARY);
        final Button done = AiUiTheme.button(context, "Done", AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(copy, AiUiTheme.weightedButtonParams(context));
        footer.addView(done, AiUiTheme.weightedButtonParams(context));
        final Dialog savedDialog = AiUiTheme.showCompactDialog(
                context, "Diff Export Saved", body, footer);
        copy.setOnClickListener(view -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showError("Could not access the clipboard.");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AI Diff Export", text));
            Toast.makeText(context, "Diff text copied.", Toast.LENGTH_SHORT).show();
        });
        done.setOnClickListener(view -> savedDialog.dismiss());
    }

    private LinearLayout createHistoryCard(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry, final Runnable onDelete) {
        final LinearLayout card = verticalLayout(context);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setElevation(dp(context, 2));
        final GradientDrawable cardShape = AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                AiUiTheme.INPUT_BORDER, dp(context, 1));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED), cardShape, null));
        card.setClickable(true);
        card.setFocusable(true);

        final LinearLayout titleRow = AiUiTheme.actionRow(context);
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        final TextView date = AiUiTheme.label(context,
                dateFormat.format(new Date(entry.getTimestampMs())), 12);
        date.setTextColor(AiUiTheme.SECONDARY_TEXT);
        titleRow.addView(date, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView delete = AiUiTheme.label(context, "🗑 Delete", 11);
        delete.setTextColor(AiUiTheme.ACCENT);
        delete.setPadding(dp(context, 8), dp(context, 5), dp(context, 8), dp(context, 5));
        delete.setBackground(AiUiTheme.rounded(AiUiTheme.SECONDARY, dp(context, 12),
                AiUiTheme.SECONDARY_BORDER, dp(context, 1)));
        delete.setClickable(true);
        delete.setFocusable(true);
        delete.setContentDescription("Delete this diff");
        delete.setOnClickListener(view -> onDelete.run());
        titleRow.addView(delete);
        final TextView provider = AiUiTheme.label(context, displayProvider(entry.getProvider()), 11);
        provider.setTextColor(AiUiTheme.ACCENT);
        provider.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        provider.setBackground(AiUiTheme.rounded(Color.rgb(36, 19, 23), dp(context, 12),
                AiUiTheme.SECONDARY_BORDER, dp(context, 1)));
        titleRow.addView(provider);
        card.addView(titleRow);

        final TextView instruction = AiUiTheme.label(context,
                entry.getInstruction().isEmpty() ? "No instruction recorded" : entry.getInstruction(), 14);
        instruction.setMaxLines(2);
        instruction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionParams.setMargins(0, dp(context, 8), 0, 0);
        card.addView(instruction, instructionParams);

        if (!entry.getModel().isEmpty()) {
            final TextView model = AiUiTheme.label(context, entry.getModel(), 11);
            model.setTextColor(AiUiTheme.SECONDARY_TEXT);
            final LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            modelParams.setMargins(0, dp(context, 4), 0, 0);
            card.addView(model, modelParams);
        }

        final LinearLayout counts = AiUiTheme.actionRow(context);
        final TextView added = AiUiTheme.label(context, "+" + entry.getAddedCount(), 12);
        added.setTextColor(AiUiTheme.ADDED_TEXT);
        added.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        final TextView removed = AiUiTheme.label(context, "-" + entry.getRemovedCount(), 12);
        removed.setTextColor(AiUiTheme.REMOVED_TEXT);
        removed.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        counts.addView(added);
        final LinearLayout.LayoutParams removedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removedParams.setMargins(dp(context, 12), 0, 0, 0);
        counts.addView(removed, removedParams);
        final LinearLayout.LayoutParams countsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countsParams.setMargins(0, dp(context, 8), 0, 0);
        card.addView(counts, countsParams);
        return card;
    }

    private void showDiffDetailDialog(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry, final EditText editText, final Dialog historyDialog) {
        final AiDiffHistoryStore.Entry currentEntry;
        try {
            AiDiffHistoryStore.Entry found = null;
            for (AiDiffHistoryStore.Entry candidate : diffHistoryStore.getHistory(currentFile)) {
                if (candidate.getId().equals(entry.getId())) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                showError("This diff entry is no longer available.");
                return;
            }
            currentEntry = found;
        } catch (IOException exception) {
            showError(exception.getMessage());
            return;
        }
        final LinearLayout body = verticalLayout(context);
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        final TextView timestamp = AiUiTheme.label(context,
                dateFormat.format(new Date(currentEntry.getTimestampMs())), 12);
        timestamp.setTextColor(AiUiTheme.SECONDARY_TEXT);
        body.addView(timestamp);

        final TextView instruction = AiUiTheme.label(context,
                currentEntry.getInstruction().isEmpty()
                        ? "No instruction recorded" : currentEntry.getInstruction(), 15);
        final LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionParams.setMargins(0, dp(context, 8), 0, dp(context, 12));
        body.addView(instruction, instructionParams);

        final LinearLayout diffCard = verticalLayout(context);
        diffCard.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        diffCard.setBackground(AiUiTheme.inputBackground(context));
        renderFormattedDiff(context, diffCard, currentEntry.getDiffText());
        body.addView(diffCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout footer = verticalLayout(context);
        final Button copyButton = AiUiTheme.button(context, "Copy Diff", AiUiTheme.ButtonKind.SECONDARY);
        final Button backButton = AiUiTheme.button(context, "Back", AiUiTheme.ButtonKind.SECONDARY);
        addFooterButton(context, footer, copyButton);
        final Button restoreButton;
        if (currentEntry.hasBeforeSnapshot()) {
            restoreButton = AiUiTheme.button(
                    context, "Restore Before This Edit", AiUiTheme.ButtonKind.PRIMARY);
            addFooterButton(context, footer, restoreButton);
        } else {
            restoreButton = null;
            final TextView note = AiUiTheme.label(
                    context, "Diff-only entry (no full snapshot saved)", 12);
            note.setTextColor(AiUiTheme.SECONDARY_TEXT);
            final LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteParams.setMargins(0, 0, 0, dp(context, 6));
            footer.addView(note, noteParams);
        }
        addFooterButton(context, footer, backButton);
        final Dialog detailDialog = AiUiTheme.showDialog(context, "Diff Detail", body, footer, null);
        copyButton.setOnClickListener(view -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showError("Could not access the clipboard.");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AI Edit Diff", currentEntry.getDiffText()));
            Toast.makeText(context, "Diff copied.", Toast.LENGTH_SHORT).show();
        });
        if (restoreButton != null) {
            restoreButton.setOnClickListener(view -> showRestoreConfirmation(
                    context, currentEntry, editText, detailDialog, historyDialog));
        }
        backButton.setOnClickListener(view -> detailDialog.dismiss());
    }

    private void showRestoreConfirmation(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry, final EditText editText, final Dialog detailDialog,
            final Dialog historyDialog) {
        final LinearLayout body = verticalLayout(context);
        body.addView(AiUiTheme.label(context,
                "Replace the current note with the snapshot from before this edit?", 14));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button restoreButton = AiUiTheme.button(context, "Restore", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(restoreButton, AiUiTheme.weightedButtonParams(context));
        final Dialog confirmation = AiUiTheme.showDialog(context, "Restore Note", body, actions, null);
        cancelButton.setOnClickListener(view -> confirmation.dismiss());
        restoreButton.setOnClickListener(view -> {
            editText.getText().replace(0, editText.getText().length(), entry.getBeforeSnapshot());
            confirmation.dismiss();
            detailDialog.dismiss();
            historyDialog.dismiss();
            onAfterApply.run();
            Toast.makeText(context, "Note restored.", Toast.LENGTH_SHORT).show();
        });
    }

    private void showClearHistoryConfirmation(final android.content.Context context, final EditText editText,
            final Dialog historyDialog, final TextView allDiffsButton) {
        final LinearLayout body = verticalLayout(context);
        body.addView(AiUiTheme.label(context,
                "Permanently clear all saved edit history for this note?", 14));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button clearButton = AiUiTheme.button(context, "Clear History", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(clearButton, AiUiTheme.weightedButtonParams(context));
        final Dialog confirmation = AiUiTheme.showDialog(context, "Clear Edit History", body, actions, null);
        cancelButton.setOnClickListener(view -> confirmation.dismiss());
        clearButton.setOnClickListener(view -> {
            try {
                diffHistoryStore.clearHistory(currentFile);
                updateAllDiffsButton(allDiffsButton);
            } catch (IOException exception) {
                confirmation.dismiss();
                showError(exception.getMessage());
                return;
            }
            confirmation.dismiss();
            historyDialog.dismiss();
            showAllDiffsDialog(context, editText, allDiffsButton);
        });
    }

    private void showDeleteDiffConfirmation(final android.content.Context context,
            final AiDiffHistoryStore.Entry entry, final EditText editText,
            final Dialog historyDialog, final TextView allDiffsButton) {
        final LinearLayout body = verticalLayout(context);
        body.addView(AiUiTheme.label(context,
                "Permanently delete only this diff entry?", 14));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button deleteButton = AiUiTheme.button(context, "Delete Diff", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(deleteButton, AiUiTheme.weightedButtonParams(context));
        final Dialog confirmation = AiUiTheme.showDialog(
                context, "Delete Diff", body, actions, null);
        cancelButton.setOnClickListener(view -> confirmation.dismiss());
        deleteButton.setOnClickListener(view -> {
            try {
                diffHistoryStore.deleteEntry(context, currentFile.getAbsolutePath(), entry.getId());
                updateAllDiffsButton(allDiffsButton);
            } catch (IOException exception) {
                confirmation.dismiss();
                showError(exception.getMessage());
                return;
            }
            confirmation.dismiss();
            historyDialog.dismiss();
            showAllDiffsDialog(context, editText, allDiffsButton);
        });
    }

    private static String displayProvider(final String providerId) {
        final AiProviderRouter.Provider provider = AiProviderRouter.Provider.fromId(providerId);
        return provider == null ? providerId : provider.getDisplayName();
    }

    private static void addFooterButton(final android.content.Context context, final LinearLayout footer,
            final Button button) {
        final LinearLayout row = AiUiTheme.actionRow(context);
        row.addView(button, AiUiTheme.weightedButtonParams(context));
        final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(context, 6));
        footer.addView(row, rowParams);
    }

    private void handleIntentResult(final EditText editText, final String originalMarkdown,
            final GeminiMarkdownHelper.IntentResult result, final String updatedMarkdown,
            final FolderSearchHelper.SearchResult folderResults, final String instruction,
            final String provider, final String model,
            final MarkdownSectionParser.Section scopedSection, final String updatedSection) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        if (currentFile == null && ("edit".equals(result.getMode())
                || "navigate".equals(result.getMode()))) {
            showError("Open a note to use Edit or Jump.");
            return;
        }
        if ("edit".equals(result.getMode())) {
            showDiffPreview(editText, originalMarkdown, updatedMarkdown, instruction, provider, model,
                    null, scopedSection, updatedSection);
        } else if ("answer".equals(result.getMode())) {
            final String matchedFile = result.getMatchedFile() != null
                    ? result.getMatchedFile()
                    : folderResults == null ? null : folderResults.getBestMatch();
            showAnswerDialog(editText, originalMarkdown, result.getAnswer(), matchedFile,
                    instruction, provider, model, scopedSection);
        } else if ("navigate".equals(result.getMode())) {
            if (scrollCallback == null) {
                Toast.makeText(context, "Could not jump to the requested section.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            scrollCallback.scrollTo(result.getTargetText(), found -> {
                final android.content.Context currentContext = getContext();
                if (currentContext != null) {
                    Toast.makeText(currentContext, found
                                    ? "Jumped to section: " + result.getReason()
                                    : "Could not find the requested section.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else if ("create_note".equals(result.getMode())) {
            showNewNotePreview(result.getFilename(), result.getNoteContent());
        } else {
            showError("The AI provider returned an unsupported intent.");
        }
    }

    private void showScopePicker(final android.content.Context context, final String markdown,
            final MarkdownTreeViewHelper.SectionSelectionListener listener) {
        final List<MarkdownSectionParser.Section> sections = MarkdownSectionParser.parse(markdown);
        final LinearLayout body = verticalLayout(context);
        final Dialog[] picker = new Dialog[1];
        if (sections.isEmpty()) {
            final TextView empty = AiUiTheme.label(context,
                    "This Markdown note has no headings to scope.", 14);
            empty.setTextColor(AiUiTheme.SECONDARY_TEXT);
            body.addView(empty);
        } else {
            body.addView(MarkdownTreeViewHelper.create(context, sections, section -> {
                picker[0].dismiss();
                listener.onSelected(section);
            }));
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        actions.addView(cancel, AiUiTheme.weightedButtonParams(context));
        picker[0] = AiUiTheme.showDialog(context, "Choose Section Scope", body, actions, null);
        cancel.setOnClickListener(view -> picker[0].dismiss());
    }

    private void startVoiceInput() {
        final Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            voiceLauncher.launch(intent);
        } catch (ActivityNotFoundException exception) {
            final android.content.Context context = getContext();
            if (context != null) {
                Toast.makeText(context, "No speech recognition app is available.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void onVoiceResult(final ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        final List<String> matches = result.getData().getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (matches == null || matches.isEmpty() || activeInstructionInput == null) {
            return;
        }
        final EditText input = activeInstructionInput;
        final String recognizedText = matches.get(0);
        final int length = input.length();
        input.getText().append((length > 0 && !Character.isWhitespace(
                input.getText().charAt(length - 1)) ? " " : "") + recognizedText);
    }

    private void pickImage(final ImageResultCallback callback) {
        pendingImageCallback = callback;
        imageLauncher.launch("image/*");
    }

    private void onImageSelected(final android.net.Uri uri) {
        final ImageResultCallback callback = pendingImageCallback;
        pendingImageCallback = null;
        if (uri == null || callback == null || getContext() == null) {
            return;
        }
        try {
            callback.onSelected(ImageEncodingHelper.encodeJpeg(
                    getContext().getContentResolver(), uri));
        } catch (IOException exception) {
            showError(exception.getMessage());
        }
    }

    private void showTutorChat(final EditText editText, final String markdown,
            final String imageBase64, final MarkdownSectionParser.Section scopedSection) {
        final android.content.Context context = getContext();
        if (context == null || currentFile == null) {
            return;
        }
        final String chatKey = currentFile.getAbsolutePath()
                + (scopedSection == null ? "" : "#" + scopedSection.getStartOffset()
                        + ":" + scopedSection.getEndOffset());
        AiStudyDialogs.showTutorChat(context, chatKey, markdown,
                (recentTurns, question, image, callback) -> EXECUTOR.execute(() -> {
                    try {
                        final StringBuilder history = new StringBuilder();
                        for (int index = 0; index < recentTurns.size() - 1; index++) {
                            final AiStudyDialogs.Turn turn = recentTurns.get(index);
                            history.append(turn.isUser() ? "Student: " : "Tutor: ")
                                    .append(turn.getText()).append('\n');
                        }
                        final String prompt = (history.length() == 0 ? "" : "Recent conversation:\n"
                                + history + "\n")
                                + "Answer the student's latest question clearly and use the note as context:\n"
                                + question;
                        final String answer = new AiProviderRouter(settingsStore)
                                .generateStudyAnswer(markdown, prompt, image);
                        MAIN_HANDLER.post(() -> callback.onResult(answer, null));
                    } catch (IOException exception) {
                        MAIN_HANDLER.post(() -> callback.onResult(null, exception.getMessage()));
                    }
                }), text -> appendToNotePreview(editText, editText.getText().toString(),
                        "\n\n" + text, "Append tutor response", "tutor", "", scopedSection),
                input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }, imageBase64, createOutputRefineRequest());
    }

    private void showMnemonicsDialog(final EditText editText, final String markdown,
            final MarkdownSectionParser.Section selectedSection) {
        final android.content.Context context = getContext();
        if (context == null || currentFile == null) {
            return;
        }
        AiMnemonicsDialog.show(context, markdown, currentFile, mnemonicsStore, selectedSection, section ->
                new AiProviderRouter(settingsStore).generateNonTeacherRawCompletion(
                        section.getContent(), AiMnemonicsDialog.buildSystemPrompt(),
                        "Create the mnemonic for this section."),
                new AiMnemonicsDialog.Actions() {
                    @Override
                    public void addToNote(final MarkdownSectionParser.Section section,
                            final String title, final String breakdown,
                            final AiMnemonicsStore store) {
                        final int insertAt = section.getEndOffset();
                        final StringBuilder block = new StringBuilder();
                        if (insertAt > 0 && insertAt <= markdown.length()
                                && markdown.charAt(insertAt - 1) != '\n') {
                            block.append('\n');
                        }
                        block.append("\n> **Mnemonic — ").append(title).append(":**\n");
                        for (String line : breakdown.split("\\r?\\n")) {
                            block.append("> ").append(line.trim()).append('\n');
                        }
                        final String updated;
                        try {
                            updated = MarkdownSectionReplacement.replace(markdown, section,
                                    section.getContent() + block);
                        } catch (IllegalArgumentException exception) {
                            showError(exception.getMessage());
                            return;
                        }
                        showDiffPreview(editText, markdown, updated,
                                "Add mnemonic for " + section.getTitle(),
                                "mnemonics", "", () -> {
                                    try {
                                        store.saveMnemonic(currentFile.getAbsolutePath(),
                                                currentFile.getName(), section.getTitle(),
                                                section.getRawHeadingLine(), title, breakdown, true);
                                    } catch (IOException exception) {
                                        showError("Mnemonic was applied, but could not be saved: "
                                                + exception.getMessage());
                                    }
                                }, section, section.getContent() + block);
                    }

                    @Override
                    public void jumpToHeading(final String headingTitle) {
                        if (scrollCallback != null) {
                            scrollCallback.scrollTo(headingTitle, found -> { });
                        }
                    }

                    @Override
                    public void showError(final String message) {
                        AiAssistantModule.this.showError(message);
                    }
                }, createOutputRefineRequest(), createOutputRefineMicCallback());
    }

    private void showMnemonicsVault(final android.content.Context context) {
        AiMnemonicsDialog.showVault(context, mnemonicsStore,
                new AiMnemonicsDialog.Actions() {
                    @Override
                    public void addToNote(final MarkdownSectionParser.Section section,
                            final String title, final String breakdown,
                            final AiMnemonicsStore store) {
                        showError("Open a note to add a mnemonic.");
                    }

                    @Override
                    public void jumpToHeading(final String headingTitle) {
                        Toast.makeText(context, "Open the note first, then use Jump.",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void showError(final String message) {
                        AiAssistantModule.this.showError(message);
                    }
                });
    }

    private void selectTeacherSource(final EditText editText, final String markdown,
            final MarkdownSectionParser.Section selectedSection) {
        if (selectedSection != null) {
            startVoiceTeacher(editText, selectedSection.getContent(), selectedSection.getTitle(),
                    settingsStore.getTeacherStyle(), selectedSection);
            return;
        }
        final int rawSelectionStart = editText.getSelectionStart();
        final int rawSelectionEnd = editText.getSelectionEnd();
        final int selectionStart = Math.min(rawSelectionStart, rawSelectionEnd);
        final int selectionEnd = Math.max(rawSelectionStart, rawSelectionEnd);
        if (selectionStart >= 0 && selectionEnd > selectionStart
                && selectionEnd - selectionStart >= 10
                && selectionEnd <= markdown.length()) {
            final String selectedText = markdown.substring(selectionStart, selectionEnd);
            String heading = "";
            for (MarkdownSectionParser.Section section : MarkdownSectionParser.parse(markdown)) {
                if (selectionStart >= section.getStart() && selectionEnd <= section.getEnd()) {
                    heading = section.getTitle();
                }
            }
            startVoiceTeacher(editText, selectedText, heading, settingsStore.getTeacherStyle(), null);
            return;
        }

        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final List<MarkdownSectionParser.Section> sections = MarkdownSectionParser.parse(markdown);
        final LinearLayout body = verticalLayout(context);
        final Dialog[] picker = new Dialog[1];
        final AiVoiceTeacherHelper.TeacherStyle[] selectedStyle = {settingsStore.getTeacherStyle()};
        final HorizontalScrollView styleSelector = AiUiTheme.teacherStyleSelector(
                context, selectedStyle[0], style -> {
                    selectedStyle[0] = style;
                    settingsStore.saveTeacherStyle(style);
                });
        final LinearLayout.LayoutParams styleSelectorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        styleSelectorParams.setMargins(0, 0, 0, dp(context, 12));
        body.addView(styleSelector, styleSelectorParams);
        if (!sections.isEmpty()) {
            body.addView(MarkdownTreeViewHelper.create(context, sections, section -> {
                picker[0].dismiss();
                startVoiceTeacher(editText, section.getContent(), section.getTitle(),
                        selectedStyle[0], section);
            }));
        }
        if (!markdown.trim().isEmpty()) {
            addTeacherSectionOption(context, body, "Whole Note", markdown, "", picker, editText,
                    selectedStyle);
        }
        if (markdown.trim().isEmpty() && sections.isEmpty()) {
            final TextView empty = AiUiTheme.label(context,
                    "This note has no Markdown headings and no content to teach.", 14);
            empty.setTextColor(AiUiTheme.SECONDARY_TEXT);
            body.addView(empty);
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        actions.addView(cancel, AiUiTheme.weightedButtonParams(context));
        picker[0] = AiUiTheme.showDialog(context, "Select Section to Learn", body, actions, null);
        cancel.setOnClickListener(view -> picker[0].dismiss());
    }

    private void addTeacherSectionOption(final android.content.Context context,
            final LinearLayout body, final String label, final String content, final String heading,
            final Dialog[] picker, final EditText editText,
            final AiVoiceTeacherHelper.TeacherStyle[] selectedStyle) {
        final TextView option = AiUiTheme.label(context, label, 15);
        option.setPadding(dp(context, 14), dp(context, 13), dp(context, 14), dp(context, 13));
        option.setBackground(new RippleDrawable(ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                        AiUiTheme.INPUT_BORDER, dp(context, 1)), null));
        option.setClickable(true);
        option.setFocusable(true);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 8));
        body.addView(option, params);
        option.setOnClickListener(view -> {
            picker[0].dismiss();
            startVoiceTeacher(editText, content, heading, selectedStyle[0],
                    "Whole Note".equals(label) ? MarkdownSectionParser.wholeNote(content) : null);
        });
    }

    private void startVoiceTeacher(final EditText editText, final String section,
            final String heading, final AiVoiceTeacherHelper.TeacherStyle style,
            final MarkdownSectionParser.Section noteScope) {
        final android.content.Context context = getContext();
        if (context == null || section == null || section.trim().isEmpty()) {
            return;
        }
        final AiVoiceTeacherHelper.TeacherStyle selectedStyle = style == null
                ? settingsStore.getTeacherStyle() : style;
        final LinearLayout body = verticalLayout(context);
        final ProgressBar progress = new ProgressBar(context);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        body.addView(progress);
        body.addView(AiUiTheme.label(context, "Preparing your spoken lesson…", 14));
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        actions.addView(cancel, AiUiTheme.weightedButtonParams(context));
        final AiUiTheme.MinimizableDialog loadingDialog = AiUiTheme.showCompactDialog(
                context, "AI Voice Teacher", body, actions);
        cancel.setOnClickListener(view -> loadingDialog.dismiss());

        EXECUTOR.execute(() -> {
            try {
                final String lesson = AiVoiceTeacherHelper.cleanForTts(
                        new AiProviderRouter(settingsStore).generateTeacherText(
                                AiVoiceTeacherHelper.SYSTEM_PROMPT,
                                AiVoiceTeacherHelper.lessonPrompt(section, selectedStyle)));
                if (lesson.trim().isEmpty()) {
                    throw new IOException("The AI provider returned an empty spoken lesson.");
                }
                MAIN_HANDLER.post(() -> {
                    AiUiTheme.runWhenResumed(loadingDialog, () -> {
                        if (getContext() == null) {
                            loadingDialog.dismiss();
                            return;
                        }
                        loadingDialog.dismiss();
                        showVoiceTeacherPlayer(
                                editText, section, heading, lesson, selectedStyle, noteScope);
                    });
                });
            } catch (IOException exception) {
                MAIN_HANDLER.post(() -> {
                    AiUiTheme.runWhenResumed(loadingDialog, () -> {
                        loadingDialog.dismiss();
                        if (getContext() != null) {
                            showError(exception.getMessage());
                        }
                    });
                });
            }
        });
    }

    private void showVoiceTeacherPlayer(final EditText editText, final String section,
            final String heading, final String lesson,
            final AiVoiceTeacherHelper.TeacherStyle style,
            final MarkdownSectionParser.Section noteScope) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        AiVoiceTeacherDialog.show(context, section, heading, lesson, style,
                (selectedStyle, callback) -> {
                    settingsStore.saveTeacherStyle(selectedStyle);
                    EXECUTOR.execute(() -> {
                        try {
                            final String updatedLesson = AiVoiceTeacherHelper.cleanForTts(
                                    new AiProviderRouter(settingsStore).generateTeacherText(
                                            AiVoiceTeacherHelper.SYSTEM_PROMPT,
                                            AiVoiceTeacherHelper.lessonPrompt(section, selectedStyle)));
                            if (updatedLesson.trim().isEmpty()) {
                                throw new IOException("The AI provider returned an empty spoken lesson.");
                            }
                            MAIN_HANDLER.post(() -> callback.onResult(updatedLesson, null));
                        } catch (IOException exception) {
                            MAIN_HANDLER.post(() -> callback.onResult(null, exception.getMessage()));
                        }
                    });
                },
                (doubt, taughtSection, currentLesson, selectedStyle, callback) ->
                        EXECUTOR.execute(() -> {
                    try {
                        final String answer = new AiProviderRouter(settingsStore).generateTeacherText(
                                AiVoiceTeacherHelper.SYSTEM_PROMPT,
                                AiVoiceTeacherHelper.doubtPrompt(
                                        taughtSection, currentLesson, doubt, selectedStyle));
                        MAIN_HANDLER.post(() -> callback.onResult(answer, null));
                    } catch (IOException exception) {
                        MAIN_HANDLER.post(() -> callback.onResult(null, exception.getMessage()));
                    }
                }),
                (explanation, taughtHeading) -> {
                    final String label = taughtHeading == null || taughtHeading.trim().isEmpty()
                            ? "AI Teacher Explanation"
                            : "AI Teacher Explanation: " + taughtHeading.replace('\n', ' ').trim();
                    appendToNotePreview(editText, editText.getText().toString(),
                            "\n\n## " + label + "\n\n" + explanation.trim() + "\n",
                            "Save AI Teacher explanation", "teacher", "", noteScope);
                },
                input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }, createOutputRefineRequest());
    }

    private void appendToNotePreview(final EditText editText, final String original,
            final String content, final String instruction, final String provider, final String model) {
        appendToNotePreview(editText, original, content, instruction, provider, model, null);
    }

    private void appendToNotePreview(final EditText editText, final String original,
            final String content, final String instruction, final String provider, final String model,
            final MarkdownSectionParser.Section scopedSection) {
        try {
            final String updated;
            if (scopedSection == null) {
                final String patch = new JSONObject().put("edits", new JSONArray().put(new JSONObject()
                        .put("action", "append_end").put("target", "").put("content", content))).toString();
                updated = GeminiMarkdownHelper.applyEdits(original, patch);
            } else {
                updated = MarkdownSectionReplacement.replace(original, scopedSection,
                        scopedSection.getContent() + content);
            }
            showDiffPreview(editText, original, updated, instruction, provider, model,
                    null, scopedSection, scopedSection == null ? null
                            : scopedSection.getContent() + content);
        } catch (org.json.JSONException | IOException | IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private AiOutputRefineHelper.RefineRequest createOutputRefineRequest() {
        return (currentOutput, instruction, callback) -> EXECUTOR.execute(() -> {
            try {
                final String output = new AiProviderRouter(settingsStore).generateNonTeacherRawCompletion(
                        AiOutputRefineHelper.buildSystemPrompt(),
                        AiOutputRefineHelper.buildPrompt(currentOutput, instruction));
                MAIN_HANDLER.post(() -> callback.onResult(output, null));
            } catch (IOException exception) {
                MAIN_HANDLER.post(() -> callback.onResult(null, exception.getMessage()));
            }
        });
    }

    private AiOutputRefineHelper.MicCallback createOutputRefineMicCallback() {
        return input -> {
            activeInstructionInput = input;
            startVoiceInput();
        };
    }

    private static String formatMermaid(final String response) throws IOException {
        if (response == null || response.trim().isEmpty()) {
            throw new IOException("The AI provider returned an empty diagram.");
        }
        final String trimmed = response.trim();
        if (trimmed.startsWith("```mermaid") && trimmed.endsWith("```")) {
            return trimmed;
        }
        final String content = GeminiMarkdownHelper.stripMarkdownFences(trimmed).trim();
        if (content.startsWith("flowchart ") || content.startsWith("graph ")
                || content.startsWith("mindmap")) {
            return "```mermaid\n" + content + "\n```";
        }
        throw new IOException("The AI provider did not return a Mermaid flowchart or mind-map.");
    }

    private void showAnkiPreview(final android.content.Context context,
            final List<AnkiCsvHelper.Card> cards, final String csv) {
        final String[] currentCsv = {csv};
        final LinearLayout body = verticalLayout(context);
        final TextView count = AiUiTheme.label(context, cards.size() + " cards", 14);
        body.addView(count);
        final LinearLayout cardsPreview = verticalLayout(context);
        body.addView(cardsPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderAnkiCards(context, cardsPreview, cards);
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout refineRow = AiUiTheme.actionRow(context);
        final Button refine = AiUiTheme.button(
                context, "✏ Refine / Edit", AiUiTheme.ButtonKind.SECONDARY);
        refineRow.addView(refine, AiUiTheme.weightedButtonParams(context));
        actions.addView(refineRow);
        final LinearLayout fileActions = AiUiTheme.actionRow(context);
        final Button copy = AiUiTheme.button(context, "Copy CSV", AiUiTheme.ButtonKind.SECONDARY);
        final Button save = AiUiTheme.button(context, "Save .csv", AiUiTheme.ButtonKind.PRIMARY);
        fileActions.addView(copy, AiUiTheme.weightedButtonParams(context));
        fileActions.addView(save, AiUiTheme.weightedButtonParams(context));
        actions.addView(fileActions);
        final Dialog dialog = AiUiTheme.showDialog(context, "Anki CSV Preview", body, actions, null);
        refine.setOnClickListener(view -> AiOutputRefineHelper.show(context, currentCsv[0],
                createOutputRefineRequest(), refined -> {
                    try {
                        final List<AnkiCsvHelper.Card> updatedCards = AnkiCsvHelper.parseCsv(refined);
                        currentCsv[0] = AnkiCsvHelper.toCsv(updatedCards);
                        count.setText(updatedCards.size() + " cards");
                        renderAnkiCards(context, cardsPreview, updatedCards);
                    } catch (IOException exception) {
                        showError(exception.getMessage());
                    }
                }, input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }));
        copy.setOnClickListener(view -> {
            final android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showError("Could not access the clipboard.");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Anki CSV", currentCsv[0]));
            Toast.makeText(context, "CSV copied.", Toast.LENGTH_SHORT).show();
        });
        save.setOnClickListener(view -> {
            try {
                final File file = saveAnkiCsv(currentCsv[0]);
                dialog.dismiss();
                Toast.makeText(context, "Saved " + file.getName(), Toast.LENGTH_SHORT).show();
            } catch (IOException exception) {
                showError(exception.getMessage());
            }
        });
    }

    private static void renderAnkiCards(final android.content.Context context,
            final LinearLayout cardsPreview, final List<AnkiCsvHelper.Card> cards) {
        cardsPreview.removeAllViews();
        for (int index = 0; index < Math.min(cards.size(), 5); index++) {
            final AnkiCsvHelper.Card card = cards.get(index);
            final LinearLayout item = verticalLayout(context);
            item.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            item.setBackground(AiUiTheme.inputBackground(context));
            item.addView(AiUiTheme.label(context, "Front: " + card.getFront(), 13));
            final TextView back = AiUiTheme.label(context, "Back: " + card.getBack(), 13);
            back.setTextColor(AiUiTheme.SECONDARY_TEXT);
            item.addView(back);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(context, 8), 0, 0);
            cardsPreview.addView(item, params);
        }
    }

    private File saveAnkiCsv(final String csv) throws IOException {
        final File parent = currentFile == null ? null : currentFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The current note's folder is not available.");
        }
        String base = currentFile.getName().replaceFirst("(?i)\\.md$", "")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "note";
        }
        for (int suffix = 0; ; suffix++) {
            final File file = new File(parent, base + "_anki"
                    + (suffix == 0 ? "" : "_" + suffix) + ".csv");
            if (!file.createNewFile()) {
                continue;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(csv.getBytes(StandardCharsets.UTF_8));
                return file;
            } catch (IOException exception) {
                if (!file.delete()) {
                    exception.addSuppressed(new IOException("Could not remove incomplete CSV: "
                            + file.getAbsolutePath()));
                }
                throw exception;
            }
        }
    }

    private void showSettingsDialog() {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final LinearLayout content = verticalLayout(context);
        final TextView providerLabel = AiUiTheme.label(context, "Primary Provider", 13);
        providerLabel.setTextColor(AiUiTheme.SECONDARY_TEXT);
        content.addView(providerLabel);
        final HorizontalScrollView providerScroll = new HorizontalScrollView(context);
        providerScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout providerRow = AiUiTheme.actionRow(context);
        providerScroll.addView(providerRow);
        content.addView(providerScroll);

        final CheckBox autoFailover = new CheckBox(context);
        autoFailover.setText("Auto-Failover on 429 / errors (including timeouts)");
        autoFailover.setTextColor(AiUiTheme.PRIMARY_TEXT);
        autoFailover.setButtonTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        autoFailover.setChecked(settingsStore.isAutoFailoverEnabled());
        content.addView(autoFailover);
        addVerticalGap(context, content, 10);

        final AiProviderRouter.Provider[] providers = AiProviderRouter.Provider.values();
        final ProviderForm[] forms = new ProviderForm[providers.length];
        final TextView[] providerChips = new TextView[providers.length];
        final LinearLayout formsContainer = verticalLayout(context);
        final String[] selectedProvider = {settingsStore.getPrimaryProvider()};
        for (int index = 0; index < providers.length; index++) {
            final AiProviderRouter.Provider provider = providers[index];
            final ProviderForm form = createProviderForm(context, provider);
            forms[index] = form;
            formsContainer.addView(form.container);
            final TextView chip = new TextView(context);
            providerChips[index] = chip;
            chip.setText(providerChipText(provider,
                    !settingsStore.getProviderApiKey(provider).isEmpty()));
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            AiUiTheme.styleChip(chip, provider.getId().equals(selectedProvider[0]));
            final LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.setMargins(0, dp(context, 7), dp(context, 7), dp(context, 9));
            providerRow.addView(chip, chipParams);
            chip.setOnClickListener(view -> {
                selectedProvider[0] = provider.getId();
                updateProviderSelection(providers, forms, providerChips, selectedProvider[0]);
            });
        }
        content.addView(formsContainer);
        updateProviderSelection(providers, forms, providerChips, selectedProvider[0]);

        final LinearLayout backupCard = verticalLayout(context);
        backupCard.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        backupCard.setElevation(dp(context, 3));
        backupCard.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 14),
                AiUiTheme.INPUT_BORDER, dp(context, 1)));
        final TextView backupTitle = AiUiTheme.label(
                context, "Backup & Transfer (aisettings.txt)", 14);
        backupTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        backupCard.addView(backupTitle);
        final TextView backupWarning = AiUiTheme.label(context,
                "API keys + GitHub PAT inside. Never share publicly.", 10);
        backupWarning.setTextColor(AiUiTheme.SECONDARY_TEXT);
        final LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningParams.setMargins(0, dp(context, 5), 0, dp(context, 10));
        backupCard.addView(backupWarning, warningParams);
        final LinearLayout backupActions = AiUiTheme.actionRow(context);
        final Button exportBackup = AiUiTheme.button(
                context, "Export aisettings.txt", AiUiTheme.ButtonKind.SECONDARY);
        final Button importBackup = AiUiTheme.button(
                context, "Import aisettings.txt", AiUiTheme.ButtonKind.SECONDARY);
        exportBackup.setTextSize(12);
        importBackup.setTextSize(12);
        backupActions.addView(exportBackup, AiUiTheme.weightedButtonParams(context));
        backupActions.addView(importBackup, AiUiTheme.weightedButtonParams(context));
        backupCard.addView(backupActions);
        final LinearLayout.LayoutParams backupParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        backupParams.setMargins(0, dp(context, 14), 0, 0);
        content.addView(backupCard, backupParams);

        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button saveButton = AiUiTheme.button(context, "Save", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(saveButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Settings", content, actions, null);
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        exportBackup.setOnClickListener(view -> exportSettingsBackup(context));
        importBackup.setOnClickListener(view -> showSettingsImportOptions(context,
                jsonText -> importSettingsIntoDialog(context, jsonText, dialog, providers,
                        forms, providerChips, selectedProvider, autoFailover)));
        for (int index = 0; index < providers.length; index++) {
            final AiProviderRouter.Provider provider = providers[index];
            final ProviderForm form = forms[index];
            form.fetchModels.setOnClickListener(view -> fetchModels(context, dialog, provider, form));
        }
        saveButton.setOnClickListener(view -> {
            for (int index = 0; index < providers.length; index++) {
                final ProviderForm form = forms[index];
                settingsStore.saveProvider(providers[index], form.apiKey.getText().toString(),
                        form.model.getText().toString(),
                        form.baseUrl == null ? "" : form.baseUrl.getText().toString());
            }
            settingsStore.savePreferences(selectedProvider[0], autoFailover.isChecked());
            dialog.dismiss();
        });
    }

    private void exportSettingsBackup(final android.content.Context context) {
        try {
            final String jsonText = AiSettingsStore.exportSettingsToJson(context);
            final File backupFile = saveSettingsBackup(context, jsonText);
            Toast.makeText(context, "Saved " + backupFile.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            showSettingsBackupExportDialog(context, backupFile, jsonText);
        } catch (IOException | IllegalStateException | SecurityException exception) {
            showSettingsBackupError(context, exception.getMessage());
        }
    }

    private File saveSettingsBackup(final android.content.Context context, final String jsonText)
            throws IOException {
        File directory = currentFile == null ? null : currentFile.getParentFile();
        if (directory == null || !directory.isDirectory()) {
            directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (directory == null) {
            directory = context.getFilesDir();
        }
        if (directory == null) {
            throw new IOException("No folder is available for saving the AI settings backup.");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create the AI settings backup folder.");
        }
        final File destination = new File(directory, "aisettings.txt");
        final File temporary = File.createTempFile(".aisettings-", ".tmp", directory);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(jsonText.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException exception) {
            if (temporary.exists() && !temporary.delete()) {
                exception.addSuppressed(new IOException("Could not remove the incomplete settings backup."));
            }
            throw exception;
        }
        if (!temporary.renameTo(destination)) {
            if (!temporary.delete()) {
                throw new IOException("Could not replace the settings backup or remove its temporary file.");
            }
            throw new IOException("Could not save " + destination.getAbsolutePath() + ".");
        }
        return destination;
    }

    private void showSettingsBackupExportDialog(final android.content.Context context,
            final File backupFile, final String jsonText) {
        final LinearLayout body = verticalLayout(context);
        final TextView location = AiUiTheme.label(context, backupFile.getAbsolutePath(), 13);
        location.setTextColor(AiUiTheme.SECONDARY_TEXT);
        body.addView(location);
        final TextView warning = AiUiTheme.label(context,
                "This file contains API keys and a GitHub token. Do not share it publicly.", 12);
        warning.setTextColor(AiUiTheme.REMOVED_TEXT);
        final LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningParams.setMargins(0, dp(context, 10), 0, 0);
        body.addView(warning, warningParams);

        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button copy = AiUiTheme.button(context, "Copy Text", AiUiTheme.ButtonKind.SECONDARY);
        final Button done = AiUiTheme.button(context, "Done", AiUiTheme.ButtonKind.PRIMARY);
        footer.addView(copy, AiUiTheme.weightedButtonParams(context));
        footer.addView(done, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showCompactDialog(
                context, "AI Settings Exported", body, footer);
        copy.setOnClickListener(view -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showSettingsBackupError(context, "Could not access the clipboard.");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AI Settings Backup", jsonText));
            Toast.makeText(context, "AI settings text copied.", Toast.LENGTH_SHORT).show();
        });
        done.setOnClickListener(view -> dialog.dismiss());
    }

    private void showSettingsImportOptions(final android.content.Context context,
            final SettingsImportCallback callback) {
        final LinearLayout body = verticalLayout(context);
        final TextView summary = AiUiTheme.label(context,
                "Load a backup from this note's folder, choose a text file, or paste JSON.", 13);
        summary.setTextColor(AiUiTheme.SECONDARY_TEXT);
        body.addView(summary);
        final LinearLayout footer = verticalLayout(context);
        final File localBackup = getCurrentFolderSettingsBackup();
        if (localBackup != null && localBackup.isFile()) {
            addSettingsImportOption(context, footer, "Load aisettings.txt here", () ->
                    readSettingsBackup(localBackup, context, callback));
        }
        addSettingsImportOption(context, footer, "Pick a .txt file", () ->
                launchSettingsImportPicker("text/*", callback));
        addSettingsImportOption(context, footer, "Browse any file", () ->
                launchSettingsImportPicker("*/*", callback));
        addSettingsImportOption(context, footer, "Paste JSON", () ->
                showPasteSettingsJson(context, callback));
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Dialog dialog = AiUiTheme.showDialog(
                context, "Import AI Settings", body, footer, null);
        cancel.setOnClickListener(view -> dialog.dismiss());
        for (int index = 0; index < footer.getChildCount() - 1; index++) {
            final View option = footer.getChildAt(index);
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (option.getTag() instanceof Runnable) {
                    ((Runnable) option.getTag()).run();
                }
            });
        }
    }

    private File getCurrentFolderSettingsBackup() {
        final File parent = currentFile == null ? null : currentFile.getParentFile();
        return parent == null ? null : new File(parent, "aisettings.txt");
    }

    private void addSettingsImportOption(final android.content.Context context,
            final LinearLayout footer, final String label, final Runnable action) {
        final Button button = AiUiTheme.button(context, label, AiUiTheme.ButtonKind.SECONDARY);
        button.setTag(action);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2));
        footer.addView(button, params);
    }

    private void launchSettingsImportPicker(final String mimeType,
            final SettingsImportCallback callback) {
        pendingSettingsImportCallback = callback;
        try {
            settingsImportLauncher.launch(mimeType);
        } catch (IllegalStateException exception) {
            pendingSettingsImportCallback = null;
            showSettingsBackupError(getContext(),
                    "Could not open the file picker: " + exception.getMessage());
        }
    }

    private void onSettingsImportPicked(final Uri uri) {
        final SettingsImportCallback callback = pendingSettingsImportCallback;
        pendingSettingsImportCallback = null;
        final android.content.Context context = getContext();
        if (uri == null || callback == null || context == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                final InputStream input = context.getContentResolver().openInputStream(uri);
                if (input == null) {
                    throw new IOException("The selected file could not be opened.");
                }
                final String jsonText = readSettingsBackupText(input);
                MAIN_HANDLER.post(() -> callback.onSelected(jsonText));
            } catch (IOException | SecurityException exception) {
                MAIN_HANDLER.post(() -> showSettingsBackupError(context, exception.getMessage()));
            }
        });
    }

    private void readSettingsBackup(final File file, final android.content.Context context,
            final SettingsImportCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                final String jsonText = readSettingsBackupText(new FileInputStream(file));
                MAIN_HANDLER.post(() -> callback.onSelected(jsonText));
            } catch (IOException | SecurityException exception) {
                MAIN_HANDLER.post(() -> showSettingsBackupError(context, exception.getMessage()));
            }
        });
    }

    private static String readSettingsBackupText(final InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_SETTINGS_BACKUP_BYTES) {
                    throw new IOException("The AI settings backup is larger than 1 MB.");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void showPasteSettingsJson(final android.content.Context context,
            final SettingsImportCallback callback) {
        final LinearLayout body = verticalLayout(context);
        final EditText jsonInput = new EditText(context);
        jsonInput.setSingleLine(false);
        jsonInput.setMinLines(7);
        jsonInput.setGravity(Gravity.TOP | Gravity.START);
        jsonInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        jsonInput.setHint("Paste the aisettings.txt JSON here");
        styleInput(jsonInput, context);
        body.addView(jsonInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button cancel = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button importButton = AiUiTheme.button(
                context, "Import", AiUiTheme.ButtonKind.PRIMARY);
        footer.addView(cancel, AiUiTheme.weightedButtonParams(context));
        footer.addView(importButton, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(
                context, "Paste AI Settings JSON", body, footer, null);
        cancel.setOnClickListener(view -> dialog.dismiss());
        importButton.setOnClickListener(view -> {
            final String jsonText = jsonInput.getText().toString();
            dialog.dismiss();
            callback.onSelected(jsonText);
        });
    }

    private void importSettingsIntoDialog(final android.content.Context context,
            final String jsonText, final Dialog settingsDialog,
            final AiProviderRouter.Provider[] providers, final ProviderForm[] forms,
            final TextView[] providerChips, final String[] selectedProvider,
            final CheckBox autoFailover) {
        EXECUTOR.execute(() -> {
            try {
                AiSettingsStore.importSettingsFromJson(context, jsonText);
                MAIN_HANDLER.post(() -> {
                    if (settingsDialog.isShowing()) {
                        for (int index = 0; index < providers.length; index++) {
                            final AiProviderRouter.Provider provider = providers[index];
                            forms[index].apiKey.setText(settingsStore.getProviderApiKey(provider));
                            forms[index].model.setText(settingsStore.getProviderModel(provider));
                            if (forms[index].baseUrl != null) {
                                forms[index].baseUrl.setText(
                                        settingsStore.getProviderBaseUrl(provider));
                            }
                            providerChips[index].setText(providerChipText(provider,
                                    !settingsStore.getProviderApiKey(provider).isEmpty()));
                        }
                        selectedProvider[0] = settingsStore.getPrimaryProvider();
                        autoFailover.setChecked(settingsStore.isAutoFailoverEnabled());
                        updateProviderSelection(
                                providers, forms, providerChips, selectedProvider[0]);
                    }
                    Toast.makeText(context, "AI settings imported successfully",
                            Toast.LENGTH_LONG).show();
                });
            } catch (IllegalArgumentException | IllegalStateException exception) {
                MAIN_HANDLER.post(() ->
                        showSettingsBackupError(context, exception.getMessage()));
            }
        });
    }

    private static void showSettingsBackupError(final android.content.Context context,
            final String message) {
        if (context == null) {
            return;
        }
        Toast.makeText(context, message == null || message.isEmpty()
                ? "AI settings backup operation failed." : message, Toast.LENGTH_LONG).show();
    }

    private ProviderForm createProviderForm(final android.content.Context context,
            final AiProviderRouter.Provider provider) {
        final LinearLayout container = verticalLayout(context);
        final EditText baseUrl = provider == AiProviderRouter.Provider.CUSTOM
                ? new EditText(context) : null;
        if (baseUrl != null) {
            addSettingsLabel(context, container, "Base URL");
            baseUrl.setSingleLine(true);
            baseUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            baseUrl.setHint("https://api.example.com/v1");
            styleInput(baseUrl, context);
            baseUrl.setText(settingsStore.getProviderBaseUrl(provider));
            container.addView(baseUrl);
            addVerticalGap(context, container, 10);
        }

        addSettingsLabel(context, container, "API Key");
        final EditText apiKey = new EditText(context);
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint("Paste your " + provider.getDisplayName() + " API key");
        styleInput(apiKey, context);
        apiKey.setText(settingsStore.getProviderApiKey(provider));
        container.addView(apiKey);
        addVerticalGap(context, container, 10);

        addSettingsLabel(context, container, "Model ID");
        final EditText model = new EditText(context);
        model.setSingleLine(true);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setHint(provider.getDefaultModel().isEmpty() ? "Enter a model ID" : provider.getDefaultModel());
        styleInput(model, context);
        model.setText(settingsStore.getProviderModel(provider));
        final LinearLayout modelRow = new LinearLayout(context);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.addView(model, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button fetchModels = AiUiTheme.button(context, "Fetch Models", AiUiTheme.ButtonKind.SECONDARY);
        modelRow.addView(fetchModels, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AiUiTheme.ACCENT));
        final LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(context, 24), dp(context, 24));
        progressParams.gravity = Gravity.CENTER_VERTICAL;
        progressParams.setMargins(dp(context, 8), 0, 0, 0);
        modelRow.addView(progress, progressParams);
        container.addView(modelRow);
        return new ProviderForm(container, baseUrl, apiKey, model, fetchModels, progress);
    }

    private void fetchModels(final android.content.Context context, final Dialog settingsDialog,
            final AiProviderRouter.Provider provider, final ProviderForm form) {
        final String apiKey = form.apiKey.getText().toString().trim();
        if (apiKey.isEmpty()) {
            showModelFetchError(context, "Enter your " + provider.getDisplayName()
                    + " API key before fetching models.");
            return;
        }
        final String baseUrl = form.baseUrl == null
                ? provider.getDefaultBaseUrl() : form.baseUrl.getText().toString().trim();
        form.fetchModels.setEnabled(false);
        form.progress.setVisibility(View.VISIBLE);
        EXECUTOR.execute(() -> {
            try {
                final List<String> models = provider == AiProviderRouter.Provider.GEMINI
                        ? GeminiMarkdownHelper.fetchAvailableModels(apiKey)
                        : OpenAiCompatibleHelper.fetchAvailableModels(provider.getDisplayName(), baseUrl, apiKey);
                MAIN_HANDLER.post(() -> {
                    if (!settingsDialog.isShowing() || getContext() == null) {
                        return;
                    }
                    form.progress.setVisibility(View.GONE);
                    form.fetchModels.setEnabled(true);
                    if (models.isEmpty()) {
                        showModelFetchError(context, "No available models were returned by "
                                + provider.getDisplayName() + ".");
                        return;
                    }
                    showModelPicker(context, models.toArray(new String[0]), form.model::setText);
                });
            } catch (IOException exception) {
                MAIN_HANDLER.post(() -> {
                    if (!settingsDialog.isShowing() || getContext() == null) {
                        return;
                    }
                    form.progress.setVisibility(View.GONE);
                    form.fetchModels.setEnabled(true);
                    showModelFetchError(context, exception.getMessage());
                });
            }
        });
    }

    private static void addSettingsLabel(final android.content.Context context, final LinearLayout parent,
            final String text) {
        final TextView label = AiUiTheme.label(context, text, 13);
        label.setTextColor(AiUiTheme.SECONDARY_TEXT);
        parent.addView(label);
    }

    private static CharSequence providerChipText(final AiProviderRouter.Provider provider,
            final boolean configured) {
        final String value = provider.getDisplayName() + "  ●";
        final SpannableString text = new SpannableString(value);
        text.setSpan(new ForegroundColorSpan(configured
                ? Color.rgb(110, 231, 183) : AiUiTheme.SECONDARY_TEXT),
                value.length() - 1, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static void updateProviderSelection(final AiProviderRouter.Provider[] providers,
            final ProviderForm[] forms, final TextView[] chips, final String selectedProvider) {
        for (int index = 0; index < providers.length; index++) {
            final boolean selected = providers[index].getId().equals(selectedProvider);
            AiUiTheme.styleChip(chips[index], selected);
            forms[index].container.setVisibility(selected ? View.VISIBLE : View.GONE);
        }
    }

    private static final class ProviderForm {
        private final LinearLayout container;
        private final EditText baseUrl;
        private final EditText apiKey;
        private final EditText model;
        private final Button fetchModels;
        private final ProgressBar progress;

        private ProviderForm(final LinearLayout container, final EditText baseUrl, final EditText apiKey,
                final EditText model, final Button fetchModels, final ProgressBar progress) {
            this.container = container;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.fetchModels = fetchModels;
            this.progress = progress;
        }
    }

    private static void showModelFetchError(final android.content.Context context, final String message) {
        final LinearLayout body = verticalLayout(context);
        final TextView detail = AiUiTheme.label(context,
                message == null || message.isEmpty()
                        ? "The provider model listing failed. Please try again." : message, 14);
        body.addView(detail);
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(close, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "Could Not Fetch Models", body, actions, null);
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private static void showModelPicker(final android.content.Context context, final String[] modelIds,
            final ModelSelectionCallback selection) {
        final LinearLayout body = verticalLayout(context);
        final Dialog[] pickerDialog = new Dialog[1];
        for (String modelId : modelIds) {
            final TextView row = AiUiTheme.label(context, modelId, 15);
            row.setPadding(dp(context, 12), dp(context, 13), dp(context, 12), dp(context, 13));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(AiUiTheme.ACCENT_PRESSED),
                    AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 12),
                            AiUiTheme.INPUT_BORDER, dp(context, 1)), null));
            row.setClickable(true);
            row.setFocusable(true);
            final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(context, 8));
            body.addView(row, rowParams);
            row.setOnClickListener(view -> {
                selection.onSelected(modelId);
                pickerDialog[0].dismiss();
            });
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        actions.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        pickerDialog[0] = AiUiTheme.showDialog(context, "Select a Gemini Model", body, actions, null);
        final Dialog dialog = pickerDialog[0];
        cancelButton.setOnClickListener(view -> dialog.dismiss());
    }

    private void showAnswerDialog(final EditText editText, final String originalMarkdown,
            final String answer, final String matchedFile, final String instruction,
            final String provider, final String model,
            final MarkdownSectionParser.Section scopedSection) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final String[] currentAnswer = {answer};
        final LinearLayout content = verticalLayout(context);
        final TextView answerText = new TextView(context);
        answerText.setText(currentAnswer[0]);
        answerText.setTextIsSelectable(true);
        answerText.setTextSize(16);
        answerText.setTextColor(AiUiTheme.PRIMARY_TEXT);
        answerText.setLineSpacing(dp(context, 3), 1f);
        answerText.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        answerText.setBackground(AiUiTheme.inputBackground(context));
        content.addView(answerText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout firstRow = AiUiTheme.actionRow(context);
        final LinearLayout secondRow = AiUiTheme.actionRow(context);
        final LinearLayout refineRow = AiUiTheme.actionRow(context);
        final Button copyButton = AiUiTheme.button(context, "Copy", AiUiTheme.ButtonKind.SECONDARY);
        final Button appendButton = AiUiTheme.button(
                context, "Append to Note", AiUiTheme.ButtonKind.PRIMARY);
        if (currentFile == null) {
            appendButton.setEnabled(false);
            appendButton.setText("Append (open a note)");
        }
        final Button closeButton = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        firstRow.addView(copyButton, AiUiTheme.weightedButtonParams(context));
        firstRow.addView(appendButton, AiUiTheme.weightedButtonParams(context));
        final File matched = findMatchedFile(matchedFile);
        final Button openButton = matched == null ? null
                : AiUiTheme.button(context, "Open File", AiUiTheme.ButtonKind.SECONDARY);
        if (openButton != null) {
            secondRow.addView(openButton, AiUiTheme.weightedButtonParams(context));
        }
        final Button refineButton = AiUiTheme.button(
                context, "✏ Refine / Edit", AiUiTheme.ButtonKind.SECONDARY);
        secondRow.addView(closeButton, AiUiTheme.weightedButtonParams(context));
        refineRow.addView(refineButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(firstRow);
        actions.addView(refineRow);
        actions.addView(secondRow);
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Answer", content, actions, null);
        copyButton.setOnClickListener(view -> {
            final ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("AI Answer", currentAnswer[0]));
                Toast.makeText(context, "Answer copied.", Toast.LENGTH_SHORT).show();
            }
        });
        appendButton.setOnClickListener(view -> {
            try {
                final String appendContent = "\n\n## AI Answer\n\n" + currentAnswer[0];
                dialog.dismiss();
                appendToNotePreview(editText, originalMarkdown, appendContent,
                        instruction, provider, model, scopedSection);
            } catch (IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        });
        refineButton.setOnClickListener(view -> AiOutputRefineHelper.show(context, currentAnswer[0],
                createOutputRefineRequest(), refined -> {
                    currentAnswer[0] = refined;
                    answerText.setText(refined);
                }, input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }));
        closeButton.setOnClickListener(view -> dialog.dismiss());
        if (openButton != null) {
            openButton.setOnClickListener(view -> {
                if (openFileCallback != null) {
                    dialog.dismiss();
                    openFileCallback.open(matched);
                }
            });
        }
    }

    private File findMatchedFile(final String matchedFile) {
        final File searchRoot = currentFile == null
                ? getNotebookRoot() : currentFile.getParentFile();
        if (matchedFile == null || matchedFile.trim().isEmpty() || searchRoot == null) {
            return null;
        }
        try {
            final File parent = searchRoot.getCanonicalFile();
            final File candidate = new File(parent, matchedFile).getCanonicalFile();
            final String parentPath = parent.getPath() + File.separator;
            return candidate.getPath().startsWith(parentPath) && candidate.isFile() ? candidate : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private void showNewNotePreview(final String requestedFilename, final String noteContent) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final String[] currentContent = {noteContent};
        final LinearLayout content = verticalLayout(context);
        final EditText filenameInput = new EditText(context);
        filenameInput.setSingleLine(true);
        filenameInput.setHint("New note filename");
        styleInput(filenameInput, context);
        filenameInput.setText(GeminiMarkdownHelper.sanitizeNoteFilename(requestedFilename));
        final TextView preview = new TextView(context);
        preview.setText(currentContent[0]);
        preview.setTextIsSelectable(true);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextColor(AiUiTheme.PRIMARY_TEXT);
        preview.setTextSize(13);
        preview.setLineSpacing(dp(context, 3), 1f);
        preview.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        preview.setBackground(AiUiTheme.inputBackground(context));
        content.addView(filenameInput);
        addVerticalGap(context, content, 12);
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout editRow = AiUiTheme.actionRow(context);
        final Button refineButton = AiUiTheme.button(
                context, "✏ Refine / Edit", AiUiTheme.ButtonKind.SECONDARY);
        editRow.addView(refineButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(editRow);
        final LinearLayout actionRow = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button createButton = AiUiTheme.button(context, "Create & Open", AiUiTheme.ButtonKind.PRIMARY);
        actionRow.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actionRow.addView(createButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(actionRow);
        final Dialog dialog = AiUiTheme.showDialog(context, "Preview New Note", content, actions, null);
        refineButton.setOnClickListener(view -> AiOutputRefineHelper.show(context, currentContent[0],
                createOutputRefineRequest(), refined -> {
                    currentContent[0] = refined;
                    preview.setText(refined);
                }, input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }));
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        createButton.setOnClickListener(view -> {
            try {
                final File created = createNote(filenameInput.getText().toString(), currentContent[0]);
                dialog.dismiss();
                if (openFileCallback != null) {
                    openFileCallback.open(created);
                }
            } catch (IOException exception) {
                showError(exception.getMessage());
            }
        });
    }

    private File createNote(final String requestedFilename, final String content) throws IOException {
        final File parent = currentFile == null ? getNotebookRoot() : currentFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The current note's folder is not available.");
        }
        final String safeName = GeminiMarkdownHelper.sanitizeNoteFilename(requestedFilename);
        final int extensionIndex = safeName.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".md");
        final String base = safeName.substring(0, extensionIndex);
        for (int suffix = 0; ; suffix++) {
            final String name = base + (suffix == 0 ? "" : "-" + suffix) + ".md";
            final File file = new File(parent, name);
            if (!file.createNewFile()) {
                continue;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                return file;
            } catch (IOException exception) {
                if (!file.delete()) {
                    exception.addSuppressed(new IOException("Could not remove the incomplete note: "
                            + file.getAbsolutePath()));
                }
                throw exception;
            }
        }
    }

    private void showDiffPreview(final EditText editText, final String original, final String updated,
            final String instruction, final String provider, final String model) {
        showDiffPreview(editText, original, updated, instruction, provider, model,
                null, null, null);
    }

    private void showDiffPreview(final EditText editText, final String original, final String updated,
            final String instruction, final String provider, final String model,
            final Runnable onApplied) {
        showDiffPreview(editText, original, updated, instruction, provider, model,
                onApplied, null, null);
    }

    private void showDiffPreview(final EditText editText, final String original, final String updated,
            final String instruction, final String provider, final String model,
            final Runnable onApplied, final MarkdownSectionParser.Section scopedSection,
            final String updatedSection) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final String[] currentUpdated = {updated};
        final String[] currentSection = {updatedSection};
        final LinearLayout body = verticalLayout(context);
        final LinearLayout toggle = AiUiTheme.actionRow(context);
        final Button diffButton = AiUiTheme.button(context, "Diff View", AiUiTheme.ButtonKind.PRIMARY);
        final Button fullButton = AiUiTheme.button(context, "Full Preview", AiUiTheme.ButtonKind.SECONDARY);
        toggle.addView(diffButton, AiUiTheme.weightedButtonParams(context));
        toggle.addView(fullButton, AiUiTheme.weightedButtonParams(context));
        body.addView(toggle);
        final List<SimpleLineDiff.Line> compactDiff =
                SimpleLineDiff.buildCompactDiffLines(original, currentUpdated[0], 2);
        final int[] changeCounts = countDiffChanges(compactDiff);
        final LinearLayout summary = AiUiTheme.actionRow(context);
        summary.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        summary.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT, dp(context, 10),
                AiUiTheme.INPUT_BORDER, dp(context, 1)));
        final TextView addedSummary = AiUiTheme.label(context,
                "+" + changeCounts[0] + " added", 12);
        addedSummary.setTextColor(AiUiTheme.ADDED_TEXT);
        addedSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        final TextView removedSummary = AiUiTheme.label(context,
                "-" + changeCounts[1] + " removed", 12);
        removedSummary.setTextColor(AiUiTheme.REMOVED_TEXT);
        removedSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        final LinearLayout.LayoutParams summaryTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryTextParams.setMargins(0, dp(context, 4), dp(context, 14), 0);
        summary.addView(addedSummary, summaryTextParams);
        final LinearLayout.LayoutParams removedSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removedSummaryParams.setMargins(0, dp(context, 4), 0, 0);
        summary.addView(removedSummary, removedSummaryParams);
        final LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(0, dp(context, 8), 0, 0);
        body.addView(summary, summaryParams);
        final LinearLayout preview = verticalLayout(context);
        final LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.setMargins(0, dp(context, 12), 0, 0);
        body.addView(preview, previewParams);
        renderDiffPreview(context, preview, currentUpdated[0], false, compactDiff);

        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout editRow = AiUiTheme.actionRow(context);
        final Button refineButton = AiUiTheme.button(
                context, "✏ Refine / Edit", AiUiTheme.ButtonKind.SECONDARY);
        editRow.addView(refineButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(editRow);
        final LinearLayout actionRow = AiUiTheme.actionRow(context);
        final Button cancelButton = AiUiTheme.button(context, "Cancel", AiUiTheme.ButtonKind.SECONDARY);
        final Button applyButton = AiUiTheme.button(context, "Apply Changes", AiUiTheme.ButtonKind.PRIMARY);
        actionRow.addView(cancelButton, AiUiTheme.weightedButtonParams(context));
        actionRow.addView(applyButton, AiUiTheme.weightedButtonParams(context));
        actions.addView(actionRow);
        final Dialog dialog = AiUiTheme.showDialog(context, "Diff Preview", body, actions, null);
        refineButton.setOnClickListener(view -> AiOutputRefineHelper.show(context,
                scopedSection == null ? currentUpdated[0] : currentSection[0],
                createOutputRefineRequest(), refined -> {
                    if (scopedSection == null) {
                        currentUpdated[0] = refined;
                    } else {
                        try {
                            currentUpdated[0] = MarkdownSectionReplacement.replace(
                                    original, scopedSection, refined);
                            currentSection[0] = refined;
                        } catch (IllegalArgumentException exception) {
                            showError(exception.getMessage());
                            return;
                        }
                    }
                    final List<SimpleLineDiff.Line> refreshedDiff =
                            SimpleLineDiff.buildCompactDiffLines(original, currentUpdated[0], 2);
                    final int[] refreshedCounts = countDiffChanges(refreshedDiff);
                    addedSummary.setText("+" + refreshedCounts[0] + " added");
                    removedSummary.setText("-" + refreshedCounts[1] + " removed");
                    renderDiffPreview(context, preview, currentUpdated[0], false, refreshedDiff);
                }, input -> {
                    activeInstructionInput = input;
                    startVoiceInput();
                }));
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        applyButton.setOnClickListener(view -> {
            final String beforeApplying = editText.getText().toString();
            if (!original.equals(beforeApplying)) {
                showError("The note changed after this diff was prepared. Run the assistant again.");
                return;
            }
            try {
                diffHistoryStore.addEntry(
                        currentFile, instruction, provider, model, beforeApplying, currentUpdated[0]);
            } catch (IOException exception) {
                showError("Could not save this edit to history: " + exception.getMessage());
                return;
            }
            editText.getText().replace(0, editText.getText().length(), currentUpdated[0]);
            dialog.dismiss();
            onAfterApply.run();
            if (onApplied != null) {
                onApplied.run();
            }
        });
        diffButton.setOnClickListener(view -> {
            AiUiTheme.styleButton(diffButton, AiUiTheme.ButtonKind.PRIMARY);
            AiUiTheme.styleButton(fullButton, AiUiTheme.ButtonKind.SECONDARY);
            final List<SimpleLineDiff.Line> refreshedDiff =
                    SimpleLineDiff.buildCompactDiffLines(original, currentUpdated[0], 2);
            renderDiffPreview(context, preview, currentUpdated[0], false, refreshedDiff);
        });
        fullButton.setOnClickListener(view -> {
            AiUiTheme.styleButton(diffButton, AiUiTheme.ButtonKind.SECONDARY);
            AiUiTheme.styleButton(fullButton, AiUiTheme.ButtonKind.PRIMARY);
            final List<SimpleLineDiff.Line> refreshedDiff =
                    SimpleLineDiff.buildCompactDiffLines(original, currentUpdated[0], 2);
            renderDiffPreview(context, preview, currentUpdated[0], true, refreshedDiff);
        });
    }

    private void showError(final String message) {
        final android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        final LinearLayout body = verticalLayout(context);
        final TextView detail = AiUiTheme.label(context,
                message == null || message.isEmpty()
                        ? "The Gemini request failed. Please try again." : message, 14);
        body.addView(detail);
        final LinearLayout actions = AiUiTheme.actionRow(context);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.PRIMARY);
        actions.addView(close, AiUiTheme.weightedButtonParams(context));
        final Dialog dialog = AiUiTheme.showDialog(context, "AI Assistant Failed", body, actions, null);
        close.setOnClickListener(view -> dialog.dismiss());
    }

    private static void renderDiffPreview(final android.content.Context context, final LinearLayout preview,
            final String updated, final boolean fullPreview, final List<SimpleLineDiff.Line> compactDiff) {
        preview.removeAllViews();
        if (fullPreview) {
            final TextView text = AiUiTheme.label(context, updated, 14);
            text.setTypeface(Typeface.MONOSPACE);
            text.setLineSpacing(dp(context, 4), 1f);
            text.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
            text.setBackground(AiUiTheme.inputBackground(context));
            preview.addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (SimpleLineDiff.Line line : compactDiff) {
            switch (line.getType()) {
                case ADDED:
                    addDiffLine(context, preview, "+ " + line.getText(),
                            AiUiTheme.ADDED_BACKGROUND, AiUiTheme.ADDED_TEXT);
                    break;
                case REMOVED:
                    addDiffLine(context, preview, "- " + line.getText(),
                            AiUiTheme.REMOVED_BACKGROUND, AiUiTheme.REMOVED_TEXT);
                    break;
                case COLLAPSED:
                    final TextView separator = AiUiTheme.label(context, line.getText(), 12);
                    separator.setTextColor(AiUiTheme.SECONDARY_TEXT);
                    separator.setGravity(Gravity.CENTER);
                    separator.setPadding(dp(context, 8), dp(context, 5), dp(context, 8), dp(context, 5));
                    preview.addView(separator, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    break;
                default:
                    addDiffLine(context, preview, "  " + line.getText(),
                            AiUiTheme.UNCHANGED_BACKGROUND, AiUiTheme.UNCHANGED_TEXT);
                    break;
            }
        }
    }

    private static int[] countDiffChanges(final List<SimpleLineDiff.Line> lines) {
        int added = 0;
        int removed = 0;
        for (SimpleLineDiff.Line line : lines) {
            if (line.getType() == SimpleLineDiff.Type.ADDED) {
                added++;
            } else if (line.getType() == SimpleLineDiff.Type.REMOVED) {
                removed++;
            }
        }
        return new int[]{added, removed};
    }

    private static void renderFormattedDiff(final android.content.Context context,
            final LinearLayout preview, final String diffText) {
        preview.removeAllViews();
        if (diffText == null || diffText.isEmpty()) {
            preview.addView(AiUiTheme.label(context, "(No diff available)", 13));
            return;
        }
        final String[] lines = diffText.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            final String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            if (line.startsWith("+ ")) {
                addDiffLine(context, preview, line, AiUiTheme.ADDED_BACKGROUND, AiUiTheme.ADDED_TEXT);
            } else if (line.startsWith("- ")) {
                addDiffLine(context, preview, line, AiUiTheme.REMOVED_BACKGROUND, AiUiTheme.REMOVED_TEXT);
            } else {
                addDiffLine(context, preview, line, AiUiTheme.UNCHANGED_BACKGROUND,
                        AiUiTheme.UNCHANGED_TEXT);
            }
        }
    }

    private static void addDiffLine(final android.content.Context context, final LinearLayout preview,
            final String text, final int background, final int foreground) {
        final TextView lineView = new TextView(context);
        lineView.setText(text);
        lineView.setTextColor(foreground);
        lineView.setTextSize(12.5f);
        lineView.setTypeface(Typeface.MONOSPACE);
        lineView.setLineSpacing(dp(context, 4), 1f);
        lineView.setIncludeFontPadding(true);
        lineView.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
        lineView.setBackground(AiUiTheme.rounded(background, dp(context, 3), background, 0));
        final LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lineParams.setMargins(0, 0, 0, dp(context, 2));
        preview.addView(lineView, lineParams);
    }

    private static LinearLayout verticalLayout(final android.content.Context context) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static void styleInput(final EditText input, final android.content.Context context) {
        input.setTextColor(AiUiTheme.PRIMARY_TEXT);
        input.setHintTextColor(AiUiTheme.SECONDARY_TEXT);
        input.setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11));
        input.setBackground(AiUiTheme.inputBackground(context));
    }

    private static void addVerticalGap(final android.content.Context context, final LinearLayout parent,
            final int heightDp) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
    }

    private static int dp(final android.content.Context context, final int value) {
        return AiUiTheme.dp(context, value);
    }
}
