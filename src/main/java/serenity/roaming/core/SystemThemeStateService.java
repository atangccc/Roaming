package serenity.roaming.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import serenity.roaming.infra.PluginJson;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemThemeStateService {

    private static final String SYSTEM_CONFIG_NAME = "system";
    private static final String THEME_GROUP = "theme";
    private static final String ROUTE_RULES_GROUP = "routeRules";
    private static final String MENU_GROUP = "menu";
    private static final ObjectMapper JSON = PluginJson.mapper();

    private final ReactiveExtensionClient client;

    public Mono<SystemThemeState> getState() {
        return client.fetch(ConfigMap.class, SYSTEM_CONFIG_NAME)
            .map(configMap -> new SystemThemeState(
                text(configMap, THEME_GROUP, "active"),
                text(configMap, MENU_GROUP, "primary"),
                bool(configMap, ROUTE_RULES_GROUP, "disableThemePreview")
            ))
            .onErrorResume(error -> {
                log.warn("[ThemeRoaming] Failed to read Halo system state: {}", error.getMessage());
                return Mono.just(SystemThemeState.empty());
            })
            .defaultIfEmpty(SystemThemeState.empty());
    }

    private String text(ConfigMap configMap, String group, String field) {
        JsonNode node = group(configMap, group);
        return node.path(field).asText("").trim();
    }

    private boolean bool(ConfigMap configMap, String group, String field) {
        return group(configMap, group).path(field).asBoolean(false);
    }

    private JsonNode group(ConfigMap configMap, String group) {
        if (configMap.getData() == null || StringUtils.isBlank(configMap.getData().get(group))) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(configMap.getData().get(group));
        } catch (Exception error) {
            log.warn("[ThemeRoaming] Invalid Halo system config group '{}': {}", group, error.getMessage());
            return JSON.createObjectNode();
        }
    }

    public record SystemThemeState(String activeThemeName, String primaryMenuName, boolean previewDisabled) {
        static SystemThemeState empty() {
            return new SystemThemeState("", "", false);
        }
    }
}
