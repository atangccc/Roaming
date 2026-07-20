package run.halo.roaming.web;

import java.net.URI;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AfterSecurityWebFilter;
import run.halo.roaming.config.ThemeRoamingSettings;
import run.halo.roaming.config.ThemeRoamingSettings.Audience;
import run.halo.roaming.config.ThemeRoamingSettings.ThemeBinding;
import run.halo.roaming.config.ThemeRoamingSettingsService;
import run.halo.roaming.core.SystemThemeStateService;
import run.halo.roaming.core.ThemeCatalogService;
import run.halo.roaming.core.HaloThemeContextBridge;
import run.halo.roaming.core.ThemeRoamingRequestContext;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThemeRoamingSecurityFilter implements AfterSecurityWebFilter {

    static final String COOKIE_NAME = "theme-roaming";

    private final ThemeRoamingSettingsService settingsService;
    private final SystemThemeStateService systemThemeStateService;
    private final ThemeCatalogService catalogService;
    private final HaloThemeContextBridge themeContextBridge;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isThemePageRequest(exchange)) {
            return chain.filter(exchange);
        }

        Mono<AuthenticationState> authentication = ReactiveSecurityContextHolder.getContext()
            .map(context -> AuthenticationState.from(context.getAuthentication()))
            .defaultIfEmpty(AuthenticationState.anonymous());

        return Mono.zip(settingsService.getSettings(), systemThemeStateService.getState(), authentication)
            .flatMap(tuple -> process(exchange, chain, tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private Mono<Void> process(
        ServerWebExchange exchange,
        WebFilterChain chain,
        ThemeRoamingSettings settings,
        SystemThemeStateService.SystemThemeState systemState,
        AuthenticationState authentication
    ) {
        boolean audienceAllowed = settings.audienceMode() == Audience.EVERYONE || authentication.authenticated();
        boolean frontendEnabled = audienceAllowed && settings.hasEnabledBindings();
        exchange.getAttributes().put(ThemeRoamingRequestContext.FRONTEND_ENABLED_ATTRIBUTE, frontendEnabled);

        String candidate = cookieValue(exchange);

        if (!audienceAllowed) {
            return StringUtils.isNotBlank(candidate)
                ? clearSelectionAndRedirect(exchange)
                : applyDefaultContext(exchange, chain, settings, systemState);
        }

        if (StringUtils.isBlank(candidate)) {
            return applyDefaultContext(exchange, chain, settings, systemState);
        }

        var binding = settings.findEnabledBinding(candidate);
        if (binding.isEmpty()) {
            return clearSelectionAndRedirect(exchange);
        }

        return catalogService.isReady(candidate).flatMap(ready -> {
            if (!ready) {
                return clearSelectionAndRedirect(exchange);
            }
            if (candidate.equals(systemState.activeThemeName())) {
                applyContext(exchange, candidate, binding.orElse(null));
                return chain.filter(exchange);
            }
            return renderWithVisitorTheme(exchange, chain, candidate, binding.orElse(null));
        });
    }

    private Mono<Void> applyDefaultContext(
        ServerWebExchange exchange,
        WebFilterChain chain,
        ThemeRoamingSettings settings,
        SystemThemeStateService.SystemThemeState systemState
    ) {
        ThemeBinding binding = settings.findEnabledBinding(systemState.activeThemeName()).orElse(null);
        applyContext(exchange, systemState.activeThemeName(), binding);
        return chain.filter(exchange);
    }

    private void applyContext(ServerWebExchange exchange, String themeName, ThemeBinding binding) {
        exchange.getAttributes().put(ThemeRoamingRequestContext.SELECTED_THEME_ATTRIBUTE, themeName);
        if (binding != null && StringUtils.isNotBlank(binding.menuName())) {
            exchange.getAttributes().put(ThemeRoamingRequestContext.BOUND_MENU_ATTRIBUTE, binding.menuName());
        }
    }

    private String cookieValue(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
        return cookie == null ? "" : StringUtils.trimToEmpty(cookie.getValue());
    }

    private Mono<Void> renderWithVisitorTheme(
        ServerWebExchange exchange,
        WebFilterChain chain,
        String themeName,
        ThemeBinding binding
    ) {
        return themeContextBridge.createVisitorThemeContext(themeName)
            .flatMap(themeContext -> {
                themeContextBridge.install(exchange, themeContext);
                applyContext(exchange, themeName, binding);
                return chain.filter(exchange);
            })
            .onErrorResume(error -> {
                log.error("[ThemeRoaming] Visitor theme context is unavailable", error);
                return clearSelectionAndRedirect(exchange);
            });
    }

    private Mono<Void> clearSelectionAndRedirect(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(ResponseCookie.from(COOKIE_NAME, "")
            .path("/").maxAge(Duration.ZERO).httpOnly(false).sameSite("Lax").build());
        return redirect(exchange, exchange.getRequest().getURI());
    }

    private Mono<Void> redirect(ServerWebExchange exchange, URI target) {
        exchange.getResponse().setStatusCode(HttpStatus.SEE_OTHER);
        exchange.getResponse().getHeaders().setLocation(target);
        return exchange.getResponse().setComplete();
    }

    private boolean isThemePageRequest(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/apis/") || path.startsWith("/console") || path.startsWith("/uc")
            || path.startsWith("/plugins/") || path.startsWith("/themes/")
            || path.startsWith("/assets/") || path.startsWith("/ui-assets/")) {
            return false;
        }
        var accept = exchange.getRequest().getHeaders().getAccept();
        return accept.isEmpty() || accept.stream().anyMatch(mediaType -> mediaType.includes(org.springframework.http.MediaType.TEXT_HTML));
    }

    private record AuthenticationState(boolean authenticated) {
        static AuthenticationState from(Authentication authentication) {
            if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
                return anonymous();
            }
            return new AuthenticationState(true);
        }

        static AuthenticationState anonymous() {
            return new AuthenticationState(false);
        }
    }
}
