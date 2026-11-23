package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class GitHubNotesSyncHelperTest {

    @Test
    public void comparedProgressIsThrottledAndReportsCompletion() {
        assertThat(GitHubNotesSyncHelper.shouldReportComparedProgress(99, 1_000)).isFalse();
        assertThat(GitHubNotesSyncHelper.shouldReportComparedProgress(100, 1_000)).isTrue();
        assertThat(GitHubNotesSyncHelper.shouldReportComparedProgress(101, 1_000)).isFalse();
        assertThat(GitHubNotesSyncHelper.shouldReportComparedProgress(1_000, 1_000)).isTrue();
    }

    @Test
    public void comparedProgressReportsAnEmptyScanCompletion() {
        assertThat(GitHubNotesSyncHelper.shouldReportComparedProgress(0, 0)).isTrue();
    }
}
