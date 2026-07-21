package serenity.roaming.core;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import reactor.core.publisher.Mono;
import run.halo.app.theme.finders.Finder;
import serenity.roaming.core.ThemeCatalogService.ThemeOption;

/**
 * Read-only theme integration API exposed to Halo theme templates.
 */
@Finder("themeRoamingFinder")
@RequiredArgsConstructor
public class ThemeRoamingFinder {

    private final ThemeCatalogService catalogService;
    private final SystemThemeStateService systemThemeStateService;

    public Mono<IntegrationCatalog> getCatalog() {
        return Mono.zip(catalogService.publicCatalog(), systemThemeStateService.getState())
            .flatMap(tuple -> currentThemeName(tuple.getT2().activeThemeName())
                .map(currentTheme -> new IntegrationCatalog(
                    !tuple.getT1().themes().isEmpty(),
                    currentTheme,
                    tuple.getT2().activeThemeName(),
                    tuple.getT1().themes()
                )));
    }

    public Mono<List<ThemeOption>> getThemes() {
        return catalogService.publicCatalog().map(ThemeCatalogService.ThemeCatalog::themes);
    }

    public Mono<String> getCurrentThemeName() {
        return systemThemeStateService.getState()
            .flatMap(state -> currentThemeName(state.activeThemeName()));
    }

    private Mono<String> currentThemeName(String fallback) {
        return Mono.deferContextual(contextView -> Mono.just(
            ServerWebExchangeContextFilter.getExchange(contextView)
                .map(exchange -> exchange.getAttributeOrDefault(
                    ThemeRoamingRequestContext.SELECTED_THEME_ATTRIBUTE, fallback))
                .orElse(fallback)
        ));
    }

    public record IntegrationCatalog(
        boolean available,
        String currentTheme,
        String activeTheme,
        List<ThemeOption> themes
    ) {
    }
}
