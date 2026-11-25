package net.gsantner.markor.ai_assistant;

public final class MarkdownSectionReplacement {
    private MarkdownSectionReplacement() {
    }

    public static String replace(final String markdown,
            final MarkdownSectionParser.Section section, final String replacement) {
        if (markdown == null || section == null || replacement == null) {
            throw new IllegalArgumentException("Markdown, section, and replacement are required.");
        }
        final int start = section.getStartOffset();
        final int end = section.getEndOffset();
        if (start < 0 || end < start || end > markdown.length()
                || !markdown.substring(start, end).equals(section.getContent())) {
            throw new IllegalArgumentException("The selected Markdown section is stale or invalid.");
        }
        return markdown.substring(0, start) + replacement + markdown.substring(end);
    }
}
