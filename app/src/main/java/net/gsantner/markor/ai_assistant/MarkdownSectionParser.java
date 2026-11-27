package net.gsantner.markor.ai_assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownSectionParser {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,4})[ \\t]+(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^ {0,3}([-+*]|\\d+[.)])[ \\t]+(.*)$");
    private static final int MAX_BULLET_TITLE_CODE_POINTS = 72;

    private MarkdownSectionParser() {
    }

    public static List<Section> parse(final String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return Collections.emptyList();
        }
        final List<MarkdownLine> lines = splitLines(markdown);
        final List<HeadingStart> headings = new ArrayList<>();
        int lineIndex = 0;
        for (MarkdownLine line : lines) {
            if (!line.inFence) {
                final Matcher matcher = HEADING.matcher(line.text);
                if (matcher.matches()) {
                    headings.add(new HeadingStart(line.start, line.end,
                            matcher.group(1).length(), matcher.group(2).trim(),
                            line.rawText, lineIndex));
                }
            }
            lineIndex++;
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
            int directBodyEnd = end;
            for (int nextIndex = index + 1; nextIndex < headings.size(); nextIndex++) {
                final HeadingStart next = headings.get(nextIndex);
                if (next.start >= end) {
                    break;
                }
                if (next.level > heading.level) {
                    directBodyEnd = next.start;
                    break;
                }
            }
            final int contentStart = Math.min(heading.contentStart, markdown.length());
            final int sectionEnd = Math.min(end, markdown.length());
            final Section section = new Section(index, heading.title, heading.level,
                    heading.rawLine, heading.start, sectionEnd,
                    markdown.substring(contentStart, sectionEnd),
                    markdown.substring(heading.start, sectionEnd), -1, false);
            section.setBulletItems(parseBulletItems(markdown, lines, heading,
                    contentStart, Math.min(directBodyEnd, sectionEnd), index));
            sections.add(section);
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

    private static List<MarkdownLine> splitLines(final String markdown) {
        final List<MarkdownLine> lines = new ArrayList<>();
        final String[] rawLines = markdown.split("\n", -1);
        int offset = 0;
        boolean inFence = false;
        char fenceCharacter = 0;
        int fenceLength = 0;
        for (String line : rawLines) {
            final String trimmed = line.trim();
            boolean opensFence = false;
            boolean closesFence = false;
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
                    opensFence = true;
                } else if (character == fenceCharacter && length >= fenceLength) {
                    inFence = false;
                    closesFence = true;
                }
            }
            final int lineContentLength = line.length()
                    - (offset + line.length() < markdown.length() && line.endsWith("\r") ? 1 : 0);
            final String lineText = line.substring(0, lineContentLength);
            final int contentEnd = Math.min(markdown.length(), offset + lineContentLength);
            final int lineEnd = Math.min(markdown.length(), offset + line.length() + 1);
            lines.add(new MarkdownLine(offset, contentEnd, lineEnd, line, lineText,
                    inFence || closesFence, opensFence, closesFence));
            offset += line.length() + 1;
        }
        return lines;
    }

    private static List<Section> parseBulletItems(final String markdown,
            final List<MarkdownLine> lines, final HeadingStart heading,
            final int contentStart, final int contentEnd, final int parentIndex) {
        final List<Section> bullets = new ArrayList<>();
        for (int lineIndex = heading.lineIndex + 1; lineIndex < lines.size(); lineIndex++) {
            final MarkdownLine line = lines.get(lineIndex);
            if (line.start >= contentEnd) {
                break;
            }
            if (line.start < contentStart || line.inFence) {
                continue;
            }
            final Matcher matcher = BULLET.matcher(line.text);
            if (!matcher.matches()) {
                continue;
            }

            final int contentIndent = columnAt(line.text, matcher.start(2));
            int bulletEnd = line.contentEnd;
            int scanIndex = lineIndex + 1;
            boolean inBulletFence = false;
            while (scanIndex < lines.size()) {
                final MarkdownLine continuation = lines.get(scanIndex);
                if (continuation.start >= contentEnd) {
                    break;
                }
                if (inBulletFence) {
                    bulletEnd = continuation.contentEnd;
                    inBulletFence = !continuation.closesFence;
                    scanIndex++;
                    continue;
                }

                if (continuation.opensFence) {
                    if (indentationColumns(continuation.text,
                            firstNonWhitespace(continuation.text)) < contentIndent) {
                        break;
                    }
                    bulletEnd = continuation.contentEnd;
                    inBulletFence = !continuation.closesFence;
                    scanIndex++;
                    continue;
                }
                if (continuation.inFence) {
                    break;
                }

                if (continuation.text.trim().isEmpty()) {
                    scanIndex++;
                    continue;
                }
                final Matcher nextBullet = BULLET.matcher(continuation.text);
                final boolean isBullet = nextBullet.matches();
                final int indentation = indentationColumns(continuation.text,
                        firstNonWhitespace(continuation.text));
                if (indentation < contentIndent
                        || isBullet && indentationColumns(continuation.text,
                                nextBullet.start(1)) < contentIndent) {
                    break;
                }
                bulletEnd = continuation.contentEnd;
                scanIndex++;
            }

            final String content = markdown.substring(line.start, bulletEnd);
            final String title = cleanBulletTitle(matcher.group(2));
            bullets.add(new Section(-1, title, heading.level, heading.rawLine,
                    line.start, bulletEnd, content, content, parentIndex, false,
                    true, cleanInlineMarkdown(heading.title)));
            lineIndex = scanIndex - 1;
        }
        return Collections.unmodifiableList(bullets);
    }

    private static int firstNonWhitespace(final String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int indentationColumns(final String line, final int end) {
        int columns = 0;
        for (int index = 0; index < end; index++) {
            final char character = line.charAt(index);
            if (character == '\t') {
                columns += 4 - columns % 4;
            } else if (character == ' ') {
                columns++;
            } else {
                break;
            }
        }
        return columns;
    }

    private static int columnAt(final String line, final int end) {
        int columns = 0;
        for (int index = 0; index < end; index++) {
            columns += line.charAt(index) == '\t' ? 4 - columns % 4 : 1;
        }
        return columns;
    }

    private static String cleanBulletTitle(final String rawTitle) {
        String title = cleanInlineMarkdown(rawTitle);
        if (title.codePointCount(0, title.length()) > MAX_BULLET_TITLE_CODE_POINTS) {
            final int end = title.offsetByCodePoints(0, MAX_BULLET_TITLE_CODE_POINTS - 1);
            title = title.substring(0, end).trim() + "…";
        }
        return title;
    }

    private static String cleanInlineMarkdown(final String text) {
        return text.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                .replaceAll("__(.*?)__", "$1")
                .replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "$1")
                .replaceAll("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", "$1")
                .replaceAll("~~(.*?)~~", "$1")
                .replaceAll("`+([^`]+)`+", "$1")
                .replace("**", "")
                .replace("__", "")
                .trim();
    }

    public static Section wholeNote(final String markdown) {
        final String content = markdown == null ? "" : markdown;
        return new Section(-1, "Whole Note", 1, "", 0, content.length(),
                content, content, -1, false);
    }

    private static final class MarkdownLine {
        private final int start;
        private final int contentEnd;
        private final int end;
        private final String rawText;
        private final String text;
        private final boolean inFence;
        private final boolean opensFence;
        private final boolean closesFence;

        private MarkdownLine(final int start, final int contentEnd, final int end,
                final String rawText, final String text,
                final boolean inFence, final boolean opensFence, final boolean closesFence) {
            this.start = start;
            this.contentEnd = contentEnd;
            this.end = end;
            this.rawText = rawText;
            this.text = text;
            this.inFence = inFence;
            this.opensFence = opensFence;
            this.closesFence = closesFence;
        }
    }

    private static final class HeadingStart {
        private final int start;
        private final int contentStart;
        private final int level;
        private final String title;
        private final String rawLine;
        private final int lineIndex;

        private HeadingStart(final int start, final int contentStart,
                final int level, final String title, final String rawLine,
                final int lineIndex) {
            this.start = start;
            this.contentStart = contentStart;
            this.level = level;
            this.title = title;
            this.rawLine = rawLine;
            this.lineIndex = lineIndex;
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
        private final boolean isBullet;
        private final String parentHeadingTitle;
        private List<Section> bulletItems = Collections.emptyList();

        private Section(final int index, final String title, final int level,
                final String rawHeadingLine, final int start, final int end,
                final String body, final String content, final int parentIndex,
                final boolean hasChildren) {
            this(index, title, level, rawHeadingLine, start, end, body, content,
                    parentIndex, hasChildren, false, "");
        }

        private Section(final int index, final String title, final int level,
                final String rawHeadingLine, final int start, final int end,
                final String body, final String content, final int parentIndex,
                final boolean hasChildren, final boolean isBullet,
                final String parentHeadingTitle) {
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
            this.isBullet = isBullet;
            this.parentHeadingTitle = parentHeadingTitle;
        }

        private void setBulletItems(final List<Section> bulletItems) {
            this.bulletItems = bulletItems;
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

        public boolean isBullet() {
            return isBullet;
        }

        public String getParentHeadingTitle() {
            return parentHeadingTitle;
        }

        public int getBulletCount() {
            return bulletItems.size();
        }

        public List<Section> getBulletItems() {
            return Collections.unmodifiableList(bulletItems);
        }
    }
}
