package net.gsantner.markor.ai_assistant;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiTodoAssistantDialog {
    public interface Host {
        File getTodoFile();

        File getNotebookRoot();

        File getCurrentFile();

        String readTodoText() throws IOException;

        String readTodoDiskText() throws IOException;

        void previewTodoChanges(String sourceText, String diskSnapshot, String updatedText,
                String instruction, String provider, String model, Runnable onApplied);

        void openFile(File file);

        void startVoiceInput(EditText input);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final int MAX_LINKED_NOTE_CHARS = 60000;

    private final Context context;
    private final AiSettingsStore settingsStore;
    private final Host host;
    private final List<ChatTurn> chatTurns = new ArrayList<>();
    private String selectedMode = "edit";
    private String linkedNotePath;
    private String linkedNoteContent;
    private Dialog dialog;
    private AlertDialog transientDialog;
    private LinearLayout tabContent;
    private TextView commandTab;
    private TextView remindersTab;
    private TextView editModeButton;
    private TextView chatModeButton;
    private Button runButton;
    private EditText promptInput;
    private TextView linkedNoteChip;
    private TextView linkCurrentNoteButton;
    private TextView planResult;
    private Button shiftOverdueButton;
    private TextView chatHistory;
    private boolean remindersTabActive;
    private int tabRenderGeneration;

    private AiTodoAssistantDialog(final Context context,
            final AiSettingsStore settingsStore, final Host host) {
        this.context = context;
        this.settingsStore = settingsStore;
        this.host = host;
    }

    public static Dialog show(final Context context, final AiSettingsStore settingsStore,
            final Host host) {
        if (context == null || settingsStore == null || host == null) {
            throw new IllegalArgumentException("AI Tasks requires a context, settings, and host.");
        }
        return new AiTodoAssistantDialog(context, settingsStore, host).show();
    }

    private Dialog show() {
        final LinearLayout body = vertical(context);
        final LinearLayout tabs = AiUiTheme.actionRow(context);
        commandTab = tabButton("AI Command & Plan", true);
        remindersTab = tabButton("Tasks & Reminders", false);
        tabs.addView(commandTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(remindersTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(tabs);
        tabContent = vertical(context);
        final LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.setMargins(0, AiUiTheme.dp(context, 12), 0, 0);
        body.addView(tabContent, contentParams);

        final LinearLayout headerActions = AiUiTheme.actionRow(context);
        final TextView resetButton = AiUiTheme.headerPill(context, "Reset");
        headerActions.addView(resetButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, AiUiTheme.dp(context, 36)));
        final LinearLayout footer = AiUiTheme.actionRow(context);
        final Button closeButton = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        footer.addView(closeButton, AiUiTheme.weightedButtonParams(context));

        dialog = AiUiTheme.showDialog(context, "AI Tasks", body, footer, headerActions);
        AiUiTheme.setOnDismissListener(dialog, ignored -> {
            if (transientDialog != null && transientDialog.isShowing()) {
                transientDialog.dismiss();
            }
            transientDialog = null;
        });
        commandTab.setOnClickListener(view -> renderCommandTab());
        remindersTab.setOnClickListener(view -> renderRemindersTab());
        resetButton.setOnClickListener(view -> reset());
        closeButton.setOnClickListener(view -> dialog.dismiss());
        renderCommandTab();
        return dialog;
    }

    private TextView tabButton(final String label, final boolean selected) {
        final TextView button = AiUiTheme.label(context, label, 13);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setMaxLines(1);
        AiUiTheme.styleChip(button, selected);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(AiUiTheme.dp(context, 3), 0,
                AiUiTheme.dp(context, 3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void renderCommandTab() {
        remindersTabActive = false;
        tabRenderGeneration++;
        AiUiTheme.styleChip(commandTab, true);
        AiUiTheme.styleChip(remindersTab, false);
        tabContent.removeAllViews();

        final Button planButton = AiUiTheme.button(context, "Today's Study Plan",
                AiUiTheme.ButtonKind.MIC);
        tabContent.addView(planButton, fullWidthParams());
        planResult = AiUiTheme.label(context, "", 14);
        planResult.setTextColor(AiUiTheme.PRIMARY_TEXT);
        planResult.setPadding(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 10),
                AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 10));
        planResult.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT,
                AiUiTheme.dp(context, 12), AiUiTheme.INPUT_BORDER, AiUiTheme.dp(context, 1)));
        planResult.setVisibility(View.GONE);
        tabContent.addView(planResult, fullWidthParams());
        shiftOverdueButton = AiUiTheme.button(context, "Shift Overdue to Today",
                AiUiTheme.ButtonKind.SECONDARY);
        shiftOverdueButton.setVisibility(View.GONE);
        tabContent.addView(shiftOverdueButton, fullWidthParams());
        planButton.setOnClickListener(view -> generateStudyPlan(planButton));
        shiftOverdueButton.setOnClickListener(view -> shiftOverdueTasks());

        final LinearLayout modeRow = AiUiTheme.actionRow(context);
        editModeButton = AiUiTheme.headerPill(context, "Edit Tasks");
        chatModeButton = AiUiTheme.headerPill(context, "Task Chat");
        modeRow.addView(editModeButton, horizontalButtonParams());
        modeRow.addView(chatModeButton, horizontalButtonParams());
        final LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeParams.setMargins(0, AiUiTheme.dp(context, 14), 0, AiUiTheme.dp(context, 8));
        tabContent.addView(modeRow, modeParams);
        updateModeButtons();
        editModeButton.setOnClickListener(view -> selectMode("edit"));
        chatModeButton.setOnClickListener(view -> selectMode("chat"));

        chatHistory = AiUiTheme.label(context, "", 13);
        chatHistory.setTextColor(AiUiTheme.SECONDARY_TEXT);
        chatHistory.setVisibility("chat".equals(selectedMode) && !chatTurns.isEmpty()
                ? View.VISIBLE : View.GONE);
        updateChatHistory();
        tabContent.addView(chatHistory, fullWidthParams());

        promptInput = new EditText(context);
        promptInput.setHint("Ask about tasks or describe the changes to make");
        promptInput.setTextColor(AiUiTheme.PRIMARY_TEXT);
        promptInput.setHintTextColor(AiUiTheme.SECONDARY_TEXT);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        promptInput.setMinLines(2);
        promptInput.setMaxLines(5);
        promptInput.setPadding(AiUiTheme.dp(context, 13), AiUiTheme.dp(context, 11),
                AiUiTheme.dp(context, 13), AiUiTheme.dp(context, 11));
        promptInput.setBackground(AiUiTheme.inputBackground(context));
        tabContent.addView(promptInput, fullWidthParams());

        final LinearLayout linkRow = AiUiTheme.actionRow(context);
        final Button linkNoteButton = AiUiTheme.button(context, "📎 Link Note",
                AiUiTheme.ButtonKind.MIC);
        final Button micButton = AiUiTheme.button(context, "Mic", AiUiTheme.ButtonKind.MIC);
        linkRow.addView(linkNoteButton, horizontalButtonParams());
        linkRow.addView(micButton, horizontalButtonParams());
        tabContent.addView(linkRow, fullWidthParams());
        linkedNoteChip = AiUiTheme.label(context, "", 12);
        linkedNoteChip.setTextColor(AiUiTheme.ACCENT);
        linkedNoteChip.setPadding(AiUiTheme.dp(context, 9), AiUiTheme.dp(context, 6),
                AiUiTheme.dp(context, 9), AiUiTheme.dp(context, 6));
        linkedNoteChip.setBackground(AiUiTheme.rounded(AiUiTheme.SURFACE,
                AiUiTheme.dp(context, 16), AiUiTheme.ACCENT, AiUiTheme.dp(context, 1)));
        linkedNoteChip.setVisibility(View.GONE);
        tabContent.addView(linkedNoteChip, fullWidthParams());
        linkCurrentNoteButton = AiUiTheme.headerPill(context, "📎 Link open note");
        linkCurrentNoteButton.setVisibility(View.GONE);
        tabContent.addView(linkCurrentNoteButton, fullWidthParams());
        linkNoteButton.setOnClickListener(view -> showNotePicker(linkNoteButton));
        micButton.setOnClickListener(view -> host.startVoiceInput(promptInput));
        linkedNoteChip.setOnClickListener(view -> clearLinkedNote());
        linkCurrentNoteButton.setOnClickListener(view -> {
            final File current = host.getCurrentFile();
            if (current != null) {
                linkNote(current);
            }
        });
        updateLinkedNoteControls();

        runButton = AiUiTheme.button(context,
                "chat".equals(selectedMode) ? "Send" : "Generate Task Patch",
                AiUiTheme.ButtonKind.PRIMARY);
        final LinearLayout.LayoutParams runParams = fullWidthParams();
        runParams.setMargins(0, AiUiTheme.dp(context, 8), 0, 0);
        tabContent.addView(runButton, runParams);
        runButton.setOnClickListener(view -> {
            if ("chat".equals(selectedMode)) {
                sendChat(runButton);
            } else {
                editTasks(runButton);
            }
        });
    }

    private void selectMode(final String mode) {
        selectedMode = mode;
        updateModeButtons();
        if (chatHistory != null) {
            updateChatHistory();
        }
        if (runButton != null) {
            runButton.setText("chat".equals(mode) ? "Send" : "Generate Task Patch");
        }
        if (promptInput != null) {
            promptInput.setHint("chat".equals(mode)
                    ? "Ask a question about your tasks" : "Describe the task changes to make");
        }
    }

    private void updateModeButtons() {
        if (editModeButton != null) {
            styleModeButton(editModeButton, "edit".equals(selectedMode));
        }
        if (chatModeButton != null) {
            styleModeButton(chatModeButton, "chat".equals(selectedMode));
        }
    }

    private void styleModeButton(final TextView button, final boolean selected) {
        button.setTextColor(selected ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY_TEXT);
        button.setBackground(AiUiTheme.rounded(selected ? AiUiTheme.SURFACE : AiUiTheme.SECONDARY,
                AiUiTheme.dp(context, 18), selected ? AiUiTheme.ACCENT : AiUiTheme.SECONDARY_BORDER,
                AiUiTheme.dp(context, 1)));
        button.setPadding(AiUiTheme.dp(context, 10), 0, AiUiTheme.dp(context, 10), 0);
    }

    private void updateLinkedNoteControls() {
        if (linkedNoteChip != null) {
            linkedNoteChip.setVisibility(linkedNotePath == null ? View.GONE : View.VISIBLE);
            linkedNoteChip.setText(linkedNotePath == null
                    ? "" : "📎 ref:" + linkedNotePath + "  ✕");
        }
        if (linkCurrentNoteButton != null) {
            final File current = host.getCurrentFile();
            boolean available = false;
            if (current != null) {
                try {
                    final String path = AiTodoAssistantHelper.relativeNotePath(
                            host.getNotebookRoot(), current);
                    available = linkedNotePath == null || !linkedNotePath.equals(path);
                    linkCurrentNoteButton.setText("📎 Link open note: " + path);
                } catch (IOException | IllegalArgumentException ignored) {
                    available = false;
                }
            }
            linkCurrentNoteButton.setVisibility(available ? View.VISIBLE : View.GONE);
        }
    }

    private void linkNote(final File note) {
        try {
            linkedNotePath = AiTodoAssistantHelper.relativeNotePath(host.getNotebookRoot(), note);
            linkedNoteContent = "";
            updateLinkedNoteControls();
            final String linkedPath = linkedNotePath;
            EXECUTOR.execute(() -> {
                try {
                    final String content = readTextFile(note);
                    MAIN_HANDLER.post(() -> {
                        if (!isDismissed() && linkedPath.equals(linkedNotePath)) {
                            linkedNoteContent = content;
                        }
                    });
                } catch (IOException exception) {
                    MAIN_HANDLER.post(() -> {
                        if (!isDismissed() && linkedPath.equals(linkedNotePath)) {
                            clearLinkedNote();
                            showToast(exception.getMessage());
                        }
                    });
                }
            });
        } catch (IOException | IllegalArgumentException exception) {
            showToast(exception.getMessage());
        }
    }

    private void clearLinkedNote() {
        linkedNotePath = null;
        linkedNoteContent = null;
        updateLinkedNoteControls();
    }

    private void showNotePicker(final Button button) {
        button.setEnabled(false);
        EXECUTOR.execute(() -> {
            try {
                final List<File> notes = AiTodoAssistantHelper.listMarkdownNotes(
                        host.getNotebookRoot(), new AiPinnedStore(context).getPinnedFiles());
                MAIN_HANDLER.post(() -> {
                    button.setEnabled(true);
                    if (isDismissed()) {
                        return;
                    }
                    if (notes.isEmpty()) {
                        showToast("No Markdown notes found in the Notebook or Pinned files.");
                        return;
                    }
                    final String[] labels = new String[notes.size()];
                    for (int index = 0; index < notes.size(); index++) {
                        try {
                            labels[index] = AiTodoAssistantHelper.relativeNotePath(
                                    host.getNotebookRoot(), notes.get(index));
                        } catch (IOException exception) {
                            labels[index] = notes.get(index).getName();
                        }
                    }
                    transientDialog = new AlertDialog.Builder(context)
                            .setTitle("Link a Markdown note")
                            .setItems(labels, (picker, which) -> linkNote(notes.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (IOException | IllegalArgumentException exception) {
                MAIN_HANDLER.post(() -> {
                    button.setEnabled(true);
                    if (!isDismissed()) {
                        showToast(exception.getMessage());
                    }
                });
            }
        });
    }

    private void generateStudyPlan(final Button button) {
        button.setEnabled(false);
        planResult.setVisibility(View.VISIBLE);
        planResult.setText("Preparing today's tasks…");
        shiftOverdueButton.setVisibility(View.GONE);
        final String today = currentDate();
        final SourceSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (IOException exception) {
            button.setEnabled(true);
            planResult.setText(exception.getMessage());
            return;
        }
        final String source = snapshot.sourceText;
        final String overdue = rawTasks(AiTodoAssistantHelper.overdueTasks(source, today));
        final String dueToday = rawTasks(AiTodoAssistantHelper.todayTasks(source, today));
        final boolean hasOverdue = !AiTodoAssistantHelper.overdueTasks(source, today).isEmpty();
        shiftOverdueButton.setTag(snapshot);
        shiftOverdueButton.setVisibility(hasOverdue ? View.VISIBLE : View.GONE);
        final List<AiTodoAssistantHelper.Task> highPriority = new ArrayList<>();
        for (AiTodoAssistantHelper.Task task : AiTodoAssistantHelper.parseTasks(source)) {
            if (!task.isCompleted() && task.getPriority() != null
                    && task.getPriority() == 'A') {
                highPriority.add(task);
            }
        }
        final String prompt = AiTodoAssistantHelper.buildStudyPlanPrompt(
                AiTodoAssistantHelper.todayForPrompt(), overdue, dueToday, rawTasks(highPriority));
        generate(source, prompt, (answer, providerResult, error) -> {
            button.setEnabled(true);
            if (error != null) {
                planResult.setText(error);
                return;
            }
            showFailoverNotice(providerResult);
            planResult.setText(answer);
        });
    }

    private void shiftOverdueTasks() {
        final Object tag = shiftOverdueButton.getTag();
        if (!(tag instanceof SourceSnapshot)) {
            return;
        }
        final SourceSnapshot snapshot = (SourceSnapshot) tag;
        final String today = currentDate();
        try {
            final String updated = AiTodoAssistantHelper.shiftOverdueToToday(
                    snapshot.sourceText, today);
            if (updated.equals(snapshot.sourceText)) {
                showToast("There are no overdue tasks to shift.");
                return;
            }
            host.previewTodoChanges(snapshot.sourceText, snapshot.diskSnapshot, updated,
                    "Shift overdue tasks to today", "local", "",
                    () -> {
                        shiftOverdueButton.setVisibility(View.GONE);
                        if (remindersTabActive) {
                            renderRemindersTab();
                        }
                    });
        } catch (IllegalArgumentException exception) {
            showToast(exception.getMessage());
        }
    }

    private void editTasks(final Button button) {
        final String instruction = promptInput.getText().toString().trim();
        if (instruction.isEmpty()) {
            showToast("Describe the task changes to make.");
            return;
        }
        button.setEnabled(false);
        final SourceSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (IOException exception) {
            button.setEnabled(true);
            showToast(exception.getMessage());
            return;
        }
        final String source = snapshot.sourceText;
        final String linkedPath = linkedNotePath;
        final String noteContent = linkedNoteContent;
        final String prompt = AiTodoAssistantHelper.buildEditPrompt(
                AiTodoAssistantHelper.todayForPrompt(), instruction, linkedPath, noteContent);
        generate(source, prompt, (patch, providerResult, error) -> {
            if (error != null) {
                button.setEnabled(true);
                showToast(error);
                return;
            }
            try {
                showFailoverNotice(providerResult);
                final String updated = AiTodoAssistantHelper.applyPatch(
                        source, parsePatchJson(stripJsonFence(patch)));
                host.previewTodoChanges(source, snapshot.diskSnapshot, updated,
                        instruction, providerResult.getUsedProviderName(),
                        providerResult.getUsedModelName(), () -> {
                            button.setEnabled(true);
                            promptInput.setText("");
                            if (remindersTabActive) {
                                renderRemindersTab();
                            }
                        });
            } catch (IOException | IllegalArgumentException exception) {
                button.setEnabled(true);
                showToast(exception.getMessage());
            }
        });
    }

    private void sendChat(final Button button) {
        final String question = promptInput.getText().toString().trim();
        if (question.isEmpty()) {
            showToast("Enter a question about your tasks.");
            return;
        }
        button.setEnabled(false);
        final SourceSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (IOException exception) {
            button.setEnabled(true);
            showToast(exception.getMessage());
            return;
        }
        final String conversation = conversationText();
        final String linkedPath = linkedNotePath;
        final String noteContent = linkedNoteContent;
        final String prompt = AiTodoAssistantHelper.buildChatPrompt(
                AiTodoAssistantHelper.todayForPrompt(), snapshot.sourceText,
                conversation, question, linkedPath, noteContent);
        generate(snapshot.sourceText, prompt, (answer, providerResult, error) -> {
            button.setEnabled(true);
            if (error != null) {
                showToast(error);
                return;
            }
            showFailoverNotice(providerResult);
            chatTurns.add(new ChatTurn(true, question));
            chatTurns.add(new ChatTurn(false, answer));
            promptInput.setText("");
            updateChatHistory();
        });
    }

    private void generate(final String source, final String prompt,
            final GeneratedCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                final AiProviderRouter.Result result = new AiProviderRouter(settingsStore)
                        .generateIntent(source, prompt, "study", "");
                final String answer = result.getIntentResult().getAnswer();
                if (answer == null || answer.trim().isEmpty()) {
                    throw new IOException("The AI provider returned an empty response.");
                }
                MAIN_HANDLER.post(() -> {
                    if (!isDismissed()) {
                        callback.onResult(answer, result, null);
                    }
                });
            } catch (IOException exception) {
                MAIN_HANDLER.post(() -> {
                    if (!isDismissed()) {
                        callback.onResult(null, null, exception.getMessage());
                    }
                });
            }
        });
    }

    private void renderRemindersTab() {
        remindersTabActive = true;
        final int generation = ++tabRenderGeneration;
        AiUiTheme.styleChip(commandTab, false);
        AiUiTheme.styleChip(remindersTab, true);
        tabContent.removeAllViews();
        final TextView loading = AiUiTheme.label(context, "Loading todo.txt…", 13);
        loading.setTextColor(AiUiTheme.SECONDARY_TEXT);
        tabContent.addView(loading, fullWidthParams());
        try {
            final String text = host.readTodoText();
            final String today = currentDate();
            final List<AiTodoAssistantHelper.Task> overdue =
                    AiTodoAssistantHelper.overdueTasks(text, today);
            final List<AiTodoAssistantHelper.Task> dueToday =
                    AiTodoAssistantHelper.todayTasks(text, today);
            final List<AiTodoAssistantHelper.Task> upcoming =
                    AiTodoAssistantHelper.upcomingTasks(text, today);
            if (!remindersTabActive || generation != tabRenderGeneration) {
                return;
            }
            tabContent.removeAllViews();
            addTaskGroup(tabContent, "Overdue", overdue);
            addTaskGroup(tabContent, "Today", dueToday);
            addTaskGroup(tabContent, "Upcoming / Other", upcoming);
        } catch (IOException | IllegalArgumentException exception) {
            tabContent.removeAllViews();
            final TextView error = AiUiTheme.label(context, exception.getMessage(), 13);
            error.setTextColor(AiUiTheme.REMOVED_TEXT);
            tabContent.addView(error, fullWidthParams());
        }
    }

    private void addTaskGroup(final LinearLayout parent, final String label,
            final List<AiTodoAssistantHelper.Task> tasks) {
        final TextView heading = AiUiTheme.label(context, label + " (" + tasks.size() + ")", 15);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.setTextColor(AiUiTheme.ACCENT);
        final LinearLayout.LayoutParams headingParams = fullWidthParams();
        headingParams.setMargins(0, AiUiTheme.dp(context, 10), 0, AiUiTheme.dp(context, 6));
        parent.addView(heading, headingParams);
        if (tasks.isEmpty()) {
            final TextView empty = AiUiTheme.label(context, "No tasks", 12);
            empty.setTextColor(AiUiTheme.SECONDARY_TEXT);
            parent.addView(empty, fullWidthParams());
            return;
        }
        for (AiTodoAssistantHelper.Task task : tasks) {
            parent.addView(taskCard(task), fullWidthParams());
        }
    }

    private View taskCard(final AiTodoAssistantHelper.Task task) {
        final LinearLayout card = vertical(context);
        card.setPadding(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 10),
                AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 10));
        card.setBackground(AiUiTheme.rounded(AiUiTheme.INPUT,
                AiUiTheme.dp(context, 12), AiUiTheme.INPUT_BORDER, AiUiTheme.dp(context, 1)));
        final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, AiUiTheme.dp(context, 8));
        card.setLayoutParams(cardParams);
        final TextView title = AiUiTheme.label(context, task.getTitle(), 14);
        title.setTextColor(AiUiTheme.PRIMARY_TEXT);
        card.addView(title);
        final LinearLayout badges = AiUiTheme.actionRow(context);
        if (task.getPriority() != null) {
            badges.addView(taskBadge("Priority (" + task.getPriority() + ")"));
        }
        if (task.getDueDate() != null) {
            badges.addView(taskBadge("due:" + task.getDueDate()));
        }
        if (badges.getChildCount() > 0) {
            card.addView(badges);
        }
        final LinearLayout actions = AiUiTheme.actionRow(context);
        if (task.getRefPath() != null) {
            final TextView openNote = AiUiTheme.headerPill(context, "📄 Open Note");
            openNote.setTextSize(11);
            actions.addView(openNote, compactActionParams());
            openNote.setOnClickListener(view -> openLinkedNote(task.getRefPath()));
        }
        final TextView remind = AiUiTheme.headerPill(context, "⏰ Remind");
        remind.setTextSize(11);
        actions.addView(remind, compactActionParams());
        remind.setOnClickListener(view -> showReminderOptions(task));
        card.addView(actions);
        return card;
    }

    private TextView taskBadge(final String text) {
        final TextView badge = AiUiTheme.headerPill(context, text);
        badge.setTextSize(10);
        badge.setClickable(false);
        final LinearLayout.LayoutParams params = compactActionParams();
        badge.setLayoutParams(params);
        return badge;
    }

    private LinearLayout.LayoutParams compactActionParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, AiUiTheme.dp(context, 30));
        params.setMargins(0, AiUiTheme.dp(context, 5), AiUiTheme.dp(context, 5), 0);
        return params;
    }

    private void openLinkedNote(final String refPath) {
        try {
            final File note = AiTodoAssistantHelper.resolveNoteReference(
                    host.getNotebookRoot(), refPath);
            host.openFile(note);
        } catch (IOException | IllegalArgumentException exception) {
            showToast(exception.getMessage());
        }
    }

    private void showReminderOptions(final AiTodoAssistantHelper.Task task) {
        transientDialog = new AlertDialog.Builder(context)
                .setTitle("Set reminder")
                .setItems(new String[]{"Calendar Event", "Clock Alarm"},
                        (reminderDialog, which) -> {
                            if (which == 0) {
                                launchCalendarEvent(task);
                            } else {
                                launchClockAlarm(task);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchCalendarEvent(final AiTodoAssistantHelper.Task task) {
        try {
            final Calendar start = dueDateCalendar(task.getDueDate());
            start.set(Calendar.HOUR_OF_DAY, 9);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            final Intent intent = new Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, task.getTitle())
                    .putExtra(CalendarContract.Events.DESCRIPTION, reminderDescription(task))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.getTimeInMillis())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                            start.getTimeInMillis() + 60L * 60L * 1000L);
            startReminderIntent(intent);
        } catch (IllegalArgumentException exception) {
            showToast(exception.getMessage());
        }
    }

    private void launchClockAlarm(final AiTodoAssistantHelper.Task task) {
        final Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, 9)
                .putExtra(AlarmClock.EXTRA_MINUTES, 0)
                .putExtra(AlarmClock.EXTRA_MESSAGE, reminderDescription(task))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        startReminderIntent(intent);
    }

    private void startReminderIntent(final Intent intent) {
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            showToast("No Calendar or Clock app can handle this reminder.");
        }
    }

    private Calendar dueDateCalendar(final String dueDate) {
        final Calendar calendar = Calendar.getInstance();
        if (dueDate == null) {
            return calendar;
        }
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        try {
            final java.util.Date date = format.parse(dueDate);
            if (date == null) {
                throw new IllegalArgumentException("The task due date is invalid.");
            }
            calendar.setTime(date);
        } catch (ParseException exception) {
            throw new IllegalArgumentException("The task due date is invalid.", exception);
        }
        return calendar;
    }

    private String reminderDescription(final AiTodoAssistantHelper.Task task) {
        return "Markor todo.txt task"
                + (task.getDueDate() == null ? "" : " — due:" + task.getDueDate())
                + (task.getRefPath() == null ? "" : " — ref:" + task.getRefPath());
    }

    private void showFailoverNotice(final AiProviderRouter.Result result) {
        if (result != null && !result.getFailoverNotice().isEmpty()) {
            showToast(result.getFailoverNotice());
        }
    }

    private void updateChatHistory() {
        if (chatHistory == null) {
            return;
        }
        final StringBuilder history = new StringBuilder();
        for (ChatTurn turn : chatTurns) {
            history.append(turn.user ? "You: " : "AI: ").append(turn.text).append('\n');
        }
        chatHistory.setText(history.toString().trim());
        chatHistory.setVisibility(chatTurns.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String conversationText() {
        final StringBuilder conversation = new StringBuilder();
        for (ChatTurn turn : chatTurns) {
            conversation.append(turn.user ? "User: " : "Assistant: ")
                    .append(turn.text).append('\n');
        }
        return conversation.toString();
    }

    private void reset() {
        chatTurns.clear();
        selectedMode = "edit";
        linkedNotePath = null;
        linkedNoteContent = null;
        if (promptInput != null) {
            promptInput.setText("");
        }
        if (planResult != null) {
            planResult.setText("");
            planResult.setVisibility(View.GONE);
        }
        if (shiftOverdueButton != null) {
            shiftOverdueButton.setVisibility(View.GONE);
            shiftOverdueButton.setTag(null);
        }
        renderCommandTab();
    }

    private String currentDate() {
        return AiTodoAssistantHelper.formatDate(Calendar.getInstance());
    }

    private SourceSnapshot captureSnapshot() throws IOException {
        return new SourceSnapshot(host.readTodoText(), host.readTodoDiskText());
    }

    private static String rawTasks(final List<AiTodoAssistantHelper.Task> tasks) {
        if (tasks.isEmpty()) {
            return "(none)";
        }
        final StringBuilder result = new StringBuilder();
        for (AiTodoAssistantHelper.Task task : tasks) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(task.getRawLine());
        }
        return result.toString();
    }

    private static String stripJsonFence(final String response) {
        if (response == null) {
            return "";
        }
        String json = response.trim();
        if (json.startsWith("```")) {
            final int firstNewline = json.indexOf('\n');
            final int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return json;
    }

    private static List<AiTodoAssistantHelper.PatchOperation> parsePatchJson(
            final String patchJson) throws IOException {
        final List<AiTodoAssistantHelper.PatchOperation> operations = new ArrayList<>();
        try {
            final JSONArray jsonOperations = new JSONObject(patchJson).getJSONArray("operations");
            for (int index = 0; index < jsonOperations.length(); index++) {
                final JSONObject operation = jsonOperations.getJSONObject(index);
                final String kind = operation.getString("op");
                if ("add".equals(kind)) {
                    operations.add(AiTodoAssistantHelper.PatchOperation.add(
                            operation.getInt("after"), operation.getString("task")));
                } else if ("replace".equals(kind)) {
                    operations.add(AiTodoAssistantHelper.PatchOperation.replace(
                            operation.getInt("line"), operation.getString("task")));
                } else if ("delete".equals(kind)) {
                    operations.add(AiTodoAssistantHelper.PatchOperation.delete(
                            operation.getInt("line")));
                } else {
                    throw new IOException("Unsupported todo.txt patch operation: " + kind);
                }
            }
        } catch (JSONException exception) {
            throw new IOException("The AI returned an invalid todo.txt JSON patch.", exception);
        }
        return operations;
    }

    private String readTextFile(final File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            final String content = new String(output.toByteArray(), Charset.forName("UTF-8"));
            return content.length() <= MAX_LINKED_NOTE_CHARS ? content
                    : content.substring(0, MAX_LINKED_NOTE_CHARS)
                            + "\n\n[Linked note truncated for AI context.]";
        }
    }

    private void showToast(final String message) {
        Toast.makeText(context, message == null || message.trim().isEmpty()
                ? "The AI Tasks action could not be completed." : message,
                Toast.LENGTH_LONG).show();
    }

    private boolean isDismissed() {
        return dialog instanceof AiUiTheme.MinimizableDialog
                && ((AiUiTheme.MinimizableDialog) dialog).isDismissed();
    }

    private LinearLayout vertical(final Context parentContext) {
        final LinearLayout layout = new LinearLayout(parentContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams horizontalButtonParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(AiUiTheme.dp(context, 3), 0, AiUiTheme.dp(context, 3), 0);
        return params;
    }

    private interface GeneratedCallback {
        void onResult(String answer, AiProviderRouter.Result providerResult, String error);
    }

    private static final class SourceSnapshot {
        private final String sourceText;
        private final String diskSnapshot;

        private SourceSnapshot(final String sourceText, final String diskSnapshot) {
            this.sourceText = sourceText;
            this.diskSnapshot = diskSnapshot;
        }
    }

    private static final class ChatTurn {
        private final boolean user;
        private final String text;

        private ChatTurn(final boolean user, final String text) {
            this.user = user;
            this.text = text;
        }
    }
}
