package serenity.roaming.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Plugin-owned JSON mapper. Halo plugin application contexts do not expose the host mapper as a
 * child-context bean, so JSON handling must remain explicit and local to the plugin.
 */
public final class PluginJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .build();

    private PluginJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
