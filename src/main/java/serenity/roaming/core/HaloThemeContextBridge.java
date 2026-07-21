package serenity.roaming.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.pf4j.PluginWrapper;
import reactor.core.publisher.Mono;

/**
 * A narrow compatibility bridge to Halo's request-scoped theme context.
 *
 * <p>Halo 2.25 keeps ThemeContext and ThemeResolver in its non-published application module,
 * although the renderer intentionally reads a ThemeContext stored on ServerWebExchange. Keeping
 * all reflective access in this class avoids leaking Halo internals through the plugin codebase.
 */
@Slf4j
@Component
public final class HaloThemeContextBridge {

    private static final String THEME_RESOLVER_BEAN = "themeResolver";
    private static final String THEME_CONTEXT_ATTRIBUTE = "run.halo.app.theme.ThemeContext";

    private final PluginWrapper pluginWrapper;
    private volatile Accessor accessor;

    public HaloThemeContextBridge(PluginWrapper pluginWrapper) {
        this.pluginWrapper = pluginWrapper;
    }

    public Mono<Object> createVisitorThemeContext(String themeName) {
        try {
            Accessor current = accessor();
            Object result = current.getThemeContext().invoke(current.resolver(), themeName);
            if (!(result instanceof Mono<?> contextMono)) {
                return Mono.error(new IllegalStateException("Halo ThemeResolver returned an unsupported value"));
            }
            return contextMono.cast(Object.class)
                .doOnNext(context -> setVirtuallyActive(current, context));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return Mono.error(new IllegalStateException(
                "Halo request theme API is incompatible with Theme Roaming", unwrap(error)));
        }
    }

    public void install(ServerWebExchange exchange, Object themeContext) {
        exchange.getAttributes().put(THEME_CONTEXT_ATTRIBUTE, themeContext);
    }

    private void setVirtuallyActive(Accessor current, Object themeContext) {
        try {
            current.setActive().invoke(themeContext, true);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unable to activate visitor theme context", unwrap(error));
        }
    }

    private Accessor accessor() throws ReflectiveOperationException {
        Accessor current = accessor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = accessor;
            if (current == null) {
                Object resolver = findThemeResolver();
                Method getThemeContext = resolver.getClass().getMethod("getThemeContext", String.class);
                Class<?> themeContextType = Class.forName(
                    THEME_CONTEXT_ATTRIBUTE, false, resolver.getClass().getClassLoader());
                Method setActive = themeContextType.getMethod("setActive", boolean.class);
                current = new Accessor(resolver, getThemeContext, setActive);
                accessor = current;
                log.info("[ThemeRoaming] Halo request theme bridge initialized");
            }
            return current;
        }
    }

    private Object findThemeResolver() throws ReflectiveOperationException {
        Object pluginManager = pluginWrapper.getPluginManager();
        Method getRootContext = pluginManager.getClass().getMethod("getRootContext");
        getRootContext.setAccessible(true);
        Object rootContext = getRootContext.invoke(pluginManager);
        if (rootContext instanceof ApplicationContext applicationContext
            && applicationContext.containsBean(THEME_RESOLVER_BEAN)) {
            return applicationContext.getBean(THEME_RESOLVER_BEAN);
        }
        throw new IllegalStateException("Halo ThemeResolver bean was not found");
    }

    private Throwable unwrap(Exception error) {
        return error instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause() : error;
    }

    private record Accessor(Object resolver, Method getThemeContext, Method setActive) {
    }
}
