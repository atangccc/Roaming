package run.halo.roaming.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.Menu;
import run.halo.app.core.extension.MenuItem;
import run.halo.app.extension.Metadata;

class BoundMenuServiceTest {

    private final BoundMenuService service = new BoundMenuService(null, null);

    @Test
    void buildsLegacyMenuTreeWithoutLeakingItemsFromOtherMenus() {
        Menu menu = menu("menu-lumen", "home", "articles");
        MenuItem home = item("home", "首页", 0, "child");
        MenuItem child = item("child", "归档", 0);
        MenuItem articles = item("articles", "文章", 1);
        MenuItem outsider = item("outsider", "不属于当前菜单", 0);

        var result = service.build(menu, List.of(home, child, articles, outsider));

        assertThat(result.metadata().getName()).isEqualTo("menu-lumen");
        assertThat(result.menuItems()).extracting(BoundMenuService.MenuItemView::displayName)
            .containsExactly("首页", "文章");
        assertThat(result.menuItems().getFirst().children())
            .extracting(BoundMenuService.MenuItemView::displayName)
            .containsExactly("归档");
    }

    private Menu menu(String name, String... itemNames) {
        Menu menu = new Menu();
        menu.setMetadata(metadata(name));
        Menu.Spec spec = new Menu.Spec();
        spec.setDisplayName(name);
        spec.setMenuItems(set(itemNames));
        menu.setSpec(spec);
        return menu;
    }

    private MenuItem item(String name, String displayName, int priority, String... children) {
        MenuItem item = new MenuItem();
        item.setMetadata(metadata(name));
        MenuItem.MenuItemSpec spec = new MenuItem.MenuItemSpec();
        spec.setDisplayName(displayName);
        spec.setPriority(priority);
        spec.setChildren(set(children));
        item.setSpec(spec);
        MenuItem.MenuItemStatus status = new MenuItem.MenuItemStatus();
        status.setDisplayName(displayName);
        status.setHref("/" + name);
        item.setStatus(status);
        return item;
    }

    private Metadata metadata(String name) {
        Metadata metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        return metadata;
    }

    private LinkedHashSet<String> set(String... values) {
        var set = new LinkedHashSet<String>();
        java.util.Collections.addAll(set, values);
        return set;
    }
}
