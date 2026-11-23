package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.json.JSONException;
import org.json.JSONObject;
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

    @Test
    public void deletionTreeEntryIncludesGitHubRequiredFields() throws JSONException {
        final JSONObject entry = GitHubNotesSyncHelper.createDeletionTreeEntry("notes/todo.md");

        assertThat(entry.getString("path")).isEqualTo("notes/todo.md");
        assertThat(entry.getString("mode")).isEqualTo("100644");
        assertThat(entry.getString("type")).isEqualTo("blob");
        assertThat(entry.get("sha")).isSameAs(JSONObject.NULL);
    }
}
