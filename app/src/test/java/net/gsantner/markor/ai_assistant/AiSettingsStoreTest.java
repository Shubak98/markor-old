package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class AiSettingsStoreTest {

    @Test
    public void importingSettingsPreservesThisDevicesName() {
        final Map<String, Object> settings = new HashMap<>();
        settings.put("github_device_name", "This Device");

        AiSettingsStore.importSettingsFromJson(settings,
                "{\"github_device_name\":\"Exporting Device\",\"github_repo\":\"owner/repo\"}");

        assertThat(settings.get("github_device_name")).isEqualTo("This Device");
    }
}
