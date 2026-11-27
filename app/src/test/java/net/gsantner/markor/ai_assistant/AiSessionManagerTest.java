package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class AiSessionManagerTest {
    @Test
    public void multipleToolsCanBeMinimizedWithoutOverwritingEachOther() {
        final AiSessionManager sessions = new AiSessionManager();
        sessions.register("teacher", "AI Teacher", null, null);
        sessions.register("chat", "Tutor Chat", null, null);

        assertThat(sessions.minimize("teacher")).isTrue();
        assertThat(sessions.minimize("chat")).isTrue();

        assertThat(sessions.getEntries()).extracting(AiSessionManager.SessionEntry::getToolKey)
                .containsExactly("teacher", "chat");
        assertThat(sessions.getMinimizedCount()).isEqualTo(2);
    }

    @Test
    public void closingOneToolLeavesOtherSessionsAndCleanupIntact() {
        final AiSessionManager sessions = new AiSessionManager();
        final AtomicInteger teacherCleanup = new AtomicInteger();
        final AtomicInteger chatCleanup = new AtomicInteger();
        sessions.register("teacher", "AI Teacher", null, teacherCleanup::incrementAndGet);
        sessions.register("chat", "Tutor Chat", null, chatCleanup::incrementAndGet);
        sessions.minimize("teacher");
        sessions.minimize("chat");

        assertThat(sessions.close("teacher")).isTrue();

        assertThat(sessions.contains("teacher")).isFalse();
        assertThat(sessions.contains("chat")).isTrue();
        assertThat(sessions.getMinimizedCount()).isEqualTo(1);
        assertThat(teacherCleanup).hasValue(1);
        assertThat(chatCleanup).hasValue(0);
    }

    @Test
    public void mostRecentlyMinimizedAndReleaseAllAreAccurate() {
        final AiSessionManager sessions = new AiSessionManager();
        final AtomicInteger cleanups = new AtomicInteger();
        sessions.register("teacher", "AI Teacher", null, cleanups::incrementAndGet);
        sessions.register("chat", "Tutor Chat", null, cleanups::incrementAndGet);
        sessions.minimize("teacher");
        sessions.minimize("chat");

        assertThat(sessions.getMostRecentlyMinimized().getToolKey()).isEqualTo("chat");
        assertThat(sessions.minimize("teacher")).isTrue();
        assertThat(sessions.getMostRecentlyMinimized().getToolKey()).isEqualTo("teacher");

        sessions.releaseAll();

        assertThat(sessions.getEntries()).isEmpty();
        assertThat(sessions.getMinimizedCount()).isZero();
        assertThat(sessions.getMostRecentlyMinimized()).isNull();
        assertThat(cleanups).hasValue(2);
    }
}
