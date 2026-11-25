package net.gsantner.markor.ai_assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownSectionParser {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,4})[ \\t]+(.+?)\\s*#*\\s*$");

    private MarkdownSectionParser() {
    }

    public static List<Section> parse(final String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return Collections.emptyList();
        }
        final List<HeadingStart> headings = new ArrayList<>();
        final String[] lines = markdown.split("\n", -1);
        int offset = 0;
        boolean inFence = false;
        char fenceCharacter = 0;
        int fenceLength = 0;
        for (String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                final char character = trimmed.charAt(0);
                int length = 0;
                while (length < trimmed.length() && trimmed.charAt(length) == character) {
                    length++;
                }
                if (!inFence) {
                    inFence = true;
                    fenceCharacter = character;
                    fenceLength = length;
                } else if (character == fenceCharacter && length >= fenceLength) {
                    inFence = false;
                }
            } else if (!inFence) {
                final Matcher matcher = HEADING.matcher(line);
                if (matcher.matches()) {
                    headings.add(new HeadingStart(offset, offset + line.length() + 1,
                            matcher.group(1).length(), matcher.group(2).trim(), line));
                }
            }
            offset += line.length() + 1;
        }

        final List<Section> sections = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            final HeadingStart heading = headings.get(index);
            int end = markdown.length();
            for (int nextIndex = index + 1; nextIndex < headings.size(); nextIndex++) {
                final HeadingStart next = headings.get(nextIndex);
                if (next.level <= heading.level) {
                    end = next.start;
                    break;
                }
            }
            final int contentStart = Math.min(heading.contentStart, markdown.length());
            final int sectionEnd = Math.min(end, markdown.length());
            sections.add(new Section(headings.indexOf(heading), heading.title, heading.level,
                    heading.rawLine, heading.start, sectionEnd,
                    markdown.substring(contentStart, sectionEnd),
                    markdown.substring(heading.start, sectionEnd), -1, false));
        }
        final int[] stack = new int[]{-1, -1, -1, -1};
        for (int index = 0; index < sections.size(); index++) {
            final Section mutable = sections.get(index);
            final int level = mutable.level;
            int parent = -1;
            for (int candidateLevel = level - 1; candidateLevel >= 1; candidateLevel--) {
                if (stack[candidateLevel - 1] >= 0) {
                    parent = stack[candidateLevel - 1];
                    break;
                }
            }
            mutable.parentIndex = parent;
            if (parent >= 0) {
                sections.get(parent).hasChildren = true;
            }
            stack[level - 1] = index;
            for (int clearLevel = level; clearLevel < 4; clearLevel++) {
                stack[clearLevel] = -1;
            }
        }
        return Collections.unmodifiableList(sections);
    }

    public static Section wholeNote(final String markdown) {
        final String content = markdown == null ? "" : markdown;
        return new Section(-1, "Whole Note", 1, "", 0, content.length(),
                content, content, -1, false);
    }

    private static final class HeadingStart {
        private final int start;
        private final int contentStart;
        private final int level;
        private final String title;
        private final String rawLine;

        private HeadingStart(final int start, final int contentStart,
                final int level, final String title, final String rawLine) {
            this.start = start;
            this.contentStart = contentStart;
            this.level = level;
            this.title = title;
            this.rawLine = rawLine;
        }
    }

    public static final class Section {
        private final int index;
        private final String title;
        private final int level;
        private final String rawHeadingLine;
        private final int start;
        private final int end;
        private final String body;
        private final String content;
        private int parentIndex;
        private boolean hasChildren;

        private Section(final int index, final String title, final int level,
                final String rawHeadingLine, final int start, final int end,
                final String body, final String content, final int parentIndex,
                final boolean hasChildren) {
            this.index = index;
            this.title = title;
            this.level = level;
            this.rawHeadingLine = rawHeadingLine;
            this.start = start;
            this.end = end;
            this.body = body;
            this.content = content;
            this.parentIndex = parentIndex;
            this.hasChildren = hasChildren;
        }

        public int getIndex() {
            return index;
        }

        public String getTitle() {
            return title;
        }

        public int getLevel() {
            return level;
        }

        public String getRawHeadingLine() {
            return rawHeadingLine;
        }

        public int getStartOffset() {
            return start;
        }

        public int getStart() {
            return start;
        }

        public int getStartOffsetInt() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public int getEndOffset() {
            return end;
        }

        public String getBody() {
            return body;
        }

        public String getBodyText() {
            return body;
        }

        public String getContent() {
            return content;
        }

        public int getParentIndex() {
            return parentIndex;
        }

        public boolean hasChildren() {
            return hasChildren;
        }
    }
}
