package net.gsantner.markor.ai_assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ThreeWayTextMerge {
    private ThreeWayTextMerge() {
    }

    public static MergeResult merge(final String base, final String local, final String remote) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remote, "remote");
        if (local.equals(remote)) {
            return new MergeResult(true, local);
        }

        final String[] baseLines = splitLines(base);
        final List<Edit> localEdits = edits(base, local);
        final List<Edit> remoteEdits = edits(base, remote);
        final List<Edit> combined = new ArrayList<>(localEdits);
        for (Edit remoteEdit : remoteEdits) {
            boolean identical = false;
            for (Edit localEdit : localEdits) {
                if (localEdit.sameAs(remoteEdit)) {
                    identical = true;
                } else if (overlaps(localEdit, remoteEdit)) {
                    return new MergeResult(false, local);
                }
            }
            if (!identical) {
                combined.add(remoteEdit);
            }
        }
        Collections.sort(combined, Comparator.comparingInt((Edit edit) -> edit.start)
                .thenComparingInt(edit -> edit.end));

        final List<String> mergedLines = new ArrayList<>();
        int cursor = 0;
        for (Edit edit : combined) {
            if (edit.start < cursor) {
                return new MergeResult(false, local);
            }
            for (; cursor < edit.start; cursor++) {
                mergedLines.add(baseLines[cursor]);
            }
            mergedLines.addAll(edit.replacement);
            cursor = edit.end;
        }
        for (; cursor < baseLines.length; cursor++) {
            mergedLines.add(baseLines[cursor]);
        }
        return new MergeResult(true, joinLines(mergedLines));
    }

    private static List<Edit> edits(final String base, final String updated) {
        final List<SimpleLineDiff.Line> diff = SimpleLineDiff.diff(base, updated);
        final List<Edit> edits = new ArrayList<>();
        List<String> replacement = null;
        int cursor = 0;
        int start = 0;
        for (SimpleLineDiff.Line line : diff) {
            switch (line.getType()) {
                case UNCHANGED:
                    if (replacement != null) {
                        edits.add(new Edit(start, cursor, replacement));
                        replacement = null;
                    }
                    cursor++;
                    break;
                case REMOVED:
                    if (replacement == null) {
                        replacement = new ArrayList<>();
                        start = cursor;
                    }
                    cursor++;
                    break;
                case ADDED:
                    if (replacement == null) {
                        replacement = new ArrayList<>();
                        start = cursor;
                    }
                    replacement.add(line.getText());
                    break;
                default:
                    throw new IllegalStateException("Unexpected line diff type.");
            }
        }
        if (replacement != null) {
            edits.add(new Edit(start, cursor, replacement));
        }
        return edits;
    }

    private static boolean overlaps(final Edit first, final Edit second) {
        if (first.start == first.end && second.start == second.end) {
            return first.start == second.start;
        }
        if (first.start == first.end) {
            return first.start > second.start && first.start < second.end;
        }
        if (second.start == second.end) {
            return second.start > first.start && second.start < first.end;
        }
        return first.start < second.end && second.start < first.end;
    }

    private static String[] splitLines(final String text) {
        return text.isEmpty() ? new String[0] : text.split("\n", -1);
    }

    private static String joinLines(final List<String> lines) {
        final StringBuilder text = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                text.append('\n');
            }
            text.append(lines.get(index));
        }
        return text.toString();
    }

    private static final class Edit {
        private final int start;
        private final int end;
        private final List<String> replacement;

        private Edit(final int start, final int end, final List<String> replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }

        private boolean sameAs(final Edit other) {
            return start == other.start && end == other.end
                    && replacement.equals(other.replacement);
        }
    }

    public static final class MergeResult {
        private final boolean clean;
        private final String mergedText;

        private MergeResult(final boolean clean, final String mergedText) {
            this.clean = clean;
            this.mergedText = mergedText;
        }

        public boolean isClean() {
            return clean;
        }

        public String getMergedText() {
            return mergedText;
        }
    }
}
