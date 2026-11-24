package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class GitHubNotesSyncHelper {
    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 180_000;
    private static final long MAX_API_RESPONSE_BYTES = 75L * 1024L * 1024L;
    private static final String API_ROOT = "https://api.github.com";
    private static final String STATE_FILE_NAME = "ai_github_sync_state.json";
    private static final Object SYNC_LOCK = new Object();

    public enum SyncMode {
        TWO_WAY,
        PUSH_ONLY,
        PULL_ONLY
    }

    public enum SyncAction {
        SKIP,
        PUSH,
        PULL,
        CONFLICT,
        DELETE_REMOTE,
        DELETE_LOCAL
    }

    public interface ProgressCallback {
        void onProgress(String message);
    }

    private final Context context;
    private final AiSettingsStore settingsStore;
    private final File currentFile;

    public GitHubNotesSyncHelper(final Context context) {
        this(context, null);
    }

    public GitHubNotesSyncHelper(final Context context, final File currentFile) {
        if (context == null) {
            throw new IllegalArgumentException("Application context is required for GitHub sync.");
        }
        this.context = context.getApplicationContext();
        this.settingsStore = new AiSettingsStore(this.context);
        this.currentFile = currentFile;
    }

    public static String computeGitBlobSha(final byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Git blob data cannot be null.");
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            final byte[] header = ("blob " + data.length + '\0').getBytes(StandardCharsets.UTF_8);
            digest.update(header);
            digest.update(data);
            final byte[] hash = digest.digest();
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable for Git blob hashing.", exception);
        }
    }

    public static SyncAction decideSyncAction(final String localSha, final String remoteSha,
            final String lastSyncedSha) {
        if (same(localSha, remoteSha)) {
            return SyncAction.SKIP;
        }
        if (localSha == null) {
            if (remoteSha == null) {
                return SyncAction.SKIP;
            }
            return lastSyncedSha != null && remoteSha.equals(lastSyncedSha)
                    ? SyncAction.DELETE_REMOTE : SyncAction.PULL;
        }
        if (remoteSha == null) {
            return lastSyncedSha != null && localSha.equals(lastSyncedSha)
                    ? SyncAction.DELETE_LOCAL : SyncAction.PUSH;
        }
        if (lastSyncedSha == null) {
            return SyncAction.CONFLICT;
        }
        final boolean localChanged = !localSha.equals(lastSyncedSha);
        final boolean remoteChanged = !remoteSha.equals(lastSyncedSha);
        if (!localChanged && !remoteChanged) {
            return SyncAction.SKIP;
        }
        if (localChanged && !remoteChanged) {
            return SyncAction.PUSH;
        }
        if (!localChanged) {
            return SyncAction.PULL;
        }
        return SyncAction.CONFLICT;
    }

    public void testConnection(final SyncConfig config) throws IOException {
        validateConfig(config);
        requestJson("GET", API_ROOT + "/repos/" + config.repo, null, config);
    }

    public SyncReport syncFolder(final File localRoot, final SyncConfig config,
            final SyncMode mode, final ProgressCallback callback) throws IOException {
        final SyncPlan plan = planSync(localRoot, config, mode, callback);
        return executeSync(plan, true, callback);
    }

    public SyncPlan planSync(final File localRoot, final SyncConfig config,
            final SyncMode mode, final ProgressCallback callback) throws IOException {
        synchronized (SYNC_LOCK) {
            return createSyncPlan(localRoot, config, mode, callback);
        }
    }

    public SyncReport executeSync(final SyncPlan plan, final boolean applyDeletions,
            final ProgressCallback callback) throws IOException {
        synchronized (SYNC_LOCK) {
            return performSync(plan, applyDeletions, callback);
        }
    }

    private SyncPlan createSyncPlan(final File localRoot, final SyncConfig config,
            final SyncMode mode, final ProgressCallback callback) throws IOException {
        validateConfig(config);
        if (mode == null) {
            throw new IOException("Choose a GitHub sync mode.");
        }
        if (localRoot == null || !localRoot.isDirectory()) {
            throw new IOException("The local sync folder is not available.");
        }
        final File canonicalRoot = localRoot.getCanonicalFile();
        final List<String> events = new ArrayList<>();
        final SyncState state = readState(config, canonicalRoot);
        final String remoteHeadSha = mode == SyncMode.TWO_WAY
                ? fetchHeadCommit(config) : null;
        final ScanResult scan = scanLocal(canonicalRoot, state);
        if (mode == SyncMode.TWO_WAY
                && isFastNoOp(remoteHeadSha, state.lastSyncedCommitSha,
                        hasLocalChanges(scan, state))) {
            addEvent(events, callback, "Already up to date (0 changes)");
            return new SyncPlan(canonicalRoot, config, mode,
                    cachedRemoteTree(state, remoteHeadSha), new ArrayList<PlannedAction>(),
                    state, events, new HashSet<String>(), 0, 0, scan.files, true);
        }
        if (mode == SyncMode.TWO_WAY && remoteHeadSha != null
                && remoteHeadSha.equals(state.lastSyncedCommitSha)) {
            if (state.lastSyncedTreeSha.matches("[0-9a-f]{40}")) {
                final TreeSnapshot remoteTree = cachedRemoteTree(state, remoteHeadSha);
                return createPlanFromSnapshots(canonicalRoot, config, mode, callback,
                        remoteTree, state, scan);
            }
        }

        notifyProgress(callback, "Fetching GitHub repository tree...");
        final Set<String> oversizedRemotePaths = new HashSet<>();
        final TreeSnapshot fetchedTree = fetchTree(config, events, callback, oversizedRemotePaths);
        final TreeSnapshot remoteTree = new TreeSnapshot(fetchedTree.files, fetchedTree.treeSha,
                fetchedTree.branchExists, remoteHeadSha);
        return createPlanFromSnapshots(canonicalRoot, config, mode, callback,
                remoteTree, state, scan, events, oversizedRemotePaths);
    }

    private SyncPlan createPlanFromSnapshots(final File canonicalRoot, final SyncConfig config,
            final SyncMode mode, final ProgressCallback callback, final TreeSnapshot remoteTree,
            final SyncState state, final ScanResult scan) throws IOException {
        return createPlanFromSnapshots(canonicalRoot, config, mode, callback, remoteTree,
                state, scan, new ArrayList<String>(), new HashSet<String>());
    }

    private SyncPlan createPlanFromSnapshots(final File canonicalRoot, final SyncConfig config,
            final SyncMode mode, final ProgressCallback callback, final TreeSnapshot remoteTree,
            final SyncState state, final ScanResult scan, final List<String> events,
            final Set<String> oversizedRemotePaths) throws IOException {
        final Map<String, RemoteFile> remoteFiles = remoteTree.files;
        for (String message : scan.messages) {
            addEvent(events, callback, message);
        }

        final Set<String> allPaths = new HashSet<>();
        allPaths.addAll(scan.files.keySet());
        allPaths.addAll(remoteFiles.keySet());
        final List<String> orderedPaths = new ArrayList<>(allPaths);
        Collections.sort(orderedPaths);
        final Set<String> oversizedPaths = new HashSet<>(scan.oversizedPaths);
        oversizedPaths.addAll(oversizedRemotePaths);
        int skipped = oversizedPaths.size();
        int unchangedSkipped = 0;
        int modeSkipped = 0;
        int conflicts = 0;
        int completed = 0;
        final List<PlannedAction> actions = new ArrayList<>();

        for (String path : orderedPaths) {
            completed++;
            final LocalFile local = scan.files.get(path);
            final RemoteFile remote = remoteFiles.get(path);
            if (oversizedPaths.contains(path)) {
                notifyComparedProgress(callback, completed, orderedPaths.size());
                continue;
            }
            final StateFile stateFile = state.files.get(path);
            final String previousSha = stateFile == null ? null : stateFile.lastSyncedSha;
            SyncAction action = decideSyncAction(
                    local == null ? null : local.sha, remote == null ? null : remote.sha, previousSha);
            if (mode == SyncMode.PUSH_ONLY && action == SyncAction.DELETE_REMOTE) {
                action = SyncAction.SKIP;
            } else if (mode == SyncMode.PULL_ONLY && action == SyncAction.DELETE_LOCAL) {
                action = SyncAction.SKIP;
            } else if (mode == SyncMode.PUSH_ONLY && action == SyncAction.DELETE_LOCAL) {
                action = SyncAction.PUSH;
            } else if (mode == SyncMode.PULL_ONLY && action == SyncAction.DELETE_REMOTE) {
                action = SyncAction.PULL;
            }
            if (action == SyncAction.SKIP) {
                if (local != null && remote != null) {
                    setLastSynced(state, path, local);
                } else if (local == null && remote == null) {
                    state.files.remove(path);
                }
                skipped++;
                unchangedSkipped++;
            } else if (action == SyncAction.PUSH) {
                if (mode == SyncMode.PULL_ONLY) {
                    skipped++;
                    modeSkipped++;
                } else {
                    final String message = local == null ? null
                            : remote == null && previousSha != null
                                    ? "⚠ Kept & re-pushed (local edits): " + path : null;
                    if (message != null) {
                        addEvent(events, callback, message);
                    }
                    actions.add(new PlannedAction(path, action, local, remote));
                }
            } else if (action == SyncAction.PULL) {
                if (mode == SyncMode.PUSH_ONLY) {
                    skipped++;
                    modeSkipped++;
                } else {
                    final String message = local == null && previousSha != null
                            && !remote.sha.equals(previousSha)
                            ? "⚠ Restored (edited on another device): " + path : null;
                    if (message != null) {
                        addEvent(events, callback, message);
                    }
                    actions.add(new PlannedAction(path, action, local, remote));
                }
            } else if (action == SyncAction.CONFLICT) {
                actions.add(new PlannedAction(path, action, local, remote));
                conflicts++;
                addEvent(events, callback, "⚠ conflict: " + path);
            } else if (action == SyncAction.DELETE_REMOTE || action == SyncAction.DELETE_LOCAL) {
                actions.add(new PlannedAction(path, action, local, remote));
                addEvent(events, callback, (action == SyncAction.DELETE_REMOTE
                        ? "🗑 Delete from GitHub: " : "🗑 Delete from This Device: ") + path);
            }
            notifyComparedProgress(callback, completed, orderedPaths.size());
        }
        if (orderedPaths.isEmpty()) {
            notifyComparedProgress(callback, 0, 0);
        }
        if (unchangedSkipped > 0 || modeSkipped > 0) {
            final StringBuilder summary = new StringBuilder("↷ skipped: ");
            if (unchangedSkipped > 0) {
                summary.append(unchangedSkipped).append(" unchanged");
            }
            if (modeSkipped > 0) {
                if (unchangedSkipped > 0) {
                    summary.append(", ");
                }
                summary.append(modeSkipped).append(" excluded by sync mode");
            }
            summary.append(" files");
            addEvent(events, callback, summary.toString());
        }

        return new SyncPlan(canonicalRoot, config, mode, remoteTree, actions, state,
                events, oversizedPaths, skipped, conflicts, scan.files, false);
    }

    private SyncReport performSync(final SyncPlan plan, final boolean applyDeletions,
            final ProgressCallback callback) throws IOException {
        if (plan == null) {
            throw new IOException("A GitHub sync plan is required.");
        }
        if (plan.executed) {
            throw new IOException("This GitHub sync plan has already been executed.");
        }
        if (plan.fastNoOp) {
            plan.executed = true;
            final long syncTime = System.currentTimeMillis();
            writeState(plan.state, plan.config, plan.localRoot);
            settingsStore.saveGitHubLocalRootIfEmpty(plan.localRoot.getAbsolutePath());
            settingsStore.saveGitHubLastSyncMs(syncTime);
            return new SyncReport(0, 0, 0, 0, plan.events, false, syncTime, 0, 0,
                    new ArrayList<String>(), new ArrayList<String>());
        }
        if (plan.hasDeletions()) {
            if (plan.remoteTree.headSha != null) {
                if (!plan.remoteTree.headSha.equals(fetchHeadCommit(plan.config))) {
                    throw new IOException("The GitHub repository changed after the sync preview. "
                            + "Run Sync Now again to review the latest changes.");
                }
            } else {
                final TreeSnapshot latestTree = fetchTree(
                        plan.config, new ArrayList<String>(), null, new HashSet<String>());
                if (!same(plan.remoteTree.treeSha, latestTree.treeSha)
                        || plan.remoteTree.branchExists != latestTree.branchExists) {
                    throw new IOException("The GitHub repository changed after the sync preview. "
                            + "Run Sync Now again to review the latest changes.");
                }
            }
            final ScanResult latestLocal = scanLocal(plan.localRoot, plan.state);
            if (!sameLocalSnapshot(plan, latestLocal)) {
                throw new IOException("The local folder changed after the sync preview. "
                        + "Run Sync Now again to review the latest changes.");
            }
        }
        plan.executed = true;

        final List<PendingPush> pendingPushes = new ArrayList<>();
        final List<PlannedAction> downloads = new ArrayList<>();
        final List<String> remoteDeletions = new ArrayList<>();
        final List<PlannedAction> localDeletions = new ArrayList<>();
        final List<String> events = new ArrayList<>(plan.events);
        int pushed = 0;
        int pulled = 0;
        int skipped = plan.skippedCount;
        int remoteDeleted = 0;
        int localDeleted = 0;
        boolean currentFileModified = false;
        final List<String> deletedRemotePaths = new ArrayList<>();
        final List<String> deletedLocalPaths = new ArrayList<>();

        for (PlannedAction action : plan.actions) {
            switch (action.action) {
                case PUSH:
                    pendingPushes.add(new PendingPush(action.path, action.local));
                    break;
                case PULL:
                    downloads.add(action);
                    break;
                case CONFLICT:
                    downloads.add(action);
                    break;
                case DELETE_REMOTE:
                    if (applyDeletions) {
                        remoteDeletions.add(action.path);
                    } else {
                        skipped++;
                        addEvent(events, callback, "↷ deletion skipped: " + action.path);
                    }
                    break;
                case DELETE_LOCAL:
                    if (applyDeletions) {
                        localDeletions.add(action);
                    } else {
                        skipped++;
                        addEvent(events, callback, "↷ deletion skipped: " + action.path);
                    }
                    break;
                case SKIP:
                    break;
                default:
                    throw new IOException("The GitHub sync plan contains an unknown action.");
            }
        }

        final DownloadReport downloadReport = downloadActions(
                plan, downloads, events, callback);
        pulled += downloadReport.pulled;
        currentFileModified |= downloadReport.currentFileModified;

        CommitSnapshot committed = null;
        if (!pendingPushes.isEmpty() || !remoteDeletions.isEmpty()) {
            committed = pushFilesInSingleCommit(plan.config, plan.remoteTree, pendingPushes,
                    remoteDeletions, callback);
            for (PendingPush pending : pendingPushes) {
                setLastSynced(plan.state, pending.path, pending.local);
                pushed++;
                addEvent(events, callback, "↑ pushed: " + pending.path);
            }
            for (String path : remoteDeletions) {
                plan.state.files.remove(path);
                deletedRemotePaths.add(path);
                remoteDeleted++;
            }
        }

        for (PlannedAction action : localDeletions) {
            final File file = localFileForPath(plan.localRoot, action.path);
            if (isCurrentFile(file)) {
                addEvent(events, callback, "⚠ kept open file during local deletion: " + action.path);
                continue;
            }
            if (file.exists() && !file.delete()) {
                throw new IOException("Could not delete the local file: " + action.path);
            }
            removeEmptyParentDirectories(plan.localRoot, file.getParentFile());
            plan.state.files.remove(action.path);
            deletedLocalPaths.add(action.path);
            localDeleted++;
            addEvent(events, callback, "🗑 deleted locally: " + action.path);
        }

        final Set<String> deletionPaths = new HashSet<>();
        for (PlannedAction action : plan.actions) {
            if (action.action == SyncAction.DELETE_REMOTE
                    || action.action == SyncAction.DELETE_LOCAL) {
                deletionPaths.add(action.path);
            }
        }
        final Set<String> presentPaths = new HashSet<>(plan.localFiles.keySet());
        presentPaths.addAll(plan.remoteTree.files.keySet());
        final List<String> staleStatePaths = new ArrayList<>(plan.state.files.keySet());
        for (String path : staleStatePaths) {
            if (!presentPaths.contains(path) && !plan.oversizedPaths.contains(path)
                    && !(deletionPaths.contains(path) && !applyDeletions)) {
                plan.state.files.remove(path);
            }
        }
        if (committed != null) {
            plan.state.lastSyncedCommitSha = committed.commitSha;
            plan.state.lastSyncedTreeSha = committed.treeSha;
        } else {
            plan.state.lastSyncedCommitSha = plan.remoteTree.headSha == null
                    ? "" : plan.remoteTree.headSha;
            plan.state.lastSyncedTreeSha = plan.remoteTree.treeSha == null
                    ? "" : plan.remoteTree.treeSha;
        }
        writeState(plan.state, plan.config, plan.localRoot);
        final long syncTime = System.currentTimeMillis();
        settingsStore.saveGitHubLocalRootIfEmpty(plan.localRoot.getAbsolutePath());
        settingsStore.saveGitHubLastSyncMs(syncTime);
        addEvent(events, callback, "🗑 Remote: " + remoteDeleted);
        addEvent(events, callback, "🗑 Local: " + localDeleted);
        return new SyncReport(pushed, pulled, skipped, plan.conflictCount, events,
                currentFileModified, syncTime, remoteDeleted, localDeleted,
                deletedRemotePaths, deletedLocalPaths);
    }

    private TreeSnapshot fetchTree(final SyncConfig config, final List<String> events,
            final ProgressCallback callback, final Set<String> oversizedPaths) throws IOException {
        final String endpoint = API_ROOT + "/repos/" + config.repo + "/git/trees/"
                + encodeSegment(config.branch) + "?recursive=1";
        final JSONObject response;
        try {
            response = requestJson("GET", endpoint, null, config);
        } catch (GitHubApiException exception) {
            if (exception.statusCode == 404 || exception.statusCode == 409) {
                addEvent(events, callback, "GitHub repository has no branch tree yet; treating it as empty.");
                return new TreeSnapshot(new HashMap<String, RemoteFile>(), null, false, null);
            }
            throw exception;
        }
        if (response.optBoolean("truncated", false)) {
            throw new IOException("GitHub's repository tree is truncated; sync stopped to avoid missing notes.");
        }
        final JSONArray tree = response.optJSONArray("tree");
        if (tree == null) {
            throw new IOException("GitHub returned an invalid repository tree.");
        }
        final String treeSha = response.optString("sha", "");
        if (!treeSha.matches("[0-9a-fA-F]{40}")) {
            throw new IOException("GitHub returned an invalid repository tree SHA.");
        }
        final Map<String, RemoteFile> files = new HashMap<>();
        for (int index = 0; index < tree.length(); index++) {
            final JSONObject entry = tree.optJSONObject(index);
            if (entry == null || !"blob".equals(entry.optString("type"))) {
                continue;
            }
            final String path = entry.optString("path", "");
            if (!isSyncablePath(path)) {
                continue;
            }
            final long size = entry.optLong("size", 0L);
            if (size > MAX_FILE_SIZE_BYTES) {
                oversizedPaths.add(path);
                addEvent(events, callback, "⚠ Skipped (>50 MB): " + path);
                continue;
            }
            final String sha = entry.optString("sha", "");
            if (!sha.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("GitHub returned an invalid blob SHA for " + path + ".");
            }
            files.put(path, new RemoteFile(sha.toLowerCase(Locale.ROOT)));
        }
        return new TreeSnapshot(files, treeSha.toLowerCase(Locale.ROOT), true, null);
    }

    private String fetchHeadCommit(final SyncConfig config) throws IOException {
        final String endpoint = API_ROOT + "/repos/" + config.repo + "/git/ref/heads/"
                + encodePath(config.branch);
        final JSONObject response;
        try {
            response = requestJson("GET", endpoint, null, config);
        } catch (GitHubApiException exception) {
            if (exception.statusCode == 404 || exception.statusCode == 409) {
                return null;
            }
            throw exception;
        }
        final JSONObject object = response.optJSONObject("object");
        final String sha = object == null ? "" : object.optString("sha", "");
        if (!sha.matches("[0-9a-fA-F]{40}")) {
            throw new IOException("GitHub returned an invalid branch commit SHA.");
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    private static TreeSnapshot cachedRemoteTree(final SyncState state, final String headSha) {
        final Map<String, RemoteFile> files = new HashMap<>();
        for (Map.Entry<String, StateFile> entry : state.files.entrySet()) {
            final String sha = entry.getValue().lastSyncedSha;
            if (sha != null && sha.matches("[0-9a-f]{40}")) {
                files.put(entry.getKey(), new RemoteFile(sha));
            }
        }
        return new TreeSnapshot(files, state.lastSyncedTreeSha, true, headSha);
    }

    private ScanResult scanLocal(final File canonicalRoot, final SyncState state) throws IOException {
        final ScanResult result = new ScanResult();
        final String canonicalRootPath = canonicalRoot.getPath();
        scanDirectory(canonicalRootPath, canonicalRoot, canonicalRootPath, "",
                result, state, new HashSet<String>());
        return result;
    }

    private void scanDirectory(final String canonicalRootPath, final File directory,
            final String directoryCanonicalPath, final String directoryRelativePath,
            final ScanResult result, final SyncState state,
            final Set<String> visitedDirectories) throws IOException {
        if (!visitedDirectories.add(directoryCanonicalPath)) {
            return;
        }
        final File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Could not read the local sync folder: " + directory.getPath());
        }
        for (File child : children) {
            if (child.getName().startsWith(".")) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && isSymbolicLink(child)) {
                continue;
            }
            if (child.isDirectory()) {
                final String childCanonicalPath = child.getCanonicalPath();
                if (isInsideRoot(canonicalRootPath, childCanonicalPath)) {
                    final String childRelativePath = appendRelativePath(
                            directoryRelativePath, child.getName());
                    scanDirectory(canonicalRootPath, child, childCanonicalPath,
                            childRelativePath, result, state, visitedDirectories);
                }
            } else if (child.isFile() && isSyncablePath(child.getName())) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                        && !isInsideRoot(canonicalRootPath, child.getCanonicalPath())) {
                    continue;
                }
                final String path = appendRelativePath(directoryRelativePath, child.getName());
                if (child.length() > MAX_FILE_SIZE_BYTES) {
                    result.oversizedPaths.add(path);
                    result.messages.add("⚠ Skipped (>50 MB): " + path);
                } else {
                    final StateFile cached = state.files.get(path);
                    final String sha = cachedOrComputeSha(child,
                            cached == null ? null : cached.cachedLocalSha,
                            cached == null ? -1L : cached.cachedLocalSize,
                            cached == null ? -1L : cached.cachedLocalLastModified);
                    final LocalFile local = new LocalFile(
                            child, sha, child.length(), child.lastModified());
                    result.files.put(path, local);
                    StateFile fileState = cached;
                    if (fileState == null) {
                        fileState = new StateFile();
                        state.files.put(path, fileState);
                    }
                    fileState.cachedLocalSha = sha;
                    fileState.cachedLocalSize = local.size;
                    fileState.cachedLocalLastModified = local.lastModified;
                }
            }
        }
    }

    private static boolean isSymbolicLink(final File path) throws IOException {
        try {
            return OsConstants.S_ISLNK(Os.lstat(path.getPath()).st_mode);
        } catch (ErrnoException exception) {
            throw new IOException("Could not inspect a local sync path: " + path.getPath(),
                    exception);
        }
    }

    private static String appendRelativePath(final String parentRelPath, final String childName) {
        return parentRelPath.isEmpty() ? childName : parentRelPath + "/" + childName;
    }

    static boolean shouldReportComparedProgress(final int completed, final int total) {
        return completed % 100 == 0 || completed == total;
    }

    private static void notifyComparedProgress(final ProgressCallback callback,
            final int completed, final int total) {
        if (shouldReportComparedProgress(completed, total)) {
            notifyProgress(callback, "Compared " + completed + " of " + total + " files.");
        }
    }

    static String cachedOrComputeSha(final File file, final String cachedSha,
            final long cachedSize, final long cachedLastModified) throws IOException {
        if (cachedSha != null && !cachedSha.isEmpty()
                && file.length() == cachedSize && file.lastModified() == cachedLastModified) {
            return cachedSha;
        }
        return GitHubNotesSyncHelper.computeGitBlobSha(readLimitedFile(file));
    }

    private static byte[] readLimitedFile(final File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FILE_SIZE_BYTES) {
                    throw new IOException("File exceeds the 50 MB sync limit: " + file.getName());
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private CommitSnapshot pushFilesInSingleCommit(final SyncConfig config,
            final TreeSnapshot remoteTree,
            final List<PendingPush> pendingPushes, final List<String> deletedPaths,
            final ProgressCallback callback) throws IOException {
        if (!remoteTree.branchExists && pendingPushes.isEmpty()) {
            throw new IOException("Cannot delete remote files because the GitHub branch is missing.");
        }

        final JSONArray treeEntries = new JSONArray();
        final List<String> blobShas = uploadBlobs(config, pendingPushes);
        for (int index = 0; index < pendingPushes.size(); index++) {
            final PendingPush pending = pendingPushes.get(index);
            final String blobSha = blobShas.get(index);
            final JSONObject entry = new JSONObject();
            try {
                entry.put("path", pending.path);
                entry.put("mode", "100644");
                entry.put("type", "blob");
                entry.put("sha", blobSha);
                treeEntries.put(entry);
            } catch (JSONException exception) {
                throw new IOException("Could not prepare the GitHub tree entry for "
                        + pending.path + ".", exception);
            }
            notifyProgress(callback, "Staged " + pending.path + " for one commit.");
        }
        for (String path : deletedPaths) {
            if (!remoteTree.files.containsKey(path)) {
                continue;
            }
            try {
                treeEntries.put(createDeletionTreeEntry(path));
            } catch (JSONException exception) {
                throw new IOException("Could not prepare the GitHub deletion for " + path + ".",
                        exception);
            }
            notifyProgress(callback, "Staged deletion of " + path + ".");
        }

        final JSONObject treeRequest = new JSONObject();
        try {
            if (remoteTree.treeSha != null
                    && remoteTree.treeSha.matches("[0-9a-fA-F]{40}")) {
                treeRequest.put("base_tree", remoteTree.treeSha);
            }
            treeRequest.put("tree", treeEntries);
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the GitHub sync tree.", exception);
        }
        final JSONObject treeResponse = requestJson("POST",
                API_ROOT + "/repos/" + config.repo + "/git/trees", treeRequest, config);
        final String newTreeSha = requireObjectSha(treeResponse, "GitHub did not confirm the sync tree.");

        final JSONObject commitRequest = new JSONObject();
        try {
            commitRequest.put("message", "Markor sync: update notes");
            commitRequest.put("tree", newTreeSha);
            final JSONArray parents = new JSONArray();
            if (remoteTree.headSha != null) {
                parents.put(remoteTree.headSha);
            }
            commitRequest.put("parents", parents);
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the single GitHub sync commit.", exception);
        }
        final JSONObject commitResponse = requestJson("POST",
                API_ROOT + "/repos/" + config.repo + "/git/commits", commitRequest, config);
        final String commitSha = requireObjectSha(
                commitResponse, "GitHub did not confirm the sync commit.");
        updateBranchReference(config, remoteTree.branchExists, commitSha);
        return new CommitSnapshot(commitSha, newTreeSha);
    }

    static JSONObject createDeletionTreeEntry(final String path) throws JSONException {
        final JSONObject entry = new JSONObject();
        entry.put("path", path);
        entry.put("mode", "100644");
        entry.put("type", "blob");
        entry.put("sha", JSONObject.NULL);
        return entry;
    }

    private List<String> uploadBlobs(final SyncConfig config,
            final List<PendingPush> pendingPushes) throws IOException {
        final List<String> blobShas = new ArrayList<>();
        if (pendingPushes.isEmpty()) {
            return blobShas;
        }
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int start = 0; start < pendingPushes.size(); start += 4) {
                final List<Future<String>> futures = new ArrayList<>();
                final int end = Math.min(start + 4, pendingPushes.size());
                for (int index = start; index < end; index++) {
                    final PendingPush pending = pendingPushes.get(index);
                    futures.add(executor.submit(() ->
                            createBlob(config, pending.path, readLimited(pending.file))));
                }
                for (Future<String> future : futures) {
                    blobShas.add(awaitTransfer(future));
                }
            }
        } finally {
            shutdownExecutor(executor);
        }
        return blobShas;
    }

    private String createBlob(final SyncConfig config, final String path, final byte[] bytes)
            throws IOException {
        final JSONObject request = new JSONObject();
        try {
            request.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP));
            request.put("encoding", "base64");
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the GitHub blob for " + path + ".", exception);
        }
        final JSONObject response = requestJsonWithRetry("POST",
                API_ROOT + "/repos/" + config.repo + "/git/blobs", request, config);
        return requireObjectSha(response, "GitHub did not confirm the uploaded blob for " + path + ".");
    }

    private void updateBranchReference(final SyncConfig config, final boolean branchExists,
            final String commitSha) throws IOException {
        final JSONObject request = new JSONObject();
        final String endpoint;
        final String method;
        try {
            request.put("sha", commitSha);
            if (branchExists) {
                request.put("force", true);
                endpoint = API_ROOT + "/repos/" + config.repo + "/git/refs/heads/"
                        + encodePath(config.branch);
                method = "PATCH";
            } else {
                request.put("ref", "refs/heads/" + config.branch);
                endpoint = API_ROOT + "/repos/" + config.repo + "/git/refs";
                method = "POST";
            }
        } catch (JSONException exception) {
            throw new IOException("Could not prepare the GitHub branch update.", exception);
        }
        requestJson(method, endpoint, request, config);
    }

    private static String requireObjectSha(final JSONObject response, final String error)
            throws IOException {
        final String sha = response.optString("sha", "");
        if (!sha.matches("[0-9a-fA-F]{40}")) {
            throw new IOException(error);
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    private byte[] fetchBlob(final SyncConfig config, final String sha) throws IOException {
        final JSONObject response = requestJsonWithRetry("GET",
                API_ROOT + "/repos/" + config.repo + "/git/blobs/" + sha, null, config);
        if (!"base64".equalsIgnoreCase(response.optString("encoding", ""))) {
            throw new IOException("GitHub returned a blob in an unsupported encoding.");
        }
        final String encoded = response.optString("content", "").replaceAll("\\s", "");
        final byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new IOException("GitHub returned invalid Base64 blob content.", exception);
        }
        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw new IOException("GitHub blob exceeds the 50 MB sync limit.");
        }
        return bytes;
    }

    private DownloadReport downloadActions(final SyncPlan plan,
            final List<PlannedAction> downloads, final List<String> events,
            final ProgressCallback callback) throws IOException {
        int pulled = 0;
        boolean currentFileModified = false;
        if (downloads.isEmpty()) {
            return new DownloadReport(pulled, currentFileModified);
        }
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int start = 0; start < downloads.size(); start += 4) {
                final int end = Math.min(start + 4, downloads.size());
                final List<Future<byte[]>> futures = new ArrayList<>();
                for (int index = start; index < end; index++) {
                    final String sha = downloads.get(index).remote.sha;
                    futures.add(executor.submit(() -> fetchBlob(plan.config, sha)));
                }
                for (int index = start; index < end; index++) {
                    final PlannedAction action = downloads.get(index);
                    final byte[] bytes = awaitTransfer(futures.get(index - start));
                    if (action.action == SyncAction.PULL) {
                        final File destination = localFileForPath(plan.localRoot, action.path);
                        writeAtomically(destination, bytes);
                        setLastSynced(plan.state, action.path,
                                new LocalFile(destination, action.remote.sha,
                                        destination.length(), destination.lastModified()));
                        pulled++;
                        currentFileModified |= isCurrentFile(destination);
                        addEvent(events, callback, "↓ pulled: " + action.path);
                    } else {
                        final File conflictFile = createConflictFile(action.local.file, bytes);
                        final StateFile stateFile = plan.state.files.get(action.path);
                        if (stateFile != null) {
                            stateFile.lastSyncedSha = action.remote.sha;
                        }
                        addEvent(events, callback, "⚠ conflict: " + action.path
                                + " (remote saved as "
                                + relativePath(plan.localRoot, conflictFile) + ")");
                    }
                }
            }
        } finally {
            shutdownExecutor(executor);
        }
        return new DownloadReport(pulled, currentFileModified);
    }

    private JSONObject requestJsonWithRetry(final String method, final String endpoint,
            final JSONObject requestBody, final SyncConfig config) throws IOException {
        try {
            return requestJson(method, endpoint, requestBody, config);
        } catch (GitHubApiException exception) {
            if (exception.statusCode != 502 && exception.statusCode != 503) {
                throw exception;
            }
            return requestJson(method, endpoint, requestBody, config);
        }
    }

    private static <T> T awaitTransfer(final Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub blob transfer was interrupted.", exception);
        } catch (ExecutionException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("GitHub blob transfer failed.", cause);
        }
    }

    private static void shutdownExecutor(final ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private JSONObject requestJson(final String method, final String endpoint,
            final JSONObject requestBody, final SyncConfig config) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + config.pat);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "Markor-AI-Assistant");
            if (requestBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            final int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorMessage;
                try {
                    errorMessage = readApiErrorMessage(connection);
                } catch (IOException exception) {
                    errorMessage = "Could not read GitHub error response: "
                            + exception.getMessage();
                }
                throw new GitHubApiException(status, errorMessage);
            }
            final byte[] response = readStream(connection.getInputStream(), MAX_API_RESPONSE_BYTES);
            try {
                return new JSONObject(new String(response, StandardCharsets.UTF_8));
            } catch (JSONException exception) {
                throw new IOException("GitHub returned an invalid JSON response.", exception);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readApiErrorMessage(final HttpURLConnection connection)
            throws IOException {
        final InputStream errorStream = connection.getErrorStream();
        if (errorStream == null) {
            return null;
        }
        final byte[] response;
        try (InputStream input = errorStream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[500];
            int remaining = buffer.length;
            while (remaining > 0) {
                final int count = input.read(buffer, 0, remaining);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            response = output.toByteArray();
        }
        final String body = new String(response, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) {
            return null;
        }
        try {
            final String message = new JSONObject(body).optString("message", "").trim();
            if (!message.isEmpty()) {
                return message;
            }
        } catch (JSONException ignored) {
            // Return non-JSON error bodies as provided by the server.
        }
        return body;
    }

    private static byte[] readStream(final InputStream input, final long limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw new IOException("GitHub response exceeds the supported size limit.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private byte[] readLimited(final File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FILE_SIZE_BYTES) {
                    throw new IOException("File exceeds the 50 MB sync limit: " + file.getName());
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void writeAtomically(final File destination, final byte[] bytes) throws IOException {
        final File parent = destination.getParentFile();
        if (parent == null) {
            throw new IOException("Could not determine the destination folder for a pulled note.");
        }
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create the local folder for " + destination.getName() + ".");
        }
        final File temporary = File.createTempFile(".markor-sync-", ".tmp", parent);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        } catch (IOException exception) {
            if (temporary.exists() && !temporary.delete()) {
                exception.addSuppressed(new IOException("Could not remove an incomplete sync file."));
            }
            throw exception;
        }
        if (!temporary.renameTo(destination)) {
            if (!temporary.delete()) {
                throw new IOException("Could not replace " + destination.getName()
                        + " or remove its temporary file.");
            }
            throw new IOException("Could not replace the local note " + destination.getName() + ".");
        }
    }

    private File createConflictFile(final File localFile, final byte[] remoteBytes) throws IOException {
        final String name = localFile.getName();
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT)
                .format(new Date());
        final File parent = localFile.getParentFile();
        for (int suffix = 0; ; suffix++) {
            final String conflictName = conflictFileName(name, timestamp, suffix);
            final File conflictFile = new File(parent, conflictName);
            if (!conflictFile.createNewFile()) {
                continue;
            }
            try (FileOutputStream output = new FileOutputStream(conflictFile)) {
                output.write(remoteBytes);
                output.getFD().sync();
                return conflictFile;
            } catch (IOException exception) {
                if (!conflictFile.delete()) {
                    exception.addSuppressed(new IOException(
                            "Could not remove an incomplete remote conflict copy."));
                }
                throw exception;
            }
        }
    }

    private SyncState readState(final SyncConfig config, final File localRoot)
            throws IOException {
        final File file = stateFile();
        if (!file.isFile()) {
            return new SyncState();
        }
        try {
            return parseStateJson(new String(
                    readStream(new FileInputStream(file), Long.MAX_VALUE), StandardCharsets.UTF_8),
                    config.repo, config.branch, localRoot.getPath());
        } catch (JSONException exception) {
            throw new IOException("The private GitHub sync state is invalid.", exception);
        }
    }

    static SyncState parseStateJson(final String jsonText, final String repo,
            final String branch, final String localRoot) throws JSONException {
        final JSONObject json = new JSONObject(jsonText);
        if (!repo.equals(json.optString("repo", ""))
                || !branch.equals(json.optString("branch", ""))
                || !localRoot.equals(json.optString("local_root", ""))) {
            return new SyncState();
        }
        final JSONObject files = json.optJSONObject("files");
        if (files == null) {
            throw new JSONException("The private GitHub sync state has no file map.");
        }
        final SyncState state = new SyncState();
        final String commitSha = normalizedSha(json.optString("lastSyncedCommitSha", ""));
        final String treeSha = normalizedSha(json.optString("lastSyncedTreeSha", ""));
        state.lastSyncedCommitSha = commitSha == null ? "" : commitSha;
        state.lastSyncedTreeSha = treeSha == null ? "" : treeSha;
        final java.util.Iterator<String> keys = files.keys();
        while (keys.hasNext()) {
            final String path = keys.next();
            if (!isSafeRelativePath(path)) {
                continue;
            }
            final Object rawFile = files.opt(path);
            final StateFile stateFile = new StateFile();
            if (rawFile instanceof String) {
                stateFile.lastSyncedSha = normalizedSha((String) rawFile);
            } else if (rawFile instanceof JSONObject) {
                final JSONObject fileJson = (JSONObject) rawFile;
                stateFile.lastSyncedSha = normalizedSha(
                        fileJson.optString("lastSyncedSha", ""));
                final String cachedSha = normalizedSha(fileJson.optString("cachedLocalSha", ""));
                stateFile.cachedLocalSha = cachedSha == null ? "" : cachedSha;
                stateFile.cachedLocalSize = fileJson.optLong("cachedLocalSize", -1L);
                stateFile.cachedLocalLastModified =
                        fileJson.optLong("cachedLocalLastModified", -1L);
                if (stateFile.cachedLocalSize < 0 || stateFile.cachedLocalLastModified < 0) {
                    stateFile.cachedLocalSha = "";
                    stateFile.cachedLocalSize = -1L;
                    stateFile.cachedLocalLastModified = -1L;
                }
            }
            if (stateFile.lastSyncedSha != null || !stateFile.cachedLocalSha.isEmpty()) {
                state.files.put(path, stateFile);
            }
        }
        return state;
    }

    private static String normalizedSha(final String sha) {
        return sha != null && sha.matches("[0-9a-fA-F]{40}")
                ? sha.toLowerCase(Locale.ROOT) : null;
    }

    private void writeState(final SyncState state, final SyncConfig config,
            final File localRoot) throws IOException {
        final JSONObject root = new JSONObject();
        final JSONObject files = new JSONObject();
        try {
            for (Map.Entry<String, StateFile> entry : state.files.entrySet()) {
                if (isSafeRelativePath(entry.getKey())) {
                    final StateFile file = entry.getValue();
                    final JSONObject fileJson = new JSONObject();
                    if (file.lastSyncedSha != null) {
                        fileJson.put("lastSyncedSha", file.lastSyncedSha);
                    }
                    fileJson.put("cachedLocalSha", file.cachedLocalSha);
                    fileJson.put("cachedLocalSize", file.cachedLocalSize);
                    fileJson.put("cachedLocalLastModified", file.cachedLocalLastModified);
                    files.put(entry.getKey(), fileJson);
                }
            }
            root.put("repo", config.repo);
            root.put("branch", config.branch);
            root.put("local_root", localRoot.getPath());
            root.put("lastSyncedCommitSha", state.lastSyncedCommitSha);
            root.put("lastSyncedTreeSha", state.lastSyncedTreeSha);
            root.put("files", files);
        } catch (JSONException exception) {
            throw new IOException("Could not encode the private GitHub sync state.", exception);
        }
        final File file = stateFile();
        final File temporary = new File(file.getParentFile(), STATE_FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) {
            if (!temporary.delete()) {
                throw new IOException("Could not save GitHub sync state or remove its temporary file.");
            }
            throw new IOException("Could not save the private GitHub sync state.");
        }
    }

    private File stateFile() throws IOException {
        final File filesDirectory = context.getFilesDir();
        if (filesDirectory == null) {
            throw new IOException("Private app storage is unavailable for GitHub sync state.");
        }
        return new File(filesDirectory, STATE_FILE_NAME);
    }

    private boolean isCurrentFile(final File file) throws IOException {
        if (currentFile == null) {
            return false;
        }
        return file.getCanonicalFile().equals(currentFile.getCanonicalFile());
    }

    private static File localFileForPath(final File root, final String path) throws IOException {
        if (!isSafeRelativePath(path)) {
            throw new IOException("GitHub returned an unsafe note path.");
        }
        final File file = new File(root, path.replace('/', File.separatorChar)).getCanonicalFile();
        if (!isInsideRoot(root, file.getPath())) {
            throw new IOException("GitHub returned a note path outside the selected sync folder.");
        }
        return file;
    }

    public static boolean isSyncablePath(final String path) {
        if (!isSafeRelativePath(path)) {
            return false;
        }
        final String[] components = path.split("/");
        for (String component : components) {
            if (component.startsWith(".")) {
                return false;
            }
        }
        return !"aisettings.txt".equalsIgnoreCase(components[components.length - 1]);
    }

    public static String conflictFileName(final String name, final String timestamp,
            final int suffix) {
        final int extensionIndex = name.lastIndexOf('.');
        final String basename = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        final String extension = extensionIndex > 0 ? name.substring(extensionIndex) : "";
        return basename + "_remote_conflict_" + timestamp
                + (suffix == 0 ? "" : "_" + suffix) + extension;
    }

    public static void removeEmptyParentDirectories(final File root, File parent)
            throws IOException {
        final File canonicalRoot = root.getCanonicalFile();
        while (parent != null) {
            final File canonicalParent = parent.getCanonicalFile();
            if (canonicalParent.equals(canonicalRoot)) {
                return;
            }
            if (!isInsideRoot(canonicalRoot, canonicalParent.getPath())) {
                throw new IOException("Refusing to clean a folder outside the local sync folder.");
            }
            final File[] children = canonicalParent.listFiles();
            if (children == null) {
                throw new IOException("Could not inspect folder during empty-folder cleanup: "
                        + canonicalParent.getPath());
            }
            if (children.length != 0) {
                return;
            }
            if (!canonicalParent.delete()) {
                throw new IOException("Could not remove empty folder: " + canonicalParent.getPath());
            }
            parent = canonicalParent.getParentFile();
        }
    }

    private static boolean isSafeRelativePath(final String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String component : path.split("/")) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInsideRoot(final File root, final String canonicalPath) throws IOException {
        return isInsideRoot(root.getCanonicalPath(), canonicalPath);
    }

    private static boolean isInsideRoot(final String rootPath, final String canonicalPath) {
        return canonicalPath.equals(rootPath)
                || canonicalPath.startsWith(rootPath.endsWith(File.separator)
                        ? rootPath : rootPath + File.separator);
    }

    private static String relativePath(final File root, final File file) throws IOException {
        final String rootPath = root.getCanonicalPath();
        final String filePath = file.getCanonicalPath();
        if (!isInsideRoot(root, filePath) || filePath.equals(rootPath)) {
            throw new IOException("Could not resolve a note path inside the local sync folder.");
        }
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static String encodePath(final String path) throws IOException {
        final StringBuilder encoded = new StringBuilder();
        final String[] components = path.split("/");
        for (int index = 0; index < components.length; index++) {
            if (index > 0) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(components[index]));
        }
        return encoded.toString();
    }

    private static String encodeSegment(final String segment) throws IOException {
        try {
            return URLEncoder.encode(segment, "UTF-8")
                    .replace("+", "%20").replace("%7E", "~");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IOException("UTF-8 is unavailable for GitHub paths.", exception);
        }
    }

    private static void validateConfig(final SyncConfig config) throws IOException {
        if (config == null || config.pat.isEmpty()) {
            throw new IOException("Enter a GitHub Personal Access Token in Sync Settings.");
        }
        if (config.pat.indexOf('\r') >= 0 || config.pat.indexOf('\n') >= 0) {
            throw new IOException("The GitHub token must be a single line.");
        }
        if (!config.repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IOException("Enter a GitHub repository in owner/repo format.");
        }
        if (config.branch.isEmpty() || config.branch.indexOf('\r') >= 0
                || config.branch.indexOf('\n') >= 0) {
            throw new IOException("Enter a valid GitHub branch name.");
        }
    }

    private static boolean same(final String left, final String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean hasLocalChanges(final ScanResult scan, final SyncState state) {
        for (Map.Entry<String, LocalFile> entry : scan.files.entrySet()) {
            final StateFile stateFile = state.files.get(entry.getKey());
            if (stateFile == null || !entry.getValue().sha.equals(stateFile.lastSyncedSha)) {
                return true;
            }
        }
        for (Map.Entry<String, StateFile> entry : state.files.entrySet()) {
            if (entry.getValue().lastSyncedSha != null
                    && !scan.files.containsKey(entry.getKey())
                    && !scan.oversizedPaths.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    static boolean isFastNoOp(final String remoteHeadSha, final String lastSyncedCommitSha,
            final boolean localChanges) {
        return remoteHeadSha != null && remoteHeadSha.equals(lastSyncedCommitSha)
                && !localChanges;
    }

    private static void setLastSynced(final SyncState state, final String path,
            final LocalFile local) {
        StateFile fileState = state.files.get(path);
        if (fileState == null) {
            fileState = new StateFile();
            state.files.put(path, fileState);
        }
        fileState.lastSyncedSha = local.sha;
        fileState.cachedLocalSha = local.sha;
        fileState.cachedLocalSize = local.size;
        fileState.cachedLocalLastModified = local.lastModified;
    }

    private static boolean sameLocalSnapshot(final SyncPlan plan, final ScanResult latest) {
        if (!plan.localFiles.keySet().equals(latest.files.keySet())
                || !plan.oversizedPaths.containsAll(latest.oversizedPaths)
                || !latest.oversizedPaths.containsAll(plan.oversizedPaths)) {
            return false;
        }
        for (Map.Entry<String, LocalFile> entry : plan.localFiles.entrySet()) {
            final LocalFile current = latest.files.get(entry.getKey());
            if (current == null || !entry.getValue().sha.equals(current.sha)) {
                return false;
            }
        }
        return true;
    }

    private static void addEvent(final List<String> events, final ProgressCallback callback,
            final String message) {
        events.add(message);
        notifyProgress(callback, message);
    }

    private static void notifyProgress(final ProgressCallback callback, final String message) {
        if (callback != null) {
            callback.onProgress(message);
        }
    }

    private static final class ScanResult {
        private final Map<String, LocalFile> files = new HashMap<>();
        private final List<String> messages = new ArrayList<>();
        private final Set<String> oversizedPaths = new HashSet<>();
    }

    private static final class LocalFile {
        private final File file;
        private final String sha;
        private final long size;
        private final long lastModified;

        private LocalFile(final File file, final String sha, final long size,
                final long lastModified) {
            this.file = file;
            this.sha = sha;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    private static final class RemoteFile {
        private final String sha;

        private RemoteFile(final String sha) {
            this.sha = sha;
        }
    }

    private static final class TreeSnapshot {
        private final Map<String, RemoteFile> files;
        private final String treeSha;
        private final boolean branchExists;
        private final String headSha;

        private TreeSnapshot(final Map<String, RemoteFile> files, final String treeSha,
                final boolean branchExists, final String headSha) {
            this.files = files;
            this.treeSha = treeSha;
            this.branchExists = branchExists;
            this.headSha = headSha;
        }
    }

    private static final class PendingPush {
        private final String path;
        private final File file;
        private final LocalFile local;

        private PendingPush(final String path, final LocalFile local) {
            this.path = path;
            this.file = local.file;
            this.local = local;
        }
    }

    static final class SyncState {
        final Map<String, StateFile> files = new HashMap<>();
        String lastSyncedCommitSha = "";
        String lastSyncedTreeSha = "";
    }

    static final class StateFile {
        String lastSyncedSha;
        String cachedLocalSha = "";
        long cachedLocalSize = -1L;
        long cachedLocalLastModified = -1L;
    }

    private static final class DownloadReport {
        private final int pulled;
        private final boolean currentFileModified;

        private DownloadReport(final int pulled, final boolean currentFileModified) {
            this.pulled = pulled;
            this.currentFileModified = currentFileModified;
        }
    }

    private static final class CommitSnapshot {
        private final String commitSha;
        private final String treeSha;

        private CommitSnapshot(final String commitSha, final String treeSha) {
            this.commitSha = commitSha;
            this.treeSha = treeSha;
        }
    }

    private static final class PlannedAction {
        private final String path;
        private final SyncAction action;
        private final LocalFile local;
        private final RemoteFile remote;

        private PlannedAction(final String path, final SyncAction action,
                final LocalFile local, final RemoteFile remote) {
            this.path = path;
            this.action = action;
            this.local = local;
            this.remote = remote;
        }
    }

    public static final class SyncPlan {
        private final File localRoot;
        private final SyncConfig config;
        private final SyncMode mode;
        private final TreeSnapshot remoteTree;
        private final List<PlannedAction> actions;
        private final SyncState state;
        private final List<String> events;
        private final Set<String> oversizedPaths;
        private final int skippedCount;
        private final int conflictCount;
        private final Map<String, LocalFile> localFiles;
        private final boolean fastNoOp;
        private boolean executed;

        private SyncPlan(final File localRoot, final SyncConfig config, final SyncMode mode,
                final TreeSnapshot remoteTree, final List<PlannedAction> actions,
                final SyncState state, final List<String> events,
                final Set<String> oversizedPaths, final int skippedCount, final int conflictCount,
                final Map<String, LocalFile> localFiles, final boolean fastNoOp) {
            this.localRoot = localRoot;
            this.config = config;
            this.mode = mode;
            this.remoteTree = remoteTree;
            this.actions = actions;
            this.state = state;
            this.events = events;
            this.oversizedPaths = oversizedPaths;
            this.skippedCount = skippedCount;
            this.conflictCount = conflictCount;
            this.localFiles = localFiles;
            this.fastNoOp = fastNoOp;
        }

        public List<String> getRemoteDeletionPaths() {
            return getDeletionPaths(SyncAction.DELETE_REMOTE);
        }

        public List<String> getLocalDeletionPaths() {
            return getDeletionPaths(SyncAction.DELETE_LOCAL);
        }

        public boolean hasDeletions() {
            return !getRemoteDeletionPaths().isEmpty() || !getLocalDeletionPaths().isEmpty();
        }

        private List<String> getDeletionPaths(final SyncAction action) {
            final List<String> paths = new ArrayList<>();
            for (PlannedAction plannedAction : actions) {
                if (plannedAction.action == action) {
                    paths.add(plannedAction.path);
                }
            }
            return Collections.unmodifiableList(paths);
        }
    }

    private static final class GitHubApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        private GitHubApiException(final int statusCode, final String errorMessage) {
            super("GitHub API HTTP " + statusCode
                    + (errorMessage == null || errorMessage.isEmpty()
                            ? "." : ": " + errorMessage));
            this.statusCode = statusCode;
        }
    }

    public static final class SyncConfig {
        private final String pat;
        private final String repo;
        private final String branch;

        public SyncConfig(final String pat, final String repo, final String branch) {
            this.pat = pat == null ? "" : pat.trim();
            this.repo = repo == null ? "" : repo.trim();
            this.branch = branch == null || branch.trim().isEmpty() ? "main" : branch.trim();
        }
    }

    public static final class SyncReport {
        private final int pushedCount;
        private final int pulledCount;
        private final int skippedCount;
        private final int conflictCount;
        private final List<String> events;
        private final boolean currentFileModified;
        private final long syncTimeMs;
        private final int remoteDeletedCount;
        private final int localDeletedCount;
        private final List<String> deletedRemotePaths;
        private final List<String> deletedLocalPaths;

        private SyncReport(final int pushedCount, final int pulledCount, final int skippedCount,
                final int conflictCount, final List<String> events,
                final boolean currentFileModified, final long syncTimeMs,
                final int remoteDeletedCount, final int localDeletedCount,
                final List<String> deletedRemotePaths, final List<String> deletedLocalPaths) {
            this.pushedCount = pushedCount;
            this.pulledCount = pulledCount;
            this.skippedCount = skippedCount;
            this.conflictCount = conflictCount;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.currentFileModified = currentFileModified;
            this.syncTimeMs = syncTimeMs;
            this.remoteDeletedCount = remoteDeletedCount;
            this.localDeletedCount = localDeletedCount;
            this.deletedRemotePaths = Collections.unmodifiableList(
                    new ArrayList<>(deletedRemotePaths));
            this.deletedLocalPaths = Collections.unmodifiableList(
                    new ArrayList<>(deletedLocalPaths));
        }

        public int getPushedCount() {
            return pushedCount;
        }

        public int getPulledCount() {
            return pulledCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public int getConflictCount() {
            return conflictCount;
        }

        public List<String> getEvents() {
            return events;
        }

        public boolean isCurrentFileModified() {
            return currentFileModified;
        }

        public long getSyncTimeMs() {
            return syncTimeMs;
        }

        public int getRemoteDeletedCount() {
            return remoteDeletedCount;
        }

        public int getLocalDeletedCount() {
            return localDeletedCount;
        }

        public List<String> getDeletedRemotePaths() {
            return deletedRemotePaths;
        }

        public List<String> getDeletedLocalPaths() {
            return deletedLocalPaths;
        }
    }
}
