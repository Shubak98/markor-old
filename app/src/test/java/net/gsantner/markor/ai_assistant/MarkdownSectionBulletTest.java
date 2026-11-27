package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.util.List;

public class MarkdownSectionBulletTest {
    @Test
    public void parsesTopLevelBulletKindsAndGroupsIndentedSubBullets() {
        final String markdown = "# Nation\n"
                + "- India is called **Indestructible**.\n"
                + "  - Ancient civilization\n"
                + "  - Diverse culture\n"
                + "* A second item\n"
                + "1. Numbered [item](https://example.test)\n"
                + "```markdown\n"
                + "- Not a bullet item\n"
                + "```\n"
                + "## Economy\n"
                + "- Economy bullet\n";

        final List<MarkdownSectionParser.Section> sections =
                MarkdownSectionParser.parse(markdown);
        final MarkdownSectionParser.Section nation = sections.get(0);
        final MarkdownSectionParser.Section first = nation.getBulletItems().get(0);

        assertThat(nation.getBulletCount()).isEqualTo(3);
        assertThat(first.isBullet()).isTrue();
        assertThat(first.getParentHeadingTitle()).isEqualTo("Nation");
        assertThat(first.getTitle()).isEqualTo("India is called Indestructible.");
        assertThat(first.getBodyText()).isEqualTo("- India is called **Indestructible**.\n"
                + "  - Ancient civilization\n"
                + "  - Diverse culture");
        assertThat(first.getStartOffset()).isEqualTo(markdown.indexOf("- India"));
        assertThat(markdown.substring(first.getStartOffset(), first.getEndOffset()))
                .isEqualTo(first.getBodyText());
        assertThat(nation.getBulletItems().get(1).getTitle()).isEqualTo("A second item");
        assertThat(nation.getBulletItems().get(2).getTitle()).isEqualTo("Numbered item");
        assertThat(nation.getBulletItems().get(2).getBodyText())
                .isEqualTo("1. Numbered [item](https://example.test)");
        assertThat(sections.get(1).getBulletCount()).isEqualTo(1);
        assertThat(sections.get(1).getBulletItems().get(0).getTitle())
                .isEqualTo("Economy bullet");
    }

    @Test
    public void replacingOneBulletPreservesNeighboringBulletsAndHeadingsVerbatim() {
        final String markdown = "# Intro\nKeep intro.\n\n"
                + "## Nation\n- first bullet\n- **Target** item\n- third bullet\n\n"
                + "## Economy\nKeep economy.";
        final MarkdownSectionParser.Section target = MarkdownSectionParser.parse(markdown)
                .get(1).getBulletItems().get(1);

        assertThat(markdown.substring(target.getStartOffset(), target.getEndOffset()))
                .isEqualTo("- **Target** item");

        final String updated = MarkdownSectionReplacement.replace(markdown, target,
                "- Revised target");

        assertThat(updated).isEqualTo("# Intro\nKeep intro.\n\n"
                + "## Nation\n- first bullet\n- Revised target\n- third bullet\n\n"
                + "## Economy\nKeep economy.");
    }

    @Test
    public void bulletReplacementPreservesCarriageReturnLineEndings() {
        final String markdown = "# Nation\r\n- Target\r\n- Neighbor\r\n";
        final MarkdownSectionParser.Section bullet =
                MarkdownSectionParser.parse(markdown).get(0).getBulletItems().get(0);

        assertThat(bullet.getBodyText()).isEqualTo("- Target");
        assertThat(MarkdownSectionReplacement.replace(markdown, bullet, "- Revised"))
                .isEqualTo("# Nation\r\n- Revised\r\n- Neighbor\r\n");
    }
}
