package run.halo.roaming.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;
import run.halo.roaming.infra.PluginJson;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeRoamingSettingsService {

    private static final ObjectMapper JSON = PluginJson.mapper();

    private final ReactiveExtensionClient client;
    private final PluginContext pluginContext;

    public Mono<ThemeRoamingSettings> getSettings() {
        return Mono.defer(() -> client.fetch(ConfigMap.class, pluginContext.getConfigMapName()))
            .mapNotNull(ConfigMap::getData)
            .mapNotNull(data -> data.get(ThemeRoamingSettings.GROUP))
            .filter(StringUtils::isNotBlank)
            .map(this::decode)
            .onErrorResume(error -> {
                log.warn("[ThemeRoaming] Failed to read settings, using safe defaults: {}", error.getMessage());
                return Mono.just(ThemeRoamingSettings.defaults());
            })
            .defaultIfEmpty(ThemeRoamingSettings.defaults());
    }

    private ThemeRoamingSettings decode(String value) {
        try {
            return JSON.readValue(value, ThemeRoamingSettings.class).normalized();
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid theme roaming settings", error);
        }
    }
}
