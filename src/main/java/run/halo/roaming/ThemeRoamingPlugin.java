package run.halo.roaming;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public final class ThemeRoamingPlugin extends BasePlugin {

    public ThemeRoamingPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
