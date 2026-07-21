package serenity.roaming.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import serenity.roaming.config.ThemeRoamingSettings.ThemeBinding;

class ThemeRoamingSettingsTest {

    @Test
    void normalizesAndDeduplicatesBindings() {
        var settings = new ThemeRoamingSettings(
            "unknown",
            "unknown",
            "unknown",
            "unknown",
            List.of(
                new ThemeBinding(" theme-b ", " menu-b ", "", "", "", true, 20),
                new ThemeBinding("theme-a", "menu-a", "", "", "", true, 10),
                new ThemeBinding("theme-a", "menu-other", "", "", "", true, 30),
                new ThemeBinding("", "menu-invalid", "", "", "", true, 40)
            )
        ).normalized();

        assertThat(settings.hasEnabledBindings()).isTrue();
        assertThat(settings.persistence()).isEqualTo("30d");
        assertThat(settings.audience()).isEqualTo("everyone");
        assertThat(settings.entryPosition()).isEqualTo("auto");
        assertThat(settings.animation()).isEqualTo("fade");
        assertThat(settings.bindings()).extracting(ThemeBinding::themeName)
            .containsExactly("theme-a", "theme-b");
        assertThat(settings.findEnabledBinding("theme-a")).get()
            .extracting(ThemeBinding::menuName).isEqualTo("menu-a");
    }

    @Test
    void disabledBindingIsNotPubliclyResolved() {
        var settings = ThemeRoamingSettings.defaults();
        settings = new ThemeRoamingSettings(
            "session", "authenticated", "top-right", "paper",
            List.of(new ThemeBinding("theme-a", "menu-a", "", "", "", false, 0))
        ).normalized();

        assertThat(settings.findEnabledBinding("theme-a")).isEmpty();
        assertThat(settings.persistenceMode().maxAge()).isEmpty();
        assertThat(settings.audienceMode()).isEqualTo(ThemeRoamingSettings.Audience.AUTHENTICATED);
    }

}
