package serenity.roaming.menu;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import reactor.core.publisher.Mono;
import run.halo.app.theme.finders.Finder;
import serenity.roaming.config.ThemeRoamingSettingsService;
import serenity.roaming.core.ThemeRoamingRequestContext;
import serenity.roaming.menu.BoundMenuService.MenuView;

@Finder("themeRoamingMenuFinder")
@RequiredArgsConstructor
public class ThemeRoamingMenuFinder {

    private final BoundMenuService menuService;
    private final ThemeRoamingSettingsService settingsService;

    /**
     * Resolves the menu explicitly bound to the theme currently being rendered. Theme templates
     * should prefer this method because it does not depend on implicit reactive request context.
     */
    public Mono<MenuView> getForTheme(String themeName) {
        return settingsService.getSettings()
            .filter(settings -> settings.hasEnabledBindings())
            .flatMap(settings -> Mono.justOrEmpty(settings.findEnabledBinding(themeName)))
            .flatMap(binding -> menuService.getByName(binding.menuName()))
            .switchIfEmpty(menuService.getGlobalPrimary());
    }

    public Mono<MenuView> getPrimary() {
        return Mono.deferContextual(contextView -> ServerWebExchangeContextFilter.getExchange(contextView)
                .map(exchange -> exchange.getAttributeOrDefault(
                    ThemeRoamingRequestContext.BOUND_MENU_ATTRIBUTE, ""))
                .filter(StringUtils::isNotBlank)
                .map(menuService::getByName)
                .orElseGet(Mono::empty))
            .switchIfEmpty(menuService.getGlobalPrimary());
    }

    public Mono<MenuView> getByName(String menuName) {
        return menuService.getByName(menuName);
    }
}
