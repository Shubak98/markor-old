package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.content.SharedPreferences;

import net.gsantner.markor.ApplicationObject;
import net.gsantner.markor.model.AppSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiPinnedStore {
    public static final String PREFERENCES_NAME = "markor_ai_assistant_prefs";
    public static final String PINNED_PATHS_KEY = "pinned_paths";
    public static final String VIRTUAL_FOLDER_NAME =
            "__MARKOR_AI_ASSISTANT_PINNED_VIRTUAL_FOLDER__";
    private static final String PINNED_FILE_NAME = "ai_pinned.json";
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
        final Context appContext = context.getApplicationContext();
        final SharedPreferences preferences = appContext.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        final PathPersistence preferencePersistence = new PathPersistence() {
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
        persistence = new JsonPathPersistence(resolveNotebookRoot(appContext),
                preferencePersistence);
    }

    AiPinnedStore(final PathPersistence persistence) {
        if (persistence == null) {
            throw new IllegalArgumentException("Pinned path storage is required.");
        }
        this.persistence = persistence;
    }

    AiPinnedStore(final File notebookRoot, final PathPersistence fallbackPersistence) {
        if (notebookRoot == null || fallbackPersistence == null) {
            throw new IllegalArgumentException("Notebook root and fallback storage are required.");
        }
        persistence = new JsonPathPersistence(canonicalFile(notebookRoot), fallbackPersistence);
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

    private static File resolveNotebookRoot(final Context context) {
        final String configuredSyncRoot = new AiSettingsStore(context).getGitHubLocalRoot();
        if (!configuredSyncRoot.isEmpty()) {
            final File syncRoot = new File(configuredSyncRoot);
            if (syncRoot.isDirectory()) {
                return canonicalFile(syncRoot);
            }
        }
        final AppSettings appSettings = ApplicationObject.settings();
        if (appSettings == null) {
            throw new IllegalStateException("The notebook root is not available.");
        }
        return canonicalFile(appSettings.getNotebookDirectory());
    }

    private static File canonicalFile(final File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not resolve the notebook root.", exception);
        }
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

    private static final class JsonPathPersistence implements PathPersistence {
        private final File notebookRoot;
        private final File pinnedFile;
        private final PathPersistence fallbackPersistence;

        private JsonPathPersistence(final File notebookRoot,
                final PathPersistence fallbackPersistence) {
            this.notebookRoot = notebookRoot;
            pinnedFile = new File(notebookRoot, PINNED_FILE_NAME);
            this.fallbackPersistence = fallbackPersistence;
        }

        @Override
        public List<String> read() {
            final List<String> paths = new ArrayList<>();
            final List<String> fallbackPaths = fallbackPersistence.read();
            final List<String> outsidePaths = new ArrayList<>();
            if (!pinnedFile.exists()) {
                for (String path : fallbackPaths) {
                    if (isInsideRoot(path)) {
                        addUnique(paths, path);
                    } else {
                        addUnique(outsidePaths, path);
                    }
                }
                if (!paths.isEmpty()) {
                    paths.addAll(outsidePaths);
                    write(paths);
                } else {
                    fallbackPersistence.write(outsidePaths);
                    paths.addAll(outsidePaths);
                }
                return paths;
            }

            readJsonPaths(paths);
            for (String path : fallbackPaths) {
                if (!isInsideRoot(path)) {
                    addUnique(paths, path);
                    addUnique(outsidePaths, path);
                }
            }
            if (!fallbackPaths.equals(outsidePaths)) {
                fallbackPersistence.write(outsidePaths);
            }
            return paths;
        }

        @Override
        public void write(final List<String> paths) {
            final List<String> relativePaths = new ArrayList<>();
            final List<String> outsidePaths = new ArrayList<>();
            for (String path : paths) {
                if (isInsideRoot(path)) {
                    addUnique(relativePaths, relativePath(path));
                } else {
                    addUnique(outsidePaths, path);
                }
            }
            writeJsonPaths(relativePaths);
            fallbackPersistence.write(outsidePaths);
        }

        private void readJsonPaths(final List<String> paths) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(pinnedFile), StandardCharsets.UTF_8))) {
                final StringBuilder content = new StringBuilder();
                final char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, count);
                }
                final JSONObject json = new JSONObject(content.toString());
                if (json.getInt("version") != 1) {
                    throw new IllegalStateException("Unsupported pinned paths file version.");
                }
                final JSONArray pinned = json.getJSONArray("pinned");
                for (int index = 0; index < pinned.length(); index++) {
                    final Object value = pinned.get(index);
                    if (value instanceof String) {
                        final String path = resolveRelativePath((String) value);
                        if (path != null) {
                            addUnique(paths, path);
                        }
                    }
                }
            } catch (IOException | JSONException exception) {
                throw new IllegalStateException("Could not read pinned paths file.", exception);
            }
        }

        private void writeJsonPaths(final List<String> relativePaths) {
            if (!notebookRoot.isDirectory() && !notebookRoot.mkdirs()
                    && !notebookRoot.isDirectory()) {
                throw new IllegalStateException("Could not create the notebook root.");
            }
            final JSONArray pinned = new JSONArray();
            for (String path : relativePaths) {
                pinned.put(path);
            }
            final JSONObject json = new JSONObject();
            try {
                json.put("version", 1);
                json.put("pinned", pinned);
            } catch (JSONException exception) {
                throw new IllegalStateException("Could not serialize pinned paths.", exception);
            }

            final File temporaryFile = new File(notebookRoot, PINNED_FILE_NAME + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (IOException | JSONException exception) {
                if (temporaryFile.exists() && !temporaryFile.delete()) {
                    exception.addSuppressed(new IOException("Could not remove temporary pinned file."));
                }
                throw new IllegalStateException("Could not write pinned paths file.", exception);
            }
            if (!temporaryFile.renameTo(pinnedFile)) {
                if (!temporaryFile.delete()) {
                    throw new IllegalStateException(
                            "Could not replace pinned paths file or clean up its temporary file.");
                }
                throw new IllegalStateException("Could not replace pinned paths file.");
            }
        }

        private String relativePath(final String canonicalPath) {
            final String rootPath = notebookRoot.getPath();
            if (canonicalPath.equals(rootPath)) {
                return ".";
            }
            final String rootPrefix = rootPath.endsWith(File.separator)
                    ? rootPath : rootPath + File.separator;
            final String relative = canonicalPath.substring(rootPrefix.length());
            return relative.replace(File.separatorChar, '/');
        }

        private String resolveRelativePath(final String path) {
            if (path == null || path.isEmpty() || path.indexOf('\0') >= 0
                    || path.startsWith("/") || path.startsWith("\\")
                    || path.matches("^[A-Za-z]:.*")) {
                return null;
            }
            final String normalized = path.replace('\\', '/');
            final String[] components = normalized.split("/", -1);
            for (String component : components) {
                if ("..".equals(component) || component.isEmpty()) {
                    return null;
                }
            }
            try {
                final File resolved = new File(notebookRoot, normalized).getCanonicalFile();
                return isWithinRoot(notebookRoot, resolved) ? resolved.getPath() : null;
            } catch (IOException exception) {
                return null;
            }
        }

        private boolean isInsideRoot(final String path) {
            if (path == null || path.isEmpty()) {
                return false;
            }
            try {
                return isWithinRoot(notebookRoot, new File(path).getCanonicalFile());
            } catch (IOException exception) {
                return false;
            }
        }

        private static boolean isWithinRoot(final File root, final File path) {
            final String rootPath = root.getPath();
            final String pathValue = path.getPath();
            if (pathValue.equals(rootPath)) {
                return true;
            }
            final String rootPrefix = rootPath.endsWith(File.separator)
                    ? rootPath : rootPath + File.separator;
            return pathValue.startsWith(rootPrefix);
        }

        private static void addUnique(final List<String> paths, final String path) {
            if (path != null && !paths.contains(path)) {
                paths.add(path);
            }
        }
    }
}
