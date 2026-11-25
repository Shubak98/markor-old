package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class AiOutputRefineHelperTest {
    @Test
    public void buildsRefinementPromptWithCurrentOutputAndInstruction() {
        final String prompt = AiOutputRefineHelper.buildPrompt("Current answer", "  Make it shorter  ");

        assertThat(prompt).contains("Current output:\nCurrent answer")
                .contains("Refinement instruction:\nMake it shorter")
                .contains("Return only the complete revised output");
    }

    @Test
    public void refinementSystemPromptIncludesDefaultEnglishRule() {
        assertThat(AiOutputRefineHelper.buildSystemPrompt())
                .startsWith(AiLanguagePromptHelper.DEFAULT_ENGLISH_RULE)
                .contains("Follow the refinement instruction");
    }
}
