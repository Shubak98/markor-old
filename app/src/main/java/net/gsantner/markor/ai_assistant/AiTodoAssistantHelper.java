package net.gsantner.markor.ai_assistant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiTodoAssistantHelper {
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern PRIORITY = Pattern.compile("(?:^|\\s)\\(([A-Z])\\)(?=\\s|$)");
    private static final Pattern DUE = Pattern.compile("(?:^|\\s)due:(\\d{4}-\\d{2}-\\d{2})(?=\\s|$)");
    private static final Pattern REF = Pattern.compile("(?:^|\\s)ref:([^\\s]+)(?=\\s|$)");
    private static final Pattern COMPLETION_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(?:\\s|$)");

    private AiTodoAssistantHelper() {
    }

    public static String readTodoFile(final File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("The todo.txt file is required.");
        }
        if (!file.exists()) {
            return "";
        }
        try (FileInputStream input = new FileInputStream(file);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    public static void writeTodoFile(final File file, final String content) throws IOException {
        if (file == null || content == null) {
            throw new IllegalArgumentException("A todo.txt file and content are required.");
        }
        final File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create the todo.txt directory: " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(Charset.forName("UTF-8")));
            output.getFD().sync();
        }
    }

    public static List<Task> parseTasks(final String todoText) {
        if (todoText == null || todoText.isEmpty()) {
            return Collections.emptyList();
        }
        final String[] lines = todoText.split("\n", -1);
        final List<Task> tasks = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            final String line = stripCarriageReturn(lines[index]);
            final Task task = parseTaskLine(line, index + 1);
            if (task != null) {
                tasks.add(task);
            }
        }
        return Collections.unmodifiableList(tasks);
    }

    public static List<Task> overdueTasks(final String todoText, final String today) {
        return tasksDueBefore(todoText, validatedDate(today));
    }

    public static List<Task> overdueTasks(final String todoText) {
        return overdueTasks(todoText, formatDate(Calendar.getInstance()));
    }

    public static List<Task> todayTasks(final String todoText, final String today) {
        final String validToday = validatedDate(today);
        final List<Task> matches = new ArrayList<>();
        for (Task task : parseTasks(todoText)) {
            if (!task.completed && validToday.equals(task.dueDate)) {
                matches.add(task);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static List<Task> todayTasks(final String todoText) {
        return todayTasks(todoText, formatDate(Calendar.getInstance()));
    }

    public static List<Task> upcomingTasks(final String todoText, final String today) {
        final String validToday = validatedDate(today);
        final List<Task> matches = new ArrayList<>();
        for (Task task : parseTasks(todoText)) {
            if (!task.completed && (task.dueDate == null || task.dueDate.compareTo(validToday) > 0)) {
                matches.add(task);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static List<Task> upcomingTasks(final String todoText) {
        return upcomingTasks(todoText, formatDate(Calendar.getInstance()));
    }

    public static String todayForPrompt() {
        final Calendar calendar = Calendar.getInstance();
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd, EEEE", Locale.ENGLISH);
        format.setTimeZone(calendar.getTimeZone());
        return format.format(calendar.getTime());
    }

    public static String buildStudyPlanPrompt(final String dateAndWeekday,
            final String overdueTasks, final String todayTasks, final String highPriorityTasks) {
        final String prompt = "Today is " + requireText(dateAndWeekday, "Today's date is required.")
                + ". Create a crisp, realistic daily study plan using these todo.txt tasks. "
                + "Prioritize overdue and due-today items, and include the listed priority (A) items. "
                + "Break work into achievable time blocks, include short breaks, and do not invent deadlines.\n\n"
                + "OVERDUE:\n" + emptySection(overdueTasks)
                + "\n\nDUE TODAY:\n" + emptySection(todayTasks)
                + "\n\nHIGH PRIORITY (A):\n" + emptySection(highPriorityTasks);
        return AiLanguagePromptHelper.ensureDefaultEnglishRule(prompt);
    }

    public static String buildEditPrompt(final String dateAndWeekday, final String instruction,
            final String linkedNotePath, final String linkedNoteContent) {
        final StringBuilder prompt = new StringBuilder()
                .append("Today is ").append(requireText(dateAndWeekday, "Today's date is required."))
                .append(". Edit the supplied todo.txt by returning ONLY a JSON object with an ")
                .append("\"operations\" array. Supported operations: ")
                .append("{\"op\":\"replace\",\"line\":1,\"task\":\"todo.txt line\"}, ")
                .append("{\"op\":\"delete\",\"line\":1}, and ")
                .append("{\"op\":\"add\",\"after\":1,\"task\":\"todo.txt line\"}. ")
                .append("Line numbers refer to the original input; add.after may be zero to insert ")
                .append("before the first line. Use valid todo.txt syntax, preserve ")
                .append("existing unrelated tasks verbatim, and never output Markdown fences. ")
                .append("When creating or editing a task for the linked note, include its exact ref tag.\n\n")
                .append("User request:\n").append(requireText(instruction,
                        "A task editing instruction is required."));
        appendLinkedNote(prompt, linkedNotePath, linkedNoteContent);
        return AiLanguagePromptHelper.ensureDefaultEnglishRule(prompt.toString());
    }

    public static String buildChatPrompt(final String dateAndWeekday, final String todoText,
            final String conversation, final String question,
            final String linkedNotePath, final String linkedNoteContent) {
        final StringBuilder prompt = new StringBuilder()
                .append("Today is ").append(requireText(dateAndWeekday, "Today's date is required."))
                .append(". Answer the user's task-planning question using the todo.txt below. ")
                .append("Do not propose or return edits to the file; this is a read-only chat.\n\n")
                .append("todo.txt:\n").append(emptySection(todoText))
                .append("\n\nConversation so far:\n").append(emptySection(conversation))
                .append("\n\nLatest question:\n").append(requireText(question, "A question is required."));
        appendLinkedNote(prompt, linkedNotePath, linkedNoteContent);
        return AiLanguagePromptHelper.ensureDefaultEnglishRule(prompt.toString());
    }

    public static String shiftOverdueToToday(final String todoText, final String today) {
        final String validToday = validatedDate(today);
        if (todoText == null || todoText.isEmpty()) {
            return todoText == null ? "" : todoText;
        }
        final String[] lines = todoText.split("\n", -1);
        for (Task task : overdueTasks(todoText, validToday)) {
            final int lineIndex = task.lineNumber - 1;
            final String carriageReturn = lines[lineIndex].endsWith("\r") ? "\r" : "";
            final String content = stripCarriageReturn(lines[lineIndex]);
            lines[lineIndex] = content.replaceFirst(
                    "(?<!\\S)due:" + Pattern.quote(task.dueDate) + "(?=\\s|$)",
                    Matcher.quoteReplacement("due:" + validToday)) + carriageReturn;
        }
        final StringBuilder shifted = new StringBuilder(todoText.length());
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                shifted.append('\n');
            }
            shifted.append(lines[index]);
        }
        return shifted.toString();
    }

    public static String applyPatch(final String todoText,
            final List<PatchOperation> operations) throws IOException {
        if (todoText == null || operations == null) {
            throw new IllegalArgumentException("todo.txt content and patch operations are required.");
        }
        if (operations.isEmpty()) {
            throw new IOException("The AI returned an empty todo.txt patch.");
        }

        final String[] split = todoText.split("\n", -1);
        final boolean trailingNewline = todoText.endsWith("\n");
        final int lineCount = todoText.isEmpty() ? 0 : split.length - (trailingNewline ? 1 : 0);
        final boolean crlf = todoText.contains("\r\n");
        final Map<Integer, String> replacements = new LinkedHashMap<>();
        final Set<Integer> deletions = new LinkedHashSet<>();
        final Map<Integer, List<String>> additions = new LinkedHashMap<>();
        final List<Task> originalTasks = parseTasks(todoText);
        final Map<Integer, Task> taskByLine = new LinkedHashMap<>();
        for (Task task : originalTasks) {
            taskByLine.put(task.lineNumber, task);
        }

        for (PatchOperation operation : operations) {
            if (operation == null || operation.action == null) {
                throw new IOException("A todo.txt patch operation is malformed.");
            }
            if (operation.action == PatchAction.ADD) {
                if (operation.after < 0 || operation.after > lineCount) {
                    throw new IOException("A todo.txt patch used an invalid insertion line.");
                }
                final String task = validatePatchTask(operation.task);
                List<String> tasksAtPosition = additions.get(operation.after);
                if (tasksAtPosition == null) {
                    tasksAtPosition = new ArrayList<>();
                    additions.put(operation.after, tasksAtPosition);
                }
                tasksAtPosition.add(task);
            } else {
                final int line = operation.line;
                final Task target = taskByLine.get(line);
                if (target == null || target.completed || line < 1 || line > lineCount
                        || replacements.containsKey(line) || deletions.contains(line)) {
                    throw new IOException("A todo.txt patch targeted a missing, completed, or "
                            + "already modified task.");
                }
                if (operation.action == PatchAction.DELETE) {
                    deletions.add(line);
                } else if (operation.action == PatchAction.REPLACE) {
                    replacements.put(line, validatePatchTask(operation.task));
                } else {
                    throw new IOException("Unsupported todo.txt patch operation.");
                }
            }
        }

        final List<String> outputLines = new ArrayList<>();
        addPatchLines(outputLines, additions.get(0), crlf);
        for (int line = 1; line <= lineCount; line++) {
            final String originalLine = split[line - 1];
            final String carriageReturn = originalLine.endsWith("\r") ? "\r" : "";
            if (!deletions.contains(line)) {
                final String replacement = replacements.get(line);
                outputLines.add((replacement == null
                        ? stripCarriageReturn(originalLine) : replacement) + carriageReturn);
            }
            addPatchLines(outputLines, additions.get(line), crlf);
        }
        if (outputLines.isEmpty()) {
            return "";
        }
        final StringBuilder updated = new StringBuilder();
        for (int index = 0; index < outputLines.size(); index++) {
            if (index > 0) {
                updated.append('\n');
            }
            updated.append(outputLines.get(index));
        }
        if (trailingNewline) {
            updated.append('\n');
        }
        return updated.toString();
    }

    public enum PatchAction {
        ADD,
        REPLACE,
        DELETE
    }

    public static final class PatchOperation {
        private final PatchAction action;
        private final int line;
        private final int after;
        private final String task;

        private PatchOperation(final PatchAction action, final int line,
                final int after, final String task) {
            this.action = action;
            this.line = line;
            this.after = after;
            this.task = task;
        }

        public static PatchOperation add(final int after, final String task) {
            return new PatchOperation(PatchAction.ADD, -1, after, task);
        }

        public static PatchOperation replace(final int line, final String task) {
            return new PatchOperation(PatchAction.REPLACE, line, -1, task);
        }

        public static PatchOperation delete(final int line) {
            return new PatchOperation(PatchAction.DELETE, line, -1, null);
        }
    }

    public static List<Task> overdueTasks(final String todoText, final Calendar today) {
        return overdueTasks(todoText, formatDate(today));
    }

    public static String formatDate(final Calendar calendar) {
        if (calendar == null) {
            throw new IllegalArgumentException("A local date is required.");
        }
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(calendar.getTimeZone());
        return format.format(calendar.getTime());
    }

    public static String relativeNotePath(final File notebookRoot, final File note) throws IOException {
        if (notebookRoot == null || note == null) {
            throw new IllegalArgumentException("A notebook root and note file are required.");
        }
        final File root = notebookRoot.getCanonicalFile();
        final File target = note.getCanonicalFile();
        if (!isMarkdownFile(target) || !isWithinRoot(root, target)) {
            throw new IOException("Only Markdown notes inside the Notebook can be linked.");
        }
        final String rootPath = root.getCanonicalPath();
        final String prefix = rootPath.endsWith(File.separator)
                ? rootPath : rootPath + File.separator;
        return encodeReferencePath(target.getCanonicalPath().substring(prefix.length())
                .replace(File.separatorChar, '/'));
    }

    public static boolean isSafeNoteReference(final File notebookRoot, final String refPath) {
        try {
            resolveNoteReference(notebookRoot, refPath, false);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    public static File resolveNoteReference(final File notebookRoot, final String refPath)
            throws IOException {
        return resolveNoteReference(notebookRoot, refPath, true);
    }

    private static File resolveNoteReference(final File notebookRoot, final String refPath,
            final boolean requireExisting) throws IOException {
        if (notebookRoot == null || !isSafeRelativeNotePath(refPath)) {
            throw new IllegalArgumentException("The linked note path is invalid.");
        }
        final File root = notebookRoot.getCanonicalFile();
        final File target = new File(root, decodeReferencePath(refPath)).getCanonicalFile();
        if (!isMarkdownFile(target) || !isWithinRoot(root, target)
                || requireExisting && (!target.isFile() || !target.exists())) {
            throw new IllegalArgumentException("The linked note must be an existing Markdown file "
                    + "inside the Notebook.");
        }
        return target;
    }

    public static List<File> listMarkdownNotes(final File notebookRoot,
            final List<File> pinnedFiles) throws IOException {
        if (notebookRoot == null) {
            throw new IllegalArgumentException("The Notebook root is required.");
        }
        final File root = notebookRoot.getCanonicalFile();
        final Map<String, File> notes = new LinkedHashMap<>();
        if (pinnedFiles != null) {
            for (File pinned : pinnedFiles) {
                if (pinned != null && pinned.isFile() && isMarkdownFile(pinned)) {
                    final File canonical = pinned.getCanonicalFile();
                    if (isWithinRoot(root, canonical)) {
                        notes.put(canonical.getCanonicalPath(), canonical);
                    }
                }
            }
        }
        collectMarkdownNotes(root, root, notes, new LinkedHashSet<>());
        final List<File> result = new ArrayList<>(notes.values());
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(final File first, final File second) {
                return String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName());
            }
        });
        return Collections.unmodifiableList(result);
    }

    private static void collectMarkdownNotes(final File root, final File directory,
            final Map<String, File> notes, final Set<String> visited) throws IOException {
        final String canonicalPath = directory.getCanonicalPath();
        if (!isWithinRoot(root, directory) || !visited.add(canonicalPath)) {
            return;
        }
        final File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Could not list Markdown notes in " + directory.getAbsolutePath());
        }
        for (File child : children) {
            final File canonical = child.getCanonicalFile();
            if (!isWithinRoot(root, canonical)) {
                continue;
            }
            if (canonical.isDirectory()) {
                if (!canonical.getName().startsWith(".")) {
                    collectMarkdownNotes(root, canonical, notes, visited);
                }
            } else if (canonical.isFile() && isMarkdownFile(canonical)) {
                notes.put(canonical.getCanonicalPath(), canonical);
            }
        }
    }

    private static boolean isWithinRoot(final File root, final File target) throws IOException {
        final String rootPath = root.getCanonicalPath();
        final String targetPath = target.getCanonicalPath();
        final String prefix = rootPath.endsWith(File.separator)
                ? rootPath : rootPath + File.separator;
        return targetPath.startsWith(prefix);
    }

    private static boolean isMarkdownFile(final File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static List<Task> tasksDueBefore(final String todoText, final String today) {
        final String validToday = validatedDate(today);
        final List<Task> matches = new ArrayList<>();
        for (Task task : parseTasks(todoText)) {
            if (!task.completed && task.dueDate != null && task.dueDate.compareTo(validToday) < 0) {
                matches.add(task);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    private static Task parseTaskLine(final String line, final int lineNumber) {
        if (line.trim().isEmpty() || line.trim().startsWith("#")) {
            return null;
        }
        final boolean completed = line.startsWith("x ");
        String metadata = completed ? line.substring(2) : line;
        if (completed) {
            final Matcher completionDate = COMPLETION_DATE.matcher(metadata);
            if (completionDate.find()) {
                metadata = metadata.substring(completionDate.end()).trim();
            }
        }
        final Matcher priorityMatcher = PRIORITY.matcher(metadata);
        final Character priority = priorityMatcher.find()
                ? priorityMatcher.group(1).charAt(0) : null;
        final Matcher dueMatcher = DUE.matcher(metadata);
        final String dueDate = dueMatcher.find() && isValidDate(dueMatcher.group(1))
                ? dueMatcher.group(1) : null;
        final Matcher refMatcher = REF.matcher(metadata);
        final String refPath = refMatcher.find() ? refMatcher.group(1) : null;
        final String title = metadata.replaceAll("\\([A-Z]\\)", " ")
                .replaceAll("(?<!\\S)due:\\S+", " ")
                .replaceAll("(?<!\\S)ref:\\S+", " ")
                .replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) {
            return null;
        }
        return new Task(line, lineNumber, completed, priority, dueDate, refPath, title);
    }

    private static String validatedDate(final String date) {
        if (date == null || !DATE.matcher(date).matches() || !isValidDate(date)) {
            throw new IllegalArgumentException("Date must use the valid YYYY-MM-DD format.");
        }
        return date;
    }

    private static boolean isValidDate(final String date) {
        if (date == null || !DATE.matcher(date).matches()) {
            return false;
        }
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        final ParsePosition position = new ParsePosition(0);
        final java.util.Date parsed = format.parse(date, position);
        return parsed != null && position.getIndex() == date.length();
    }

    private static String validatePatchTask(final String taskLine) throws IOException {
        if (taskLine == null || taskLine.trim().isEmpty()
                || taskLine.indexOf('\n') >= 0 || taskLine.indexOf('\r') >= 0) {
            throw new IOException("A patch task must be a single non-empty todo.txt line.");
        }
        final Task task = parseTaskLine(taskLine, 1);
        if (task == null) {
            throw new IOException("A patch task must be a valid todo.txt line.");
        }
        if (task.refPath != null && !isSafeRelativeNotePath(task.refPath)) {
            throw new IOException("A task patch contained an unsafe linked note path.");
        }
        return taskLine;
    }

    private static boolean isSafeRelativeNotePath(final String refPath) {
        if (refPath == null || refPath.trim().isEmpty()
                || refPath.startsWith("/")) {
            return false;
        }
        final String[] segments = refPath.split("/", -1);
        for (String segment : segments) {
            final String decoded = decodeReferenceSegment(segment);
            if (decoded.isEmpty() || ".".equals(decoded) || "..".equals(decoded)
                    || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
                    || decoded.indexOf(':') >= 0) {
                return false;
            }
        }
        return isMarkdownFile(new File(decodeReferenceSegment(segments[segments.length - 1])));
    }

    private static String encodeReferencePath(final String path) throws IOException {
        final String[] segments = path.split("/", -1);
        final StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            try {
                encoded.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException exception) {
                throw new IOException("UTF-8 encoding is unavailable.", exception);
            }
        }
        return encoded.toString();
    }

    private static String decodeReferencePath(final String path) {
        final String[] segments = path.split("/", -1);
        final StringBuilder decoded = new StringBuilder();
        for (String segment : segments) {
            if (decoded.length() > 0) {
                decoded.append(File.separatorChar);
            }
            decoded.append(decodeReferenceSegment(segment));
        }
        return decoded.toString();
    }

    private static String decodeReferenceSegment(final String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException("The linked note path is invalid.");
        }
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalArgumentException("UTF-8 decoding is unavailable.", exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The linked note path is invalid.", exception);
        }
    }

    private static void addPatchLines(final List<String> output,
            final List<String> additions, final boolean crlf) {
        if (additions == null) {
            return;
        }
        for (String addition : additions) {
            output.add(addition + (crlf ? "\r" : ""));
        }
    }

    private static String stripCarriageReturn(final String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String requireText(final String text, final String message) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return text.trim();
    }

    private static String emptySection(final String text) {
        return text == null || text.trim().isEmpty() ? "(none)" : text.trim();
    }

    private static void appendLinkedNote(final StringBuilder prompt, final String path,
            final String content) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        prompt.append("\n\nLINKED NOTE ref:").append(path);
        if (content != null && !content.trim().isEmpty()) {
            prompt.append("\nLinked note content:\n").append(content);
        }
    }

    public static final class Task {
        private final String rawLine;
        private final int lineNumber;
        private final boolean completed;
        private final Character priority;
        private final String dueDate;
        private final String refPath;
        private final String title;

        private Task(final String rawLine, final int lineNumber, final boolean completed,
                final Character priority, final String dueDate, final String refPath,
                final String title) {
            this.rawLine = rawLine;
            this.lineNumber = lineNumber;
            this.completed = completed;
            this.priority = priority;
            this.dueDate = dueDate;
            this.refPath = refPath;
            this.title = title;
        }

        public String getRawLine() {
            return rawLine;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public boolean isCompleted() {
            return completed;
        }

        public Character getPriority() {
            return priority;
        }

        public String getDueDate() {
            return dueDate;
        }

        public String getRefPath() {
            return refPath;
        }

        public String getTitle() {
            return title;
        }
    }
}
