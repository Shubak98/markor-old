package net.gsantner.markor.ai_assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SimpleLineDiff {
    private static final long MAX_LCS_CELLS = 4_000_000L;

    public enum Type {
        UNCHANGED,
        ADDED,
        REMOVED,
        COLLAPSED
    }

    public static final class Line {
        private final Type type;
        private final String text;

        private Line(final Type type, final String text) {
            this.type = type;
            this.text = text;
        }

        public Type getType() {
            return type;
        }

        public String getText() {
            return text;
        }
    }

    private SimpleLineDiff() {
    }

    public static List<Line> diff(final String original, final String updated) {
        final String[] before = splitLines(Objects.requireNonNull(original, "original"));
        final String[] after = splitLines(Objects.requireNonNull(updated, "updated"));
        final long cells = (long) before.length * after.length;
        if (cells > MAX_LCS_CELLS) {
            return coarseDiff(before, after);
        }

        final int[][] lcs = new int[before.length + 1][after.length + 1];
        for (int i = before.length - 1; i >= 0; i--) {
            for (int j = after.length - 1; j >= 0; j--) {
                lcs[i][j] = before[i].equals(after[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        final List<Line> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < before.length && j < after.length) {
            if (before[i].equals(after[j])) {
                result.add(new Line(Type.UNCHANGED, before[i++]));
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Line(Type.REMOVED, before[i++]));
            } else {
                result.add(new Line(Type.ADDED, after[j++]));
            }
        }
        while (i < before.length) {
            result.add(new Line(Type.REMOVED, before[i++]));
        }
        while (j < after.length) {
            result.add(new Line(Type.ADDED, after[j++]));
        }
        return result;
    }

    public static List<Line> buildCompactDiffLines(final String original, final String updated,
            final int contextLines) {
        if (contextLines < 0) {
            throw new IllegalArgumentException("contextLines must not be negative");
        }
        final List<Line> lines = diff(original, updated);
        final List<Line> compact = new ArrayList<>();
        final boolean[] visible = new boolean[lines.size()];
        boolean hasChanges = false;
        for (int i = 0; i < lines.size();) {
            if (lines.get(i).getType() == Type.UNCHANGED) {
                i++;
                continue;
            }
            hasChanges = true;
            final int start = i;
            int end = i;
            while (end + 1 < lines.size() && lines.get(end + 1).getType() != Type.UNCHANGED) {
                end++;
            }
            int contextBefore = contextLines;
            for (int context = start - 1; context >= 0 && contextBefore > 0
                    && lines.get(context).getType() == Type.UNCHANGED; context--, contextBefore--) {
                visible[context] = true;
            }
            int contextAfter = contextLines;
            for (int context = end + 1; context < lines.size() && contextAfter > 0
                    && lines.get(context).getType() == Type.UNCHANGED; context++, contextAfter--) {
                visible[context] = true;
            }
            for (int change = start; change <= end; change++) {
                visible[change] = true;
            }
            i = end + 1;
        }
        if (!hasChanges) {
            compact.add(new Line(Type.UNCHANGED, "No changes detected."));
            return compact;
        }

        int hiddenUnchanged = 0;
        for (int i = 0; i < lines.size(); i++) {
            final Line line = lines.get(i);
            if (line.getType() == Type.UNCHANGED && !visible[i]) {
                hiddenUnchanged++;
                continue;
            }
            if (hiddenUnchanged > 0) {
                compact.add(new Line(Type.COLLAPSED,
                        "--- " + hiddenUnchanged + " unchanged lines hidden ---"));
                hiddenUnchanged = 0;
            }
            compact.add(line);
        }
        if (hiddenUnchanged > 0) {
            compact.add(new Line(Type.COLLAPSED,
                    "--- " + hiddenUnchanged + " unchanged lines hidden ---"));
        }
        return compact;
    }

    private static String[] splitLines(final String text) {
        return text.isEmpty() ? new String[0] : text.split("\n", -1);
    }

    private static List<Line> coarseDiff(final String[] before, final String[] after) {
        int prefix = 0;
        while (prefix < before.length && prefix < after.length
                && before[prefix].equals(after[prefix])) {
            prefix++;
        }
        int beforeEnd = before.length;
        int afterEnd = after.length;
        while (beforeEnd > prefix && afterEnd > prefix
                && before[beforeEnd - 1].equals(after[afterEnd - 1])) {
            beforeEnd--;
            afterEnd--;
        }
        final List<Line> result = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            result.add(new Line(Type.UNCHANGED, before[i]));
        }
        for (int i = prefix; i < beforeEnd; i++) {
            result.add(new Line(Type.REMOVED, before[i]));
        }
        for (int i = prefix; i < afterEnd; i++) {
            result.add(new Line(Type.ADDED, after[i]));
        }
        for (int i = beforeEnd; i < before.length; i++) {
            result.add(new Line(Type.UNCHANGED, before[i]));
        }
        return result;
    }
}
