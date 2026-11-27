package net.gsantner.markor.ai_assistant;

import android.app.Dialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AiSessionManager {
    public static final class SessionEntry {
        private final String toolKey;
        private final String displayLabel;
        private final Dialog dialog;
        private Runnable onResetCleanup;

        private SessionEntry(final String toolKey, final String displayLabel,
                final Dialog dialog, final Runnable onResetCleanup) {
            this.toolKey = toolKey;
            this.displayLabel = displayLabel;
            this.dialog = dialog;
            this.onResetCleanup = onResetCleanup;
        }

        public String getToolKey() {
            return toolKey;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }

        public Dialog getDialog() {
            return dialog;
        }

        private void runCleanup() {
            final Runnable cleanup = onResetCleanup;
            onResetCleanup = null;
            if (cleanup != null) {
                cleanup.run();
            }
        }
    }

    private final LinkedHashMap<String, SessionEntry> sessions = new LinkedHashMap<>();
    private final Set<String> minimizedToolKeys = new LinkedHashSet<>();

    public void register(final String toolKey, final String displayLabel,
            final Dialog dialog, final Runnable onResetCleanup) {
        if (toolKey == null || toolKey.trim().isEmpty()) {
            throw new IllegalArgumentException("A tool key is required.");
        }
        final SessionEntry existing = sessions.get(toolKey);
        if (existing != null && existing.dialog == dialog) {
            existing.onResetCleanup = onResetCleanup;
            return;
        }
        if (existing != null) {
            close(toolKey);
        }
        sessions.put(toolKey, new SessionEntry(toolKey,
                displayLabel == null ? toolKey : displayLabel, dialog, onResetCleanup));
    }

    public void setOnResetCleanup(final String toolKey, final Runnable onResetCleanup) {
        final SessionEntry entry = sessions.get(toolKey);
        if (entry != null) {
            entry.onResetCleanup = onResetCleanup;
        }
    }

    public SessionEntry get(final String toolKey) {
        return sessions.get(toolKey);
    }

    public boolean contains(final String toolKey) {
        return sessions.containsKey(toolKey);
    }

    public List<SessionEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    public int getMinimizedCount() {
        return minimizedToolKeys.size();
    }

    public SessionEntry getMostRecentlyMinimized() {
        String mostRecentKey = null;
        for (String toolKey : minimizedToolKeys) {
            mostRecentKey = toolKey;
        }
        return mostRecentKey == null ? null : sessions.get(mostRecentKey);
    }

    public String getToolKey(final Dialog dialog) {
        for (SessionEntry entry : sessions.values()) {
            if (entry.dialog == dialog) {
                return entry.toolKey;
            }
        }
        return null;
    }

    public boolean minimize(final String toolKey) {
        final SessionEntry entry = sessions.get(toolKey);
        if (entry == null) {
            return false;
        }
        if (entry.dialog instanceof AiUiTheme.MinimizableDialog) {
            ((AiUiTheme.MinimizableDialog) entry.dialog).markMinimized();
        }
        if (entry.dialog != null && entry.dialog.isShowing()) {
            entry.dialog.hide();
        }
        minimizedToolKeys.remove(toolKey);
        minimizedToolKeys.add(toolKey);
        return true;
    }

    public boolean minimize(final Dialog dialog) {
        final String toolKey = getToolKey(dialog);
        return toolKey != null && minimize(toolKey);
    }

    public boolean resume(final String toolKey) {
        final SessionEntry entry = sessions.get(toolKey);
        if (entry == null || entry.dialog == null) {
            return false;
        }
        minimizedToolKeys.remove(toolKey);
        entry.dialog.show();
        return true;
    }

    public boolean resumeMostRecentlyMinimized() {
        final SessionEntry entry = getMostRecentlyMinimized();
        return entry != null && resume(entry.toolKey);
    }

    public boolean close(final String toolKey) {
        final SessionEntry entry = sessions.remove(toolKey);
        minimizedToolKeys.remove(toolKey);
        if (entry == null) {
            return false;
        }
        if (entry.dialog != null) {
            entry.dialog.dismiss();
        }
        entry.runCleanup();
        return true;
    }

    public boolean forget(final Dialog dialog) {
        final String toolKey = getToolKey(dialog);
        if (toolKey == null) {
            return false;
        }
        final SessionEntry entry = sessions.remove(toolKey);
        minimizedToolKeys.remove(toolKey);
        if (entry != null) {
            entry.runCleanup();
            return true;
        }
        return false;
    }

    public void releaseAll() {
        final List<SessionEntry> entries = new ArrayList<>(sessions.values());
        sessions.clear();
        minimizedToolKeys.clear();
        for (SessionEntry entry : entries) {
            if (entry.dialog != null) {
                entry.dialog.dismiss();
            }
            entry.runCleanup();
        }
    }
}
