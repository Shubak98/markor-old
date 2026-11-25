package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class MarkdownSectionReplacementTest {
    @Test
    public void replacesOnlyTheSelectedSectionRange() {
        final String markdown = "# Intro\nLeave this intact.\n\n"
                + "## Polity\nOld section text.\n\n## Economy\nKeep economy.";
        final MarkdownSectionParser.Section section =
                MarkdownSectionParser.parse(markdown).get(1);

        final String updated = MarkdownSectionReplacement.replace(markdown, section,
                "## Polity\nRevised section text.\n\n");

        assertThat(updated).isEqualTo("# Intro\nLeave this intact.\n\n"
                + "## Polity\nRevised section text.\n\n## Economy\nKeep economy.");
    }

    @Test
    public void rejectsASectionFromAChangedDocument() {
        final String markdown = "# First\nText\n\n# Second\nMore";
        final MarkdownSectionParser.Section section =
                MarkdownSectionParser.parse(markdown).get(0);

        assertThatThrownBy(() -> MarkdownSectionReplacement.replace(
                markdown.replace("Text", "Changed"), section, "replacement"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
