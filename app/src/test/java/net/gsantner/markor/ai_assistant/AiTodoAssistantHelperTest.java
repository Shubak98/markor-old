package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class AiTodoAssistantHelperTest {
    @Test
    public void parsesCompletionPriorityDueDateAndLinkedNote() {
        final List<AiTodoAssistantHelper.Task> tasks = AiTodoAssistantHelper.parseTasks(
                "(A) Read chapter due:2026-11-27 ref:biology/overview.md\n"
                        + "x 2026-11-20 (B) Finished task due:2026-11-21\n"
                        + "(Z) Review notes due:2026-12-01\n");

        assertThat(tasks).hasSize(3);
        assertThat(tasks.get(0).getPriority()).isEqualTo('A');
        assertThat(tasks.get(0).getDueDate()).isEqualTo("2026-11-27");
        assertThat(tasks.get(0).getRefPath()).isEqualTo("biology/overview.md");
        assertThat(tasks.get(0).getTitle()).isEqualTo("Read chapter");
        assertThat(tasks.get(1).isCompleted()).isTrue();
        assertThat(tasks.get(1).getPriority()).isEqualTo('B');
        assertThat(tasks.get(2).getPriority()).isEqualTo('Z');
    }

    @Test
    public void filtersActiveTasksAgainstTheSuppliedLocalDate() {
        final String todoText = "(A) Overdue due:2026-11-26\n"
                + "Today due:2026-11-27\n"
                + "(A) Today priority due:2026-11-27\n"
                + "Tomorrow due:2026-11-28\n"
                + "No due date\n"
                + "x 2026-11-20 Finished due:2026-11-26\n";

        assertThat(AiTodoAssistantHelper.overdueTasks(todoText, "2026-11-27"))
                .extracting(AiTodoAssistantHelper.Task::getTitle)
                .containsExactly("Overdue");
        assertThat(AiTodoAssistantHelper.todayTasks(todoText, "2026-11-27"))
                .extracting(AiTodoAssistantHelper.Task::getTitle)
                .containsExactly("Today", "Today priority");
        assertThat(AiTodoAssistantHelper.upcomingTasks(todoText, "2026-11-27"))
                .extracting(AiTodoAssistantHelper.Task::getTitle)
                .containsExactly("Tomorrow", "No due date");
    }

    @Test
    public void rejectsLinkedNoteReferencesThatEscapeNotebookRoot() throws IOException {
        final File root = new File(System.getProperty("java.io.tmpdir"),
                "ai-todo-helper-" + System.nanoTime());
        final File note = new File(root, "biology/overview.md");
        final File spacedNote = new File(root, "biology/study plan.md");
        if (!note.getParentFile().mkdirs() || !note.createNewFile()) {
            throw new IOException("Could not create temporary Markdown note for test.");
        }
        try {
            assertThat(AiTodoAssistantHelper.isSafeNoteReference(root, "biology/overview.md"))
                    .isTrue();
            assertThat(AiTodoAssistantHelper.isSafeNoteReference(root, "../outside.md"))
                    .isFalse();
            assertThat(AiTodoAssistantHelper.isSafeNoteReference(root, "%2e%2e/outside.md"))
                    .isFalse();
            assertThatThrownBy(() ->
                    AiTodoAssistantHelper.resolveNoteReference(root, "../outside.md"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(AiTodoAssistantHelper.resolveNoteReference(root, "biology/overview.md"))
                    .isEqualTo(note.getCanonicalFile());
            if (!spacedNote.createNewFile()) {
                throw new IOException("Could not create temporary Markdown note for test.");
            }
            final String linkedPath = AiTodoAssistantHelper.relativeNotePath(root, spacedNote);
            assertThat(linkedPath).isEqualTo("biology/study%20plan.md");
            assertThat(AiTodoAssistantHelper.resolveNoteReference(root, linkedPath))
                    .isEqualTo(spacedNote.getCanonicalFile());
        } finally {
            final boolean spacedNoteDeleted = !spacedNote.exists() || spacedNote.delete();
            final boolean noteDeleted = note.delete();
            final boolean parentDeleted = note.getParentFile().delete();
            final boolean rootDeleted = root.delete();
            if (!spacedNoteDeleted || !noteDeleted || !parentDeleted || !rootDeleted) {
                throw new IOException("Could not clean temporary Markdown note test files.");
            }
        }
    }

    @Test
    public void appliesTaskPatchWithoutChangingNeighboringLines() throws IOException {
        final String original = "(A) Keep due:2026-11-27\n(B) Change this\nOther task\n";
        final List<AiTodoAssistantHelper.PatchOperation> patch = java.util.Arrays.asList(
                AiTodoAssistantHelper.PatchOperation.replace(2, "(B) Changed task"),
                AiTodoAssistantHelper.PatchOperation.add(3, "New task due:2026-11-28"));

        assertThat(AiTodoAssistantHelper.applyPatch(original, patch))
                .isEqualTo("(A) Keep due:2026-11-27\n(B) Changed task\nOther task\n"
                        + "New task due:2026-11-28\n");
    }

    @Test
    public void shiftsOnlyOverdueActiveDueDatesAndPreservesLineEndings() {
        final String original = "(A) Overdue due:2026-11-26\r\n"
                + "Today due:2026-11-27\r\n"
                + "x 2026-11-20 Done due:2026-11-25\r\n";

        assertThat(AiTodoAssistantHelper.shiftOverdueToToday(original, "2026-11-27"))
                .isEqualTo("(A) Overdue due:2026-11-27\r\n"
                        + "Today due:2026-11-27\r\n"
                        + "x 2026-11-20 Done due:2026-11-25\r\n");
    }
}
