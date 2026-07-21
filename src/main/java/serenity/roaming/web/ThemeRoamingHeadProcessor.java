package serenity.roaming.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import run.halo.app.plugin.PluginContext;
import serenity.roaming.infra.PluginJson;
import serenity.roaming.config.ThemeRoamingSettingsService;
import serenity.roaming.core.ThemeRoamingRequestContext;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE - 40)
public class ThemeRoamingHeadProcessor implements TemplateHeadProcessor {

    private static final String ASSET_ROOT = "/plugins/plugin-theme-roaming/assets/static/";
    private static final String CATALOG_ENDPOINT = "/apis/roaming.serenity/v1alpha1/catalog";
    private static final ObjectMapper JSON = PluginJson.mapper();

    private final ThemeRoamingSettingsService settingsService;
    private final PluginContext pluginContext;

    @Override
    public Mono<Void> process(
        ITemplateContext context,
        IModel model,
        IElementModelStructureHandler structureHandler
    ) {
        ServerWebExchange exchange = exchange(context);
        if (exchange == null || !Boolean.TRUE.equals(exchange.getAttribute(
            ThemeRoamingRequestContext.FRONTEND_ENABLED_ATTRIBUTE))) {
            return Mono.empty();
        }

        return settingsService.getSettings()
            .filter(settings -> settings.hasEnabledBindings())
            .doOnNext(settings -> inject(context.getModelFactory(), model, exchange, settings))
            .onErrorResume(error -> {
                log.warn("[ThemeRoaming] Head injection failed safely: {}", error.getMessage());
                return Mono.empty();
            })
            .then();
    }

    private void inject(
        IModelFactory factory,
        IModel model,
        ServerWebExchange exchange,
        serenity.roaming.config.ThemeRoamingSettings settings
    ) {
        String selectedTheme = exchange.getAttributeOrDefault(
            ThemeRoamingRequestContext.SELECTED_THEME_ATTRIBUTE, "");
        Map<String, Object> clientConfig = Map.of(
            "catalogEndpoint", CATALOG_ENDPOINT,
            "selectedTheme", selectedTheme,
            "persistence", settings.persistence(),
            "entryPosition", settings.entryPosition(),
            "animation", settings.animation()
        );
        try {
            String json = JSON.writeValueAsString(clientConfig)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
            String assetVersion = "?v=" + HtmlUtils.htmlEscape(pluginContext.getVersion());
            add(model, factory, "\n<link rel=\"stylesheet\" href=\"" + ASSET_ROOT
                + "theme-roaming.css" + assetVersion + "\">\n");
            add(model, factory, "<script id=\"theme-roaming-config\" type=\"application/json\">" + json + "</script>\n");
            add(model, factory, "<script src=\"" + ASSET_ROOT + "theme-roaming.js"
                + assetVersion + "\" defer></script>\n");
        } catch (Exception error) {
            log.warn("[ThemeRoaming] Failed to serialize frontend config: {}", error.getMessage());
        }

    }

    private void add(IModel model, IModelFactory factory, String html) {
        model.add(factory.createText(html));
    }

    private ServerWebExchange exchange(ITemplateContext context) {
        Object reactorContext = context.getVariable("reactorContextView");
        if (!(reactorContext instanceof ContextView contextView)) {
            return null;
        }
        return ServerWebExchangeContextFilter.getExchange(contextView).orElse(null);
    }
}
