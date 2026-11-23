package net.gsantner.markor.ai_assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class AiMnemonicsStore {
    private final File vaultFile;

    public AiMnemonicsStore(final Context context) {
        this(new File(context.getFilesDir(), "ai_mnemonics_vault.json"));
    }

    AiMnemonicsStore(final File vaultFile) {
        this.vaultFile = vaultFile;
    }

    public synchronized Entry saveMnemonic(final String filePath, final String fileName,
            final String headingTitle, final String rawHeadingLine,
            final String mnemonicTitle, final String mnemonicBody,
            final boolean addedToNote) throws IOException {
        final long timestampMs = System.currentTimeMillis();
        final Entry entry = new Entry(UUID.randomUUID().toString(), timestampMs,
                filePath, fileName, headingTitle, rawHeadingLine, mnemonicTitle,
                mnemonicBody, addedToNote);
        final List<Entry> entries = readEntries();
        entries.add(0, entry);
        writeEntries(entries);
        return entry;
    }

    public synchronized List<Entry> getMnemonicsForFile(final String filePath) throws IOException {
        final List<Entry> filtered = new ArrayList<>();
        for (Entry entry : readEntries()) {
            if (filePath != null && filePath.equals(entry.filePath)) {
                filtered.add(entry);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    public synchronized List<Entry> getAllMnemonics() throws IOException {
        return Collections.unmodifiableList(readEntries());
    }

    public synchronized void deleteMnemonic(final String id) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IOException("The mnemonic to delete was not identified.");
        }
        final List<Entry> entries = readEntries();
        boolean removed = false;
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (id.equals(entries.get(index).id)) {
                entries.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            throw new IOException("This mnemonic is no longer available.");
        }
        writeEntries(entries);
    }

    private List<Entry> readEntries() throws IOException {
        if (!vaultFile.isFile()) {
            return new ArrayList<>();
        }
        try (FileInputStream input = new FileInputStream(vaultFile)) {
            final byte[] bytes = new byte[(int) vaultFile.length()];
            final int read = input.read(bytes);
            if (read < 0) {
                return new ArrayList<>();
            }
            final JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            final JSONArray array = root.optJSONArray("mnemonics");
            final List<Entry> entries = new ArrayList<>();
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    final JSONObject item = array.optJSONObject(index);
                    if (item != null) {
                        entries.add(Entry.fromJson(item));
                    }
                }
            }
            Collections.sort(entries, (left, right) ->
                    Long.compare(right.timestampMs, left.timestampMs));
            return entries;
        } catch (JSONException exception) {
            throw new IOException("Could not read the Mnemonics Vault: " + exception.getMessage());
        }
    }

    private void writeEntries(final List<Entry> entries) throws IOException {
        final File parent = vaultFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not prepare the Mnemonics Vault.");
        }
        final JSONObject root = new JSONObject();
        final JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(entry.toJson());
            }
            root.put("mnemonics", array);
        } catch (JSONException exception) {
            throw new IOException("Could not encode the Mnemonics Vault.");
        }
        try (FileOutputStream output = new FileOutputStream(vaultFile)) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class Entry {
        private final String id;
        private final long timestampMs;
        private final String filePath;
        private final String fileName;
        private final String headingTitle;
        private final String rawHeadingLine;
        private final String mnemonicTitle;
        private final String mnemonicBody;
        private final boolean addedToNote;

        public Entry(final String id, final long timestampMs, final String filePath,
                final String fileName, final String headingTitle, final String rawHeadingLine,
                final String mnemonicTitle, final String mnemonicBody, final boolean addedToNote) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.filePath = filePath;
            this.fileName = fileName;
            this.headingTitle = headingTitle;
            this.rawHeadingLine = rawHeadingLine;
            this.mnemonicTitle = mnemonicTitle;
            this.mnemonicBody = mnemonicBody;
            this.addedToNote = addedToNote;
        }

        public String getId() { return id; }
        public long getTimestampMs() { return timestampMs; }
        public String getFilePath() { return filePath; }
        public String getFileName() { return fileName; }
        public String getHeadingTitle() { return headingTitle; }
        public String getRawHeadingLine() { return rawHeadingLine; }
        public String getMnemonicTitle() { return mnemonicTitle; }
        public String getMnemonicBody() { return mnemonicBody; }
        public boolean isAddedToNote() { return addedToNote; }

        private JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("timestampMs", timestampMs)
                    .put("filePath", filePath)
                    .put("fileName", fileName)
                    .put("headingTitle", headingTitle)
                    .put("rawHeadingLine", rawHeadingLine)
                    .put("mnemonicTitle", mnemonicTitle)
                    .put("mnemonicBody", mnemonicBody)
                    .put("addedToNote", addedToNote);
        }

        private static Entry fromJson(final JSONObject item) {
            return new Entry(
                    item.optString("id"),
                    item.optLong("timestampMs"),
                    item.optString("filePath"),
                    item.optString("fileName"),
                    item.optString("headingTitle"),
                    item.optString("rawHeadingLine"),
                    item.optString("mnemonicTitle"),
                    item.optString("mnemonicBody"),
                    item.optBoolean("addedToNote"));
        }
    }
}
