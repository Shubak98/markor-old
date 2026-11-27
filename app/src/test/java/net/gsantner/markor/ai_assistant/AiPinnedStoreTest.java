package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
