package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ThreeWayTextMergeTest {

    @Test
    public void mergesEditsToDifferentSegments() {
        final ThreeWayTextMerge.MergeResult result = ThreeWayTextMerge.merge(
                "Segment A\nSegment B\nSegment C\n",
                "Edited Segment A\nSegment B\nSegment C\n",
                "Segment A\nSegment B\nEdited Segment C\n");

        assertThat(result.isClean()).isTrue();
        assertThat(result.getMergedText()).isEqualTo(
                "Edited Segment A\nSegment B\nEdited Segment C\n");
    }

    @Test
    public void mergesDeletionAndEditToDifferentSegments() {
        final ThreeWayTextMerge.MergeResult result = ThreeWayTextMerge.merge(
                "Segment A\nSegment B\nSegment C\n",
                "Segment B\nSegment C\n",
                "Segment A\nEdited Segment B\nSegment C\n");

        assertThat(result.isClean()).isTrue();
        assertThat(result.getMergedText()).isEqualTo("Edited Segment B\nSegment C\n");
    }

    @Test
    public void conflictsWhenOneSideDeletesAndTheOtherEditsTheSameSegment() {
        final ThreeWayTextMerge.MergeResult result = ThreeWayTextMerge.merge(
                "Segment A\nSegment B\n",
                "Segment B\n",
                "Edited Segment A\nSegment B\n");

        assertThat(result.isClean()).isFalse();
    }

    @Test
    public void keepsIdenticalChangesOnlyOnce() {
        final ThreeWayTextMerge.MergeResult result = ThreeWayTextMerge.merge(
                "Segment A\nSegment B\n",
                "Edited Segment A\nSegment B\n",
                "Edited Segment A\nSegment B\n");

        assertThat(result.isClean()).isTrue();
        assertThat(result.getMergedText()).isEqualTo("Edited Segment A\nSegment B\n");
    }
}
