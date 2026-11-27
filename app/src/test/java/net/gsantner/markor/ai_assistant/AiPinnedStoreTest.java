package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class AiPinnedStoreTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void pinsFilesAndFoldersInNewestFirstOrderAndUnpinsThem() throws IOException {
        final File firstFile = temporaryFolder.newFile("first.md");
        final File folder = new File(temporaryFolder.getRoot(), "folder");
        assertThat(folder.mkdir()).isTrue();
        final AiPinnedStore store = new AiPinnedStore(new MemoryPersistence());

        store.pin(firstFile);
        store.pin(folder);

        assertThat(store.isPinned(firstFile)).isTrue();
        assertThat(store.isPinned(folder)).isTrue();
        assertThat(store.getPinnedFiles()).containsExactly(folder.getCanonicalFile(),
                firstFile.getCanonicalFile());

        store.unpin(firstFile);

        assertThat(store.isPinned(firstFile)).isFalse();
        assertThat(store.getPinnedFiles()).containsExactly(folder.getCanonicalFile());
    }

    @Test
    public void togglePinReturnsTheNewPinnedState() throws IOException {
        final File file = temporaryFolder.newFile("toggle.md");
        final AiPinnedStore store = new AiPinnedStore(new MemoryPersistence());

        assertThat(store.togglePin(file)).isTrue();
        assertThat(store.togglePin(file)).isFalse();

        assertThat(store.getPinnedFiles()).isEmpty();
    }

    @Test
    public void removesPathsThatNoLongerExistFromResultsAndStorage() throws IOException {
        final File file = temporaryFolder.newFile("removed.md");
        final MemoryPersistence persistence = new MemoryPersistence();
        final AiPinnedStore store = new AiPinnedStore(persistence);
        store.pin(file);
        assertThat(file.delete()).isTrue();

        assertThat(store.getPinnedFiles()).isEmpty();
        assertThat(store.getPinnedCount()).isZero();
        assertThat(persistence.paths).isEmpty();
    }

    @Test
    public void storesPinnedPathsRelativeToTheNotebookRoot() throws Exception {
        final File root = temporaryFolder.newFolder("notebook");
        final File folder = new File(root, "Polity");
        assertThat(folder.mkdir()).isTrue();
        final File file = new File(folder, "Part1.md");
        assertThat(file.createNewFile()).isTrue();
        final File jsonFile = new File(root, "ai_pinned.json");
        final AiPinnedStore store = new AiPinnedStore(root, new MemoryPersistence());

        store.pin(new File(root, "Polity/../Polity/Part1.md"));

        final JSONObject json = new JSONObject(readFile(jsonFile));
        assertThat(json.getInt("version")).isEqualTo(1);
        assertThat(json.getJSONArray("pinned").getString(0)).isEqualTo("Polity/Part1.md");
    }

    @Test
    public void resolvesTheSameRelativeEntriesUnderAnotherNotebookRoot() throws Exception {
        final File firstRoot = temporaryFolder.newFolder("first-device");
        final File secondRoot = temporaryFolder.newFolder("second-device");
        final File firstFolder = new File(firstRoot, "Modern_History");
        final File secondFolder = new File(secondRoot, "Modern_History");
        assertThat(firstFolder.mkdir()).isTrue();
        assertThat(secondFolder.mkdir()).isTrue();
        final AiPinnedStore firstStore = new AiPinnedStore(firstRoot, new MemoryPersistence());
        firstStore.pin(firstFolder);
        writeFile(new File(secondRoot, "ai_pinned.json"),
                readFile(new File(firstRoot, "ai_pinned.json")));
        final AiPinnedStore secondStore = new AiPinnedStore(secondRoot, new MemoryPersistence());

        assertThat(secondStore.getPinnedFiles()).containsExactly(secondFolder.getCanonicalFile());
    }

    @Test
    public void ignoresUnsafeRelativePathsFromJson() throws Exception {
        final File root = temporaryFolder.newFolder("unsafe-paths");
        final File validFile = new File(root, "valid.md");
        assertThat(validFile.createNewFile()).isTrue();
        final JSONArray pinned = new JSONArray();
        pinned.put("../secret.md");
        pinned.put("/absolute.md");
        pinned.put("safe/../../secret.md");
        pinned.put("valid.md");
        final JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("pinned", pinned);
        writeFile(new File(root, "ai_pinned.json"), json.toString());
        final AiPinnedStore store = new AiPinnedStore(root, new MemoryPersistence());

        assertThat(store.getPinnedFiles()).containsExactly(validFile.getCanonicalFile());
    }

    @Test
    public void unpinningRemovesTheRelativeJsonEntry() throws Exception {
        final File root = temporaryFolder.newFolder("unpin");
        final File file = new File(root, "Polity/Part1.md");
        assertThat(file.getParentFile().mkdir()).isTrue();
        assertThat(file.createNewFile()).isTrue();
        final AiPinnedStore store = new AiPinnedStore(root, new MemoryPersistence());
        store.pin(file);

        store.unpin(file);

        assertThat(new JSONObject(readFile(new File(root, "ai_pinned.json")))
                .getJSONArray("pinned").length()).isZero();
    }

    @Test
    public void migratesLegacyInRootPathsToRelativeJsonEntries() throws Exception {
        final File root = temporaryFolder.newFolder("migration");
        final File file = new File(root, "legacy.md");
        assertThat(file.createNewFile()).isTrue();
        final MemoryPersistence preferences = new MemoryPersistence();
        preferences.paths.add(file.getCanonicalPath());
        final AiPinnedStore store = new AiPinnedStore(root, preferences);

        assertThat(store.getPinnedFiles()).containsExactly(file.getCanonicalFile());
        assertThat(new JSONObject(readFile(new File(root, "ai_pinned.json")))
                .getJSONArray("pinned").getString(0)).isEqualTo("legacy.md");
        assertThat(preferences.paths).isEmpty();
    }

    @Test
    public void keepsPinnedPathsOutsideTheNotebookRootInFallbackStorage() throws Exception {
        final File root = temporaryFolder.newFolder("notebook-root");
        final File outsideFolder = temporaryFolder.newFolder("outside-root");
        final File file = new File(outsideFolder, "external.md");
        assertThat(file.createNewFile()).isTrue();
        final MemoryPersistence preferences = new MemoryPersistence();
        final AiPinnedStore store = new AiPinnedStore(root, preferences);

        store.pin(file);

        assertThat(preferences.paths).containsExactly(file.getCanonicalPath());
        assertThat(new JSONObject(readFile(new File(root, "ai_pinned.json")))
                .getJSONArray("pinned").length()).isZero();
    }

    private static String readFile(final File file) throws IOException {
        final Scanner scanner = new Scanner(file, "UTF-8").useDelimiter("\\A");
        try {
            return scanner.hasNext() ? scanner.next() : "";
        } finally {
            scanner.close();
        }
    }

    private static void writeFile(final File file, final String content) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static final class MemoryPersistence implements AiPinnedStore.PathPersistence {
        private List<String> paths = new ArrayList<>();

        @Override
        public List<String> read() {
            return new ArrayList<>(paths);
        }

        @Override
        public void write(final List<String> paths) {
            this.paths = new ArrayList<>(paths);
        }
    }
}
