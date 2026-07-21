package serenity.roaming.api;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import serenity.roaming.core.SystemThemeStateService;
import serenity.roaming.core.ThemeCatalogService;

@Component
@RequiredArgsConstructor
public class ThemeRoamingPublicEndpoint implements CustomEndpoint {

    private static final String API_VERSION = "v1alpha1";

    private final ThemeCatalogService catalogService;
    private final SystemThemeStateService systemThemeStateService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route().GET("catalog", this::catalog).build();
    }

    private Mono<ServerResponse> catalog(ServerRequest request) {
        return Mono.zip(catalogService.publicCatalog(), systemThemeStateService.getState())
            .flatMap(tuple -> {
                var catalog = tuple.getT1();
                var state = tuple.getT2();
                var settings = catalog.settings();
                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("apiVersion", API_VERSION);
                response.put("available", !catalog.themes().isEmpty());
                response.put("activeTheme", state.activeThemeName());
                response.put("persistence", settings.persistence());
                response.put("audience", settings.audience());
                response.put("entryPosition", settings.entryPosition());
                response.put("animation", settings.animation());
                response.put("themes", catalog.themes());
                return ServerResponse.ok()
                    .cacheControl(CacheControl.noStore().mustRevalidate())
                    .header("Pragma", "no-cache")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(response);
            });
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("roaming.serenity/v1alpha1");
    }
}
