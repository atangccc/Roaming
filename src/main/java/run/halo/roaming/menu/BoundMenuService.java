package run.halo.roaming.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Menu;
import run.halo.app.core.extension.MenuItem;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.roaming.infra.PluginJson;
import run.halo.app.theme.finders.vo.ExtensionVoOperator;
import run.halo.roaming.core.SystemThemeStateService;

@Service
@RequiredArgsConstructor
public class BoundMenuService {

    private static final ObjectMapper JSON = PluginJson.mapper();

    private final ReactiveExtensionClient client;
    private final SystemThemeStateService systemThemeStateService;

    public Mono<MenuView> getByName(String menuName) {
        if (StringUtils.isBlank(menuName)) {
            return Mono.empty();
        }
        Mono<Menu> menu = client.fetch(Menu.class, menuName);
        Mono<List<MenuItem>> items = listMenuItems().collectList();
        return Mono.zip(menu, items).map(tuple -> build(tuple.getT1(), tuple.getT2()));
    }

    public Mono<MenuView> getGlobalPrimary() {
        return systemThemeStateService.getState()
            .map(SystemThemeStateService.SystemThemeState::primaryMenuName)
            .filter(StringUtils::isNotBlank)
            .flatMap(this::getByName)
            .switchIfEmpty(firstMenu().flatMap(menu -> getByName(menu.getMetadata().getName())));
    }

    private Flux<Menu> listMenus() {
        return client.listAll(Menu.class, ListOptions.builder().build(), Sort.unsorted());
    }

    private Mono<Menu> firstMenu() {
        return listMenus().sort(Comparator.comparing(menu -> menu.getMetadata().getName())).next();
    }

    private Flux<MenuItem> listMenuItems() {
        return client.listAll(MenuItem.class, ListOptions.builder().build(), Sort.unsorted());
    }

    MenuView build(Menu menu, List<MenuItem> allItems) {
        String menuName = menu.getMetadata().getName();
        Map<String, MenuItem> itemsByName = new LinkedHashMap<>();
        allItems.forEach(item -> itemsByName.put(item.getMetadata().getName(), item));

        LinkedHashSet<String> members = resolveMembers(menuName, menu, itemsByName);
        Map<String, String> parents = resolveParents(members, itemsByName);
        List<MenuItemView> roots = members.stream()
            .filter(name -> StringUtils.isBlank(parents.get(name)) || !members.contains(parents.get(name)))
            .map(name -> buildItem(name, members, parents, itemsByName, new HashSet<>()))
            .filter(java.util.Objects::nonNull)
            .sorted(itemComparator())
            .toList();

        return new MenuView(menu.getMetadata(), menu.getSpec(), roots);
    }

    private LinkedHashSet<String> resolveMembers(
        String menuName,
        Menu menu,
        Map<String, MenuItem> itemsByName
    ) {
        LinkedHashSet<String> members = new LinkedHashSet<>();
        for (MenuItem item : itemsByName.values()) {
            JsonNode spec = json(item.getSpec());
            if (menuName.equals(spec.path("menuName").asText())) {
                members.add(item.getMetadata().getName());
            }
        }

        JsonNode legacyMenuItems = json(menu.getSpec()).path("menuItems");
        if (legacyMenuItems.isArray()) {
            legacyMenuItems.forEach(node -> collectLegacyMembers(node.asText(), itemsByName, members));
        }
        return members;
    }

    private void collectLegacyMembers(
        String itemName,
        Map<String, MenuItem> itemsByName,
        Set<String> members
    ) {
        if (StringUtils.isBlank(itemName) || !members.add(itemName)) {
            return;
        }
        MenuItem item = itemsByName.get(itemName);
        if (item == null) {
            return;
        }
        JsonNode children = json(item.getSpec()).path("children");
        if (children.isArray()) {
            children.forEach(node -> collectLegacyMembers(node.asText(), itemsByName, members));
        }
    }

    private Map<String, String> resolveParents(Set<String> members, Map<String, MenuItem> itemsByName) {
        Map<String, String> parents = new HashMap<>();
        for (String name : members) {
            MenuItem item = itemsByName.get(name);
            if (item == null) {
                continue;
            }
            String parent = json(item.getSpec()).path("parent").asText("");
            if (StringUtils.isNotBlank(parent)) {
                parents.put(name, parent);
            }
            JsonNode children = json(item.getSpec()).path("children");
            if (children.isArray()) {
                children.forEach(child -> parents.putIfAbsent(child.asText(), name));
            }
        }
        return parents;
    }

    private MenuItemView buildItem(
        String name,
        Set<String> members,
        Map<String, String> parents,
        Map<String, MenuItem> itemsByName,
        Set<String> ancestors
    ) {
        if (!ancestors.add(name)) {
            return null;
        }
        MenuItem item = itemsByName.get(name);
        if (item == null) {
            return null;
        }
        List<MenuItemView> children = members.stream()
            .filter(candidate -> name.equals(parents.get(candidate)))
            .map(candidate -> buildItem(candidate, members, parents, itemsByName, new HashSet<>(ancestors)))
            .filter(java.util.Objects::nonNull)
            .sorted(itemComparator())
            .toList();
        String statusName = item.getStatus() == null ? "" : item.getStatus().getDisplayName();
        String specName = item.getSpec() == null ? "" : item.getSpec().getDisplayName();
        return new MenuItemView(
            item.getMetadata(),
            item.getSpec(),
            item.getStatus(),
            children,
            parents.get(name),
            StringUtils.defaultIfBlank(statusName, specName),
            priority(item)
        );
    }

    private Comparator<MenuItemView> itemComparator() {
        return Comparator.comparingInt(MenuItemView::priority)
            .thenComparing(item -> item.metadata().getName());
    }

    private int priority(MenuItem item) {
        return json(item.getSpec()).path("priority").asInt(0);
    }

    private JsonNode json(Object value) {
        return value == null ? JSON.createObjectNode() : JSON.valueToTree(value);
    }

    public record MenuView(MetadataOperator metadata, Object spec, List<MenuItemView> menuItems) {
    }

    public record MenuItemView(
        MetadataOperator metadata,
        Object spec,
        Object status,
        List<MenuItemView> children,
        String parentName,
        String displayName,
        int priority
    ) implements ExtensionVoOperator {
        @Override
        public MetadataOperator getMetadata() {
            return metadata;
        }
    }
}
