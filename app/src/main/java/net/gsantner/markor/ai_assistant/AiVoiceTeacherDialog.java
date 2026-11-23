package net.gsantner.markor.ai_assistant;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AiVoiceTeacherDialog {
    public interface DoubtCallback {
        void onResult(String answer, String error);
    }

    public interface DoubtRequest {
        void send(String doubt, String section, String lesson,
                AiVoiceTeacherHelper.TeacherStyle style, DoubtCallback callback);
    }

    public interface StyleChangeResult {
        void onResult(String lesson, String error);
    }

    public interface StyleChangeRequest {
        void reExplain(AiVoiceTeacherHelper.TeacherStyle style, StyleChangeResult callback);
    }

    public interface SaveCallback {
        void save(String content, String heading);
    }

    public interface MicCallback {
        void start(EditText input);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final int ACTIVE_BACKGROUND = Color.rgb(50, 18, 24);
    private static final int ACTIVE_TEXT = Color.WHITE;
    private static final int INACTIVE_TEXT = Color.rgb(184, 184, 194);

    private AiVoiceTeacherDialog() {
    }

    public static Dialog show(final Context context, final String section,
            final String heading, final String lesson,
            final AiVoiceTeacherHelper.TeacherStyle style, final StyleChangeRequest styleChangeRequest,
            final DoubtRequest doubtRequest, final SaveCallback saveCallback,
            final MicCallback micCallback) {
        final Player player = new Player(context, section, heading, lesson, style,
                styleChangeRequest, doubtRequest, saveCallback, micCallback);
        player.show();
        return player.dialog;
    }

    private static final class Player {
        private final Context context;
        private final String section;
        private final String heading;
        private String lesson;
        private AiVoiceTeacherHelper.TeacherStyle teacherStyle;
        private final StyleChangeRequest styleChangeRequest;
        private final DoubtRequest doubtRequest;
        private final SaveCallback saveCallback;
        private final MicCallback micCallback;
        private final List<Sentence> sentences = new ArrayList<>();
        private final StringBuilder displayedScript = new StringBuilder();
        private final Dialog dialog;
        private final ScrollView scriptScroll;
        private final TextView scriptView;
        private final Button playButton;
        private final Button speedButton;
        private final Button resumeButton;
        private final EditText doubtInput;
        private final Button sendDoubtButton;
        private final LinearLayout doubtBar;
        private final TextView hiChip;
        private final TextView enChip;
        private final TextView styleStatus;
        private TextToSpeech tts;
        private boolean ttsReady;
        private boolean playing;
        private boolean hindiVoiceAvailable = true;
        private int currentSentence = -1;
        private int rangeEnd = -1;
        private int mainSentenceCount;
        private int mainResumeSentence;
        private int speedIndex = 1;
        private long utteranceSequence;
        private int styleRequestSequence;
        private String activeUtteranceId;
        private float touchDownX;
        private float touchDownY;

        private Player(final Context context, final String section, final String heading,
                final String lesson, final AiVoiceTeacherHelper.TeacherStyle style,
                final StyleChangeRequest styleChangeRequest, final DoubtRequest doubtRequest,
                final SaveCallback saveCallback, final MicCallback micCallback) {
            this.context = context;
            this.section = section == null ? "" : section;
            this.heading = heading == null ? "" : heading;
            this.lesson = AiVoiceTeacherHelper.cleanForTts(lesson);
            this.teacherStyle = style == null
                    ? AiVoiceTeacherHelper.DEFAULT_TEACHER_STYLE : style;
            this.styleChangeRequest = styleChangeRequest;
            this.doubtRequest = doubtRequest;
            this.saveCallback = saveCallback;
            this.micCallback = micCallback;

            final LinearLayout shell = new LinearLayout(context);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setBackground(AiUiTheme.cardBackground(context));
            final LinearLayout header = AiUiTheme.actionRow(context);
            header.setPadding(AiUiTheme.dp(context, 16), AiUiTheme.dp(context, 12),
                    AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 12));
            final TextView title = AiUiTheme.label(context, "AI Voice Teacher", 18);
            title.setTextColor(Color.WHITE);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            hiChip = languageChip(context, "hi-IN");
            enChip = languageChip(context, "en-IN");
            header.addView(hiChip);
            header.addView(enChip);
            shell.addView(header);
            final HorizontalScrollView styleSelector = AiUiTheme.teacherStyleSelector(
                    context, teacherStyle, this::requestStyleChange);
            final LinearLayout.LayoutParams styleSelectorParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            styleSelectorParams.setMargins(AiUiTheme.dp(context, 12), 0,
                    AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 8));
            shell.addView(styleSelector, styleSelectorParams);
            styleStatus = AiUiTheme.label(context, "", 12);
            styleStatus.setTextColor(AiUiTheme.SECONDARY_TEXT);
            styleStatus.setVisibility(View.GONE);
            final LinearLayout.LayoutParams styleStatusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            styleStatusParams.setMargins(AiUiTheme.dp(context, 16), 0,
                    AiUiTheme.dp(context, 16), AiUiTheme.dp(context, 8));
            shell.addView(styleStatus, styleStatusParams);
            shell.addView(AiUiTheme.divider(context), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AiUiTheme.dp(context, 1)));

            scriptScroll = new ScrollView(context);
            scriptScroll.setFillViewport(false);
            scriptScroll.setClipToPadding(false);
            scriptScroll.setPadding(AiUiTheme.dp(context, 16), AiUiTheme.dp(context, 14),
                    AiUiTheme.dp(context, 16), AiUiTheme.dp(context, 12));
            scriptView = AiUiTheme.label(context, "", 16);
            scriptView.setTextColor(INACTIVE_TEXT);
            scriptView.setLineSpacing(AiUiTheme.dp(context, 8), 1f);
            scriptView.setTextIsSelectable(false);
            scriptScroll.addView(scriptView, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shell.addView(scriptScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            scriptView.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE
                        && (Math.abs(event.getX() - touchDownX) > ViewConfiguration
                                .get(context).getScaledTouchSlop()
                        || Math.abs(event.getY() - touchDownY) > ViewConfiguration
                                .get(context).getScaledTouchSlop())) {
                    return false;
                }
                if (event.getAction() != MotionEvent.ACTION_UP || scriptView.getLayout() == null) {
                    return false;
                }
                final android.text.Layout layout = scriptView.getLayout();
                final int line = layout.getLineForVertical((int) (event.getY()
                        - scriptView.getTotalPaddingTop() + scriptView.getScrollY()));
                final int offset = layout.getOffsetForHorizontal(line, event.getX()
                        - scriptView.getTotalPaddingLeft() + scriptView.getScrollX());
                for (int index = 0; index < sentences.size(); index++) {
                    final Sentence sentence = sentences.get(index);
                    if (offset >= sentence.start && offset <= sentence.end) {
                        startAt(index, sentences.size() - 1);
                        break;
                    }
                }
                return true;
            });

            final LinearLayout footer = new LinearLayout(context);
            footer.setOrientation(LinearLayout.VERTICAL);
            footer.addView(AiUiTheme.divider(context), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AiUiTheme.dp(context, 1)));
            doubtInput = new EditText(context);
            doubtInput.setSingleLine(false);
            doubtInput.setMaxLines(2);
            doubtInput.setHint("Ask a doubt about this lesson");
            doubtInput.setTextColor(AiUiTheme.PRIMARY_TEXT);
            doubtInput.setHintTextColor(AiUiTheme.SECONDARY_TEXT);
            doubtInput.setPadding(AiUiTheme.dp(context, 10), AiUiTheme.dp(context, 7),
                    AiUiTheme.dp(context, 10), AiUiTheme.dp(context, 7));
            doubtInput.setBackground(AiUiTheme.inputBackground(context));
            final Button micButton = AiUiTheme.button(context, "🎙️", AiUiTheme.ButtonKind.MIC);
            sendDoubtButton = AiUiTheme.button(context, "Ask", AiUiTheme.ButtonKind.PRIMARY);
            doubtBar = AiUiTheme.actionRow(context);
            doubtBar.addView(doubtInput, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            doubtBar.addView(micButton);
            doubtBar.addView(sendDoubtButton);
            doubtBar.setVisibility(View.GONE);
            final LinearLayout.LayoutParams doubtParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            doubtParams.setMargins(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 8),
                    AiUiTheme.dp(context, 12), 0);
            footer.addView(doubtBar, doubtParams);

            final LinearLayout primaryControls = AiUiTheme.actionRow(context);
            final Button previousButton = AiUiTheme.button(context, "⏮ Prev",
                    AiUiTheme.ButtonKind.SECONDARY);
            playButton = AiUiTheme.button(context, "▶ Play",
                    AiUiTheme.ButtonKind.PRIMARY);
            final Button nextButton = AiUiTheme.button(context, "⏭ Next",
                    AiUiTheme.ButtonKind.SECONDARY);
            final Button replayButton = AiUiTheme.button(context, "↻ Replay",
                    AiUiTheme.ButtonKind.SECONDARY);
            speedButton = AiUiTheme.button(context, "1.0x",
                    AiUiTheme.ButtonKind.SECONDARY);
            for (Button button : new Button[]{previousButton, playButton, nextButton, replayButton}) {
                primaryControls.addView(button, AiUiTheme.weightedButtonParams(context));
            }
            final LinearLayout secondaryControls = AiUiTheme.actionRow(context);
            final Button askButton = AiUiTheme.button(context, "Ask Doubt 🎙️",
                    AiUiTheme.ButtonKind.MIC);
            resumeButton = AiUiTheme.button(context, "Resume Lesson",
                    AiUiTheme.ButtonKind.SECONDARY);
            final Button saveButton = AiUiTheme.button(context, "Save to Note",
                    AiUiTheme.ButtonKind.SECONDARY);
            secondaryControls.addView(askButton, AiUiTheme.weightedButtonParams(context));
            secondaryControls.addView(resumeButton, AiUiTheme.weightedButtonParams(context));
            secondaryControls.addView(speedButton, AiUiTheme.weightedButtonParams(context));
            secondaryControls.addView(saveButton, AiUiTheme.weightedButtonParams(context));
            final LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            controlParams.setMargins(AiUiTheme.dp(context, 8), AiUiTheme.dp(context, 8),
                    AiUiTheme.dp(context, 8), AiUiTheme.dp(context, 8));
            footer.addView(primaryControls, controlParams);
            footer.addView(secondaryControls, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shell.addView(footer);

            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(shell);
            dialog.setCanceledOnTouchOutside(true);
            final Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0.64f);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }

            addSentences(this.lesson);
            mainSentenceCount = sentences.size();
            updateScript();
            previousButton.setOnClickListener(view -> jumpBy(-1));
            nextButton.setOnClickListener(view -> jumpBy(1));
            playButton.setOnClickListener(view -> togglePlayback());
            replayButton.setOnClickListener(view -> replay());
            speedButton.setOnClickListener(view -> cycleSpeed());
            askButton.setOnClickListener(view -> openDoubtBar());
            resumeButton.setOnClickListener(view -> resumeMainLesson());
            saveButton.setOnClickListener(view -> save());
            micButton.setOnClickListener(view -> {
                if (micCallback != null) {
                    micCallback.start(doubtInput);
                }
            });
            sendDoubtButton.setOnClickListener(view -> sendDoubt());
            hiChip.setOnClickListener(view -> selectLanguage(true));
            enChip.setOnClickListener(view -> selectLanguage(false));
            dialog.setOnDismissListener(ignored -> {
                playing = false;
                if (tts != null) {
                    tts.stop();
                    tts.shutdown();
                    tts = null;
                }
            });
        }

        private void show() {
            dialog.show();
            final Window window = dialog.getWindow();
            if (window != null) {
                final android.util.DisplayMetrics metrics =
                        context.getResources().getDisplayMetrics();
                window.setLayout((int) (metrics.widthPixels * 0.94f),
                        (int) (metrics.heightPixels * 0.88f));
                window.setGravity(Gravity.CENTER);
            }
            tts = new TextToSpeech(context, status -> MAIN_HANDLER.post(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    Toast.makeText(context, "Android text-to-speech could not be initialized.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(final String utteranceId) {
                        MAIN_HANDLER.post(() -> onUtteranceStarted(utteranceId));
                    }

                    @Override
                    public void onDone(final String utteranceId) {
                        MAIN_HANDLER.post(() -> onUtteranceDone(utteranceId));
                    }

                    @Override
                    public void onError(final String utteranceId) {
                        MAIN_HANDLER.post(() -> onUtteranceError(utteranceId));
                    }
                });
                selectLanguage(true);
                ttsReady = true;
                tts.setSpeechRate(speedRates()[speedIndex]);
                if (playing) {
                    speakCurrent();
                }
            }));
        }

        private void addSentences(final String text) {
            if (text.isEmpty()) {
                return;
            }
            if (displayedScript.length() > 0) {
                displayedScript.append("\n\n");
            }
            final int base = displayedScript.length();
            displayedScript.append(text);
            int searchFrom = 0;
            for (String part : AiVoiceTeacherHelper.splitSentences(text)) {
                final int start = text.indexOf(part, searchFrom);
                if (start < 0) {
                    continue;
                }
                sentences.add(new Sentence(part, base + start, base + start + part.length()));
                searchFrom = start + part.length();
            }
        }

        private void requestStyleChange(final AiVoiceTeacherHelper.TeacherStyle style) {
            teacherStyle = style;
            if (styleChangeRequest == null) {
                return;
            }
            pause();
            final int requestSequence = ++styleRequestSequence;
            styleStatus.setText("Re-explaining…");
            styleStatus.setVisibility(View.VISIBLE);
            styleChangeRequest.reExplain(style, (updatedLesson, error) -> MAIN_HANDLER.post(() -> {
                if (!dialog.isShowing() || requestSequence != styleRequestSequence) {
                    return;
                }
                styleStatus.setVisibility(View.GONE);
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show();
                    return;
                }
                replaceLesson(updatedLesson);
            }));
        }

        private void replaceLesson(final String updatedLesson) {
            final String cleanLesson = AiVoiceTeacherHelper.cleanForTts(updatedLesson);
            if (cleanLesson.isEmpty()) {
                Toast.makeText(context, "The AI returned an empty spoken lesson.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            activeUtteranceId = null;
            pause();
            lesson = cleanLesson;
            sentences.clear();
            displayedScript.setLength(0);
            currentSentence = -1;
            rangeEnd = -1;
            mainResumeSentence = 0;
            addSentences(lesson);
            mainSentenceCount = sentences.size();
            updateScript();
        }

        private void updateScript() {
            scriptView.setText(displayedScript);
            if (currentSentence >= 0 && currentSentence < sentences.size()) {
                highlight(currentSentence);
            } else {
                final SpannableStringBuilder styled = new SpannableStringBuilder(displayedScript);
                if (styled.length() > 0) {
                    styled.setSpan(new ForegroundColorSpan(INACTIVE_TEXT), 0, styled.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                scriptView.setText(styled);
            }
        }

        private void highlight(final int index) {
            if (index < 0 || index >= sentences.size()) {
                return;
            }
            final SpannableStringBuilder styled = new SpannableStringBuilder(displayedScript);
            if (styled.length() > 0) {
                styled.setSpan(new ForegroundColorSpan(INACTIVE_TEXT), 0, styled.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            final Sentence sentence = sentences.get(index);
            styled.setSpan(new BackgroundColorSpan(ACTIVE_BACKGROUND), sentence.start, sentence.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(ACTIVE_TEXT), sentence.start, sentence.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), sentence.start, sentence.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            scriptView.setText(styled);
            scriptView.post(() -> {
                if (scriptView.getLayout() == null) {
                    return;
                }
                final int line = scriptView.getLayout().getLineForOffset(sentence.start);
                scriptScroll.smoothScrollTo(0,
                        scriptView.getTop() + scriptView.getTotalPaddingTop()
                                + scriptView.getLayout().getLineTop(line));
            });
        }

        private void togglePlayback() {
            if (playing) {
                pause();
            } else if (!sentences.isEmpty()) {
                if (currentSentence < 0) {
                    currentSentence = Math.max(0, mainResumeSentence);
                }
                startAt(currentSentence, rangeEnd >= currentSentence
                        ? rangeEnd : mainSentenceCount - 1);
            }
        }

        private void startAt(final int sentenceIndex, final int endIndex) {
            if (sentences.isEmpty() || sentenceIndex < 0 || sentenceIndex >= sentences.size()) {
                return;
            }
            if (ttsReady && tts != null) {
                tts.stop();
            }
            currentSentence = sentenceIndex;
            rangeEnd = Math.min(endIndex, sentences.size() - 1);
            playing = true;
            updatePlayButton();
            highlight(currentSentence);
            speakCurrent();
        }

        private void speakCurrent() {
            if (!playing || !ttsReady || tts == null || currentSentence < 0
                    || currentSentence >= sentences.size()) {
                return;
            }
            final String utteranceId = "teacher-" + (++utteranceSequence);
            activeUtteranceId = utteranceId;
            tts.setSpeechRate(speedRates()[speedIndex]);
            final int result = tts.speak(sentences.get(currentSentence).text,
                    TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (result == TextToSpeech.ERROR) {
                onUtteranceError(utteranceId);
            }
        }

        private void onUtteranceStarted(final String utteranceId) {
            if (!dialog.isShowing() || utteranceId == null || !utteranceId.equals(activeUtteranceId)) {
                return;
            }
            highlight(currentSentence);
        }

        private void onUtteranceDone(final String utteranceId) {
            if (!dialog.isShowing() || !playing || utteranceId == null
                    || !utteranceId.equals(activeUtteranceId)) {
                return;
            }
            if (currentSentence < rangeEnd) {
                currentSentence++;
                if (currentSentence < mainSentenceCount) {
                    mainResumeSentence = currentSentence;
                }
                speakCurrent();
            } else {
                playing = false;
                if (currentSentence < mainSentenceCount) {
                    mainResumeSentence = currentSentence;
                }
                updatePlayButton();
            }
        }

        private void onUtteranceError(final String utteranceId) {
            if (!dialog.isShowing() || utteranceId == null || !utteranceId.equals(activeUtteranceId)) {
                return;
            }
            playing = false;
            updatePlayButton();
            Toast.makeText(context, "Could not speak this sentence with the selected voice.",
                    Toast.LENGTH_SHORT).show();
        }

        private void jumpBy(final int amount) {
            if (sentences.isEmpty()) {
                return;
            }
            final int current = currentSentence < 0 ? 0 : currentSentence;
            startAt(Math.max(0, Math.min(sentences.size() - 1, current + amount)),
                    sentences.size() - 1);
        }

        private void replay() {
            if (currentSentence < 0) {
                startAt(0, mainSentenceCount - 1);
            } else {
                startAt(currentSentence, rangeEnd < currentSentence
                        ? mainSentenceCount - 1 : rangeEnd);
            }
        }

        private void pause() {
            playing = false;
            if (tts != null) {
                tts.stop();
            }
            updatePlayButton();
        }

        private void updatePlayButton() {
            playButton.setText(playing ? "⏸ Pause" : "▶ Play");
        }

        private void cycleSpeed() {
            speedIndex = (speedIndex + 1) % speedRates().length;
            speedButton.setText(speedLabels()[speedIndex]);
            if (ttsReady && tts != null) {
                tts.setSpeechRate(speedRates()[speedIndex]);
            }
        }

        private void selectLanguage(final boolean hindi) {
            if (tts == null) {
                return;
            }
            int result = hindi ? tts.setLanguage(new Locale("hi", "IN"))
                    : tts.setLanguage(new Locale("en", "IN"));
            if (hindi) {
                hindiVoiceAvailable = !unsupported(result);
                if (!hindiVoiceAvailable) {
                    result = tts.setLanguage(new Locale("en", "IN"));
                    if (unsupported(result)) {
                        tts.setLanguage(Locale.getDefault());
                    }
                }
            } else if (!hindi && unsupported(result)) {
                tts.setLanguage(Locale.getDefault());
            }
            AiUiTheme.styleChip(hiChip, hindi && hindiVoiceAvailable);
            AiUiTheme.styleChip(enChip, !hindi || !hindiVoiceAvailable);
            if (ttsReady && playing) {
                tts.stop();
                speakCurrent();
            }
        }

        private static boolean unsupported(final int result) {
            return result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED;
        }

        private void openDoubtBar() {
            pause();
            doubtBar.setVisibility(doubtBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (doubtBar.getVisibility() == View.VISIBLE) {
                doubtInput.requestFocus();
                final android.view.inputmethod.InputMethodManager inputMethodManager =
                        (android.view.inputmethod.InputMethodManager) context.getSystemService(
                                Context.INPUT_METHOD_SERVICE);
                if (inputMethodManager != null) {
                    inputMethodManager.showSoftInput(doubtInput,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }

        private void sendDoubt() {
            final String doubt = doubtInput.getText().toString().trim();
            if (doubt.isEmpty() || doubtRequest == null) {
                return;
            }
            mainResumeSentence = currentSentence >= 0 && currentSentence < mainSentenceCount
                    ? currentSentence : mainResumeSentence;
            sendDoubtButton.setEnabled(false);
            doubtInput.setEnabled(false);
            doubtRequest.send(doubt, section, lesson, teacherStyle,
                    (answer, error) -> MAIN_HANDLER.post(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                sendDoubtButton.setEnabled(true);
                doubtInput.setEnabled(true);
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show();
                    return;
                }
                final String cleanAnswer = AiVoiceTeacherHelper.cleanForTts(answer);
                if (cleanAnswer.isEmpty()) {
                    Toast.makeText(context, "The AI returned an empty doubt answer.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                final int firstAnswerSentence = sentences.size();
                addSentences(cleanAnswer);
                updateScript();
                doubtInput.setText("");
                doubtBar.setVisibility(View.GONE);
                startAt(firstAnswerSentence, sentences.size() - 1);
            }));
        }

        private void resumeMainLesson() {
            if (mainSentenceCount == 0) {
                return;
            }
            startAt(Math.max(0, Math.min(mainResumeSentence, mainSentenceCount - 1)),
                    mainSentenceCount - 1);
        }

        private void save() {
            if (saveCallback == null || lesson.trim().isEmpty()) {
                Toast.makeText(context, "There is no lesson explanation to save.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pause();
            saveCallback.save(lesson.trim(), heading);
        }

        private static TextView languageChip(final Context context, final String label) {
            final TextView chip = AiUiTheme.label(context, label, 11);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setClickable(true);
            AiUiTheme.styleChip(chip, false);
            return chip;
        }

        private static float[] speedRates() {
            return new float[]{0.85f, 1.0f, 1.25f, 1.5f};
        }

        private static String[] speedLabels() {
            return new String[]{"0.85x", "1.0x", "1.25x", "1.5x"};
        }
    }

    private static final class Sentence {
        private final String text;
        private final int start;
        private final int end;

        private Sentence(final String text, final int start, final int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }
}
