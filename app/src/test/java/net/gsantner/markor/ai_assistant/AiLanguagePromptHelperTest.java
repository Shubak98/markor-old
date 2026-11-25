package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AiLanguagePromptHelperTest {
    @Test
    public void intentSystemInstructionDefaultsEveryToolToEnglish() {
        final String systemInstruction = GeminiMarkdownHelper.getIntentSystemInstruction();

        assertThat(systemInstruction).startsWith(AiLanguagePromptHelper.DEFAULT_ENGLISH_RULE)
                .contains("MCQs, Anki cards, mnemonics, diagrams, refinements")
                .contains("ONLY switch to Hindi, Roman Hindi/Hinglish, or another language if the user "
                        + "explicitly requests that language");
    }

    @Test
    public void mnemonicSystemPromptIncludesDefaultEnglishRule() {
        assertThat(AiMnemonicsDialog.buildSystemPrompt())
                .startsWith(AiLanguagePromptHelper.DEFAULT_ENGLISH_RULE)
                .contains("English-language memory hook");
    }

    @Test
    public void nonTeacherRouterPromptsIncludeDefaultEnglishRule() {
        assertThat(AiProviderRouter.buildNonTeacherSystemPrompt("You are a precise editor."))
                .startsWith(AiLanguagePromptHelper.DEFAULT_ENGLISH_RULE)
                .endsWith("You are a precise editor.");
    }

    @Test
    public void teacherSystemPromptRemainsUnchanged() throws Exception {
        assertThat(sha256(AiVoiceTeacherHelper.SYSTEM_PROMPT))
                .isEqualTo("499a5a66f625e25fe74ef297aedf2d6efd29e083a5387c7fa48fa7aaf2361408");
    }

    private static String sha256(final String value) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            final String pair = Integer.toHexString(item & 0xff);
            if (pair.length() == 1) {
                hex.append('0');
            }
            hex.append(pair);
        }
        return hex.toString();
    }
}
