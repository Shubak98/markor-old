package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiPinnedStore {
    public static final String PREFERENCES_NAME = "markor_ai_assistant_prefs";
    public static final String PINNED_PATHS_KEY = "pinned_paths";
    public static final String VIRTUAL_FOLDER_NAME =
            "__MARKOR_AI_ASSISTANT_PINNED_VIRTUAL_FOLDER__";
    private static final Object PERSISTENCE_LOCK = new Object();

    interface PathPersistence {
        List<String> read();

        void write(List<String> paths);
    }

    private final PathPersistence persistence;

    public AiPinnedStore(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("A context is required.");
        }
        final SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        persistence = new PathPersistence() {
            @Override
            public List<String> read() {
                final String serialized = preferences.getString(PINNED_PATHS_KEY, null);
                if (serialized == null || serialized.isEmpty()) {
                    return new ArrayList<>();
                }
                try {
                    final JSONArray array = new JSONArray(serialized);
                    final List<String> paths = new ArrayList<>(array.length());
                    for (int index = 0; index < array.length(); index++) {
                        paths.add(array.getString(index));
                    }
                    return paths;
                } catch (JSONException exception) {
                    throw new IllegalStateException("Stored pinned paths are invalid.", exception);
                }
            }

            @Override
            public void write(final List<String> paths) {
                final JSONArray array = new JSONArray();
                for (String path : paths) {
                    array.put(path);
                }
                preferences.edit().putString(PINNED_PATHS_KEY, array.toString()).apply();
            }
        };
    }

    AiPinnedStore(final PathPersistence persistence) {
        if (persistence == null) {
            throw new IllegalArgumentException("Pinned path storage is required.");
        }
        this.persistence = persistence;
    }

    public boolean isPinned(final File file) {
        final String path = canonicalPath(file);
        synchronized (PERSISTENCE_LOCK) {
            return cleanStoredPaths().contains(path);
        }
    }

    public boolean togglePin(final File file) {
        final String path = canonicalPath(file);
        synchronized (PERSISTENCE_LOCK) {
            final List<String> paths = cleanStoredPaths();
            if (paths.remove(path)) {
                persistence.write(paths);
                return false;
            }
            paths.add(0, path);
            persistence.write(paths);
            return true;
        }
    }

    public void pin(final File file) {
        final String path = canonicalPath(file);
        synchronized (PERSISTENCE_LOCK) {
            final List<String> paths = cleanStoredPaths();
            if (!paths.contains(path)) {
                paths.add(0, path);
                persistence.write(paths);
            }
        }
    }

    public void unpin(final File file) {
        final String path = canonicalPath(file);
        synchronized (PERSISTENCE_LOCK) {
            final List<String> paths = cleanStoredPaths();
            if (paths.remove(path)) {
                persistence.write(paths);
            }
        }
    }

    public List<File> getPinnedFiles() {
        synchronized (PERSISTENCE_LOCK) {
            final List<File> files = new ArrayList<>();
            final List<String> validPaths = cleanStoredPaths();
            for (String path : validPaths) {
                files.add(new File(path));
            }
            return Collections.unmodifiableList(files);
        }
    }

    public int getPinnedCount() {
        synchronized (PERSISTENCE_LOCK) {
            return cleanStoredPaths().size();
        }
    }

    public static File getPinnedVirtualFolder(final File notebookRoot) {
        if (notebookRoot == null) {
            throw new IllegalArgumentException("The notebook root is required.");
        }
        return new File(notebookRoot, VIRTUAL_FOLDER_NAME);
    }

    public static boolean isPinnedVirtualFolder(final File directory) {
        return directory != null && VIRTUAL_FOLDER_NAME.equals(directory.getName())
                && !directory.exists();
    }

    public static boolean isPinnedVirtualFolder(final File directory, final File notebookRoot) {
        return directory != null && notebookRoot != null
                && getPinnedVirtualFolder(notebookRoot).equals(directory) && !directory.exists();
    }

    private List<String> cleanStoredPaths() {
        final List<String> storedPaths = persistence.read();
        final List<String> validPaths = new ArrayList<>();
        for (String path : storedPaths) {
            if (path != null && !path.isEmpty() && new File(path).exists()
                    && !validPaths.contains(path)) {
                validPaths.add(path);
            }
        }
        if (!storedPaths.equals(validPaths)) {
            persistence.write(validPaths);
        }
        return validPaths;
    }

    private static String canonicalPath(final File file) {
        if (file == null) {
            throw new IllegalArgumentException("A file or folder is required.");
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not resolve the file or folder path.", exception);
        }
    }
}
