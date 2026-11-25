package net.gsantner.markor.ai_assistant;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;


public final class AiOutputRefineHelper {
    public interface RefineRequest {
        void refine(String currentOutput, String instruction, ResultCallback callback);
    }

    public interface ResultCallback {
        void onResult(String output, String error);
    }

    public interface UpdateCallback {
        void onUpdate(String output);
    }

    public interface MicCallback {
        void start(EditText input);
    }

    private AiOutputRefineHelper() {
    }

    public static String buildPrompt(final String currentOutput, final String instruction) {
        final String output = currentOutput == null ? "" : currentOutput;
        final String request = instruction == null ? "" : instruction.trim();
        return "Revise the existing output according to the user's instruction. Preserve its "
                + "format and all correct information unless the instruction asks otherwise. "
                + "Return only the complete revised output, with no preamble or explanation.\n\n"
                + "Current output:\n" + output + "\n\n"
                + "Refinement instruction:\n" + request;
    }

    public static void show(final Context context, final String currentOutput,
            final RefineRequest request, final UpdateCallback update,
            final MicCallback micCallback) {
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        final EditText instruction = new EditText(context);
        instruction.setSingleLine(false);
        instruction.setMaxLines(3);
        instruction.setHint("Make it shorter, add an example, fix point 2…");
        instruction.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleInput(context, instruction);
        body.addView(instruction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText manual = new EditText(context);
        manual.setSingleLine(false);
        manual.setMinLines(5);
        manual.setMaxLines(12);
        manual.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        manual.setTypeface(android.graphics.Typeface.MONOSPACE);
        manual.setHint("Edit the output directly");
        manual.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        manual.setText(currentOutput == null ? "" : currentOutput);
        styleInput(context, manual);
        manual.setVisibility(View.GONE);
        final LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manualParams.topMargin = AiUiTheme.dp(context, 10);
        body.addView(manual, manualParams);

        final LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout firstRow = AiUiTheme.actionRow(context);
        final LinearLayout secondRow = AiUiTheme.actionRow(context);
        final LinearLayout thirdRow = AiUiTheme.actionRow(context);
        final Button mic = AiUiTheme.button(context, "Mic", AiUiTheme.ButtonKind.MIC);
        final Button refine = AiUiTheme.button(context, "Refine with AI", AiUiTheme.ButtonKind.PRIMARY);
        final Button manualToggle = AiUiTheme.button(context, "Manual Edit", AiUiTheme.ButtonKind.SECONDARY);
        final Button updateButton = AiUiTheme.button(context, "Update Preview", AiUiTheme.ButtonKind.PRIMARY);
        final Button close = AiUiTheme.button(context, "Close", AiUiTheme.ButtonKind.SECONDARY);
        firstRow.addView(mic, AiUiTheme.weightedButtonParams(context));
        firstRow.addView(refine, AiUiTheme.weightedButtonParams(context));
        secondRow.addView(manualToggle, AiUiTheme.weightedButtonParams(context));
        thirdRow.addView(updateButton, AiUiTheme.weightedButtonParams(context));
        thirdRow.addView(close, AiUiTheme.weightedButtonParams(context));
        footer.addView(firstRow);
        footer.addView(secondRow);
        footer.addView(thirdRow);
        final Dialog dialog = AiUiTheme.showDialog(context, "Refine / Edit Output",
                body, footer, null);
        mic.setOnClickListener(view -> {
            if (micCallback != null) {
                micCallback.start(instruction);
            }
        });
        manualToggle.setOnClickListener(view ->
                manual.setVisibility(manual.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        updateButton.setOnClickListener(view -> {
            if (update != null) {
                update.onUpdate(manual.getText().toString());
            }
            dialog.dismiss();
        });
        close.setOnClickListener(view -> dialog.dismiss());
        refine.setOnClickListener(view -> {
            final String prompt = instruction.getText().toString().trim();
            if (prompt.isEmpty()) {
                Toast.makeText(context, "Enter a refinement instruction.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (request == null) {
                Toast.makeText(context, "AI refinement is not available.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            refine.setEnabled(false);
            mic.setEnabled(false);
            request.refine(currentOutput, prompt, (output, error) ->
                    new Handler(Looper.getMainLooper()).post(() -> {
                refine.setEnabled(true);
                mic.setEnabled(true);
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show();
                    return;
                }
                if (output == null || output.trim().isEmpty()) {
                    Toast.makeText(context, "The AI returned an empty refinement.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (update != null) {
                    update.onUpdate(output);
                }
                dialog.dismiss();
            }));
        });
    }

    private static void styleInput(final Context context, final EditText input) {
        input.setTextColor(AiUiTheme.PRIMARY_TEXT);
        input.setHintTextColor(AiUiTheme.SECONDARY_TEXT);
        input.setPadding(AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 9),
                AiUiTheme.dp(context, 12), AiUiTheme.dp(context, 9));
        input.setBackground(AiUiTheme.inputBackground(context));
    }

}
