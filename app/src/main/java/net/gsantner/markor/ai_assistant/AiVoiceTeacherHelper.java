package net.gsantner.markor.ai_assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public final class AiVoiceTeacherHelper {
    public static final String SYSTEM_PROMPT = "You are an English-medium Indian UPSC and State PCS "
            + "coaching teacher teaching a Hindi-mother-tongue, English-medium Civil Services aspirant. "
            + "Speak strictly like a real English-medium Indian teacher: use natural, colloquial Hinglish. "
            + "Keep ALL technical terms, constitutional phrases, legal and academic words, and normal "
            + "English-medium classroom vocabulary in English script, including words such as Constitution, "
            + "Federation, Union of States, Formation, agreement, treaty, consent, dissolve, reorganise, "
            + "boundary, right to secede, mandatory, difference, and statement. Use ONLY simple, everyday "
            + "spoken Hindi connectors and verbs in Devanagari, such as “का मतलब है”, “कहा जाता है”, "
            + "“बनता है”, “नहीं होता”, “ध्यान रखना”, and “इसके उलट”. STRICTLY FORBID formal, Sanskritized, "
            + "or bookish Hindi. Never say “संविधान”, “परिसंघ”, “सहमति”, “अविनाशी”, “विघटित”, “प्रारंभ”, "
            + "“उल्लेख”, “प्रमुख”, “अर्थात”, or “प्रावधान”; use Constitution, Federation, consent, "
            + "indestructible, dissolve, commencement, mention, main, meaning, and provision instead.\n"
            + "Example of the exact desired tone:\n"
            + "India को Union of States कहा जाता है, Federation नहीं।\n"
            + "States के बीच कोई agreement नहीं होता, और उन्हें Union से secede करने का right नहीं है।\n"
            + "Never use a childish tone, fluff, storytelling filler, or unsupported facts. Output only "
            + "TTS-clean spoken sentences. Do not use Markdown, headings, bullets, tables, bold, backticks, "
            + "or decorative symbols.";
    public static final TeacherStyle DEFAULT_TEACHER_STYLE = TeacherStyle.EXACT_SHORT;
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^\\]]+)\\]\\[[^\\]]*\\]");

    public enum TeacherStyle {
        EXACT_SHORT("exact_short", "Exact Short"),
        RAPID_TRAPS("rapid_traps", "Rapid + Traps"),
        PRELIMS_STATEMENTS("prelims_statements", "Prelims Statements");

        private final String preferenceValue;
        private final String label;

        TeacherStyle(final String preferenceValue, final String label) {
            this.preferenceValue = preferenceValue;
            this.label = label;
        }

        public String getPreferenceValue() {
            return preferenceValue;
        }

        public String getLabel() {
            return label;
        }

        public static TeacherStyle fromPreferenceValue(final String value) {
            for (TeacherStyle style : values()) {
                if (style.preferenceValue.equals(value)) {
                    return style;
                }
            }
            return DEFAULT_TEACHER_STYLE;
        }
    }

    private AiVoiceTeacherHelper() {
    }

    public static String lessonPrompt(final String section) {
        return lessonPrompt(section, DEFAULT_TEACHER_STYLE);
    }

    public static String lessonPrompt(final String section, final TeacherStyle style) {
        final TeacherStyle selectedStyle = style == null ? DEFAULT_TEACHER_STYLE : style;
        final String instructions;
        switch (selectedStyle) {
            case RAPID_TRAPS:
                instructions = "Give a structured UPSC and State PCS rapid-revision lesson. Start with a "
                        + "concise core formula, then give a crisp comparative breakdown, and finish with "
                        + "one or two sharp Prelims Trap Alerts about exact wording tested on this topic. "
                        + "Keep traps accurate and tied to the topic. Use only spoken sentences; do not "
                        + "read Markdown or table syntax.";
                break;
            case PRELIMS_STATEMENTS:
                instructions = "Turn the supplied block into four to six concise UPSC Prelims statement "
                        + "checks for exam-hall elimination practice. Say each as “Statement 1: ... — "
                        + "सही/गलत, क्योंकि ...”, explaining the decisive wording briefly. Keep every "
                        + "check grounded in the supplied block and preserve English technical terms "
                        + "verbatim. Use only spoken sentences; do not read Markdown or table syntax.";
                break;
            case EXACT_SHORT:
            default:
                instructions = "Explain only what is written in the supplied block, in crisp, concise "
                        + "spoken Hinglish. Preserve every fact, distinction, and meaning without adding "
                        + "outside information, fluff, examples, or storytelling. Use Devanagari Hindi for "
                        + "Hindi words and keep all English technical terms, articles, definitions, and "
                        + "keywords verbatim in English. Convert tables into natural spoken side-by-side "
                        + "contrast; never read pipes or Markdown syntax.";
                break;
        }
        return instructions + "\n\nSelected block:\n" + safe(section);
    }

    public static String doubtPrompt(final String section, final String lesson, final String doubt) {
        return doubtPrompt(section, lesson, doubt, DEFAULT_TEACHER_STYLE);
    }

    public static String doubtPrompt(final String section, final String lesson, final String doubt,
            final TeacherStyle style) {
        final String styleInstruction = style == TeacherStyle.PRELIMS_STATEMENTS
                ? "Where useful, answer through a concise Sahi/Galat statement check."
                : style == TeacherStyle.RAPID_TRAPS
                ? "Keep the answer rapid and point out a relevant wording trap only when supported."
                : "Answer only the doubt using the taught material, without adding outside details.";
        return "The student is listening to this Civil Services lesson and asks a doubt. Give a concise, "
                + "mature answer in the same natural, colloquial English-medium Hinglish as the system "
                + "rules: use only everyday spoken Hindi connectors and verbs in Devanagari, keep English "
                + "technical and classroom vocabulary in English script, and never use formal or bookish "
                + "Hindi. " + styleInstruction + " Output only TTS-clean spoken sentences, without Markdown.\n\n"
                + "Taught section:\n" + safe(section)
                + "\n\nCurrent lesson:\n" + safe(lesson)
                + "\n\nStudent doubt:\n" + safe(doubt);
    }

    public static String cleanForTts(final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        cleaned = REFERENCE_LINK.matcher(cleaned).replaceAll("$1");
        cleaned = cleaned.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*(?:[-+*]|\\d+[.)])\\s+", "")
                .replaceAll("(?m)^\\s*(?:\\|?\\s*:?-{3,}:?\\s*\\|?\\s*)+$", "")
                .replaceAll("\\*\\*|__|~~|`{1,3}|[*_~#|]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^\\s+|\\s+$", "");
        return cleaned;
    }

    public static List<String> splitSentences(final String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (".?!।".indexOf(character) >= 0) {
                while (index + 1 < text.length() && ".?!।".indexOf(text.charAt(index + 1)) >= 0) {
                    index++;
                }
                addTrimmed(sentences, text.substring(start, index + 1));
                start = index + 1;
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                addTrimmed(sentences, text.substring(start, index + 1));
                start = index + 1;
            }
        }
        addTrimmed(sentences, text.substring(start));
        return Collections.unmodifiableList(sentences);
    }

    private static void addTrimmed(final List<String> sentences, final String sentence) {
        final String trimmed = sentence.trim();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }

    private static String safe(final String value) {
        return value == null ? "" : value.trim();
    }
}
