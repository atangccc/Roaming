package serenity.roaming.core;

import java.util.Comparator;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import serenity.roaming.infra.PluginJson;
import serenity.roaming.config.ThemeRoamingSettings;
import serenity.roaming.config.ThemeRoamingSettings.ThemeBinding;
import serenity.roaming.config.ThemeRoamingSettingsService;

@Service
@RequiredArgsConstructor
public class ThemeCatalogService {

    private static final ObjectMapper JSON = PluginJson.mapper();

    private final ReactiveExtensionClient client;
    private final ThemeRoamingSettingsService settingsService;

    public Mono<Boolean> isReady(String themeName) {
        return client.fetch(Theme.class, themeName)
            .map(this::ready)
            .onErrorReturn(false)
            .defaultIfEmpty(false);
    }

    public Mono<ThemeCatalog> publicCatalog() {
        return Mono.zip(settingsService.getSettings(), listThemes().collectList())
            .map(tuple -> buildCatalog(tuple.getT1(), tuple.getT2()));
    }

    private Flux<Theme> listThemes() {
        return client.listAll(Theme.class, ListOptions.builder().build(), Sort.unsorted());
    }

    private ThemeCatalog buildCatalog(ThemeRoamingSettings settings, List<Theme> installedThemes) {
        var themesByName = installedThemes.stream()
            .collect(java.util.stream.Collectors.toMap(theme -> theme.getMetadata().getName(), theme -> theme));

        List<ThemeOption> options = settings.bindings().stream()
            .filter(ThemeBinding::isEnabled)
            .map(binding -> toOption(binding, themesByName.get(binding.themeName())))
            .sorted(Comparator.comparingInt(ThemeOption::order).thenComparing(ThemeOption::themeName))
            .toList();
        return new ThemeCatalog(settings, options);
    }

    private ThemeOption toOption(ThemeBinding binding, Theme theme) {
        String themeDisplayName = theme != null && theme.getSpec() != null
            ? theme.getSpec().getDisplayName() : binding.themeName();
        String themeDescription = theme != null && theme.getSpec() != null
            ? theme.getSpec().getDescription() : "";
        String logo = theme != null && theme.getSpec() != null
            ? text(theme.getSpec().getLogo()) : "";
        String screenshot = theme != null && theme.getStatus() != null
            ? text(JSON.valueToTree(theme.getStatus()).path("screenshot").asText("")) : "";
        return new ThemeOption(
            binding.themeName(),
            binding.menuName(),
            binding.displayName().isBlank() ? themeDisplayName : binding.displayName(),
            binding.description().isBlank() ? themeDescription : binding.description(),
            firstNonBlank(binding.cover(), logo, screenshot),
            binding.order(),
            theme != null,
            theme != null && ready(theme),
            theme != null && theme.getSpec() != null ? theme.getSpec().getVersion() : ""
        );
    }

    private boolean ready(Theme theme) {
        return theme.getStatus() != null && Theme.ThemePhase.READY.equals(theme.getStatus().getPhase());
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String value = text(candidate);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record ThemeCatalog(ThemeRoamingSettings settings, List<ThemeOption> themes) {}

    public record ThemeOption(
        String themeName,
        String menuName,
        String displayName,
        String description,
        String cover,
        int order,
        boolean installed,
        boolean ready,
        String version
    ) {}
}
