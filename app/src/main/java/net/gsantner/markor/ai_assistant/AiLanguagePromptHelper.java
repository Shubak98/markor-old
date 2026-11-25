package net.gsantner.markor.ai_assistant;

public final class AiLanguagePromptHelper {
    public static final String DEFAULT_ENGLISH_RULE = "LANGUAGE RULE: Even if the user's instruction or voice "
            + "input is in Hindi, Hinglish, or another language, ALWAYS write your entire output (edits, answers, "
            + "notes, MCQs, Anki cards, mnemonics, diagrams, refinements) in clear, standard English by default. "
            + "ONLY switch to Hindi, Roman Hindi/Hinglish, or another language if the user explicitly requests "
            + "that language in their prompt (e.g., 'in Hindi', 'in Roman Hindi', 'Hinglish me likho').";

    private AiLanguagePromptHelper() {
    }

    public static String ensureDefaultEnglishRule(final String systemPrompt) {
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-empty system prompt is required.");
        }
        return systemPrompt.contains(DEFAULT_ENGLISH_RULE)
                ? systemPrompt : DEFAULT_ENGLISH_RULE + "\n\n" + systemPrompt;
    }
}
