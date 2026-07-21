package serenity.roaming.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThemeRoamingSettings(
    String persistence,
    String audience,
    String entryPosition,
    String animation,
    List<ThemeBinding> bindings
) {
    public static final String GROUP = "roaming";

    public static ThemeRoamingSettings defaults() {
        return new ThemeRoamingSettings(
            Persistence.THIRTY_DAYS.value,
            Audience.EVERYONE.value,
            EntryPosition.AUTO.value,
            Animation.FADE.value,
            List.of()
        );
    }

    public ThemeRoamingSettings normalized() {
        Map<String, ThemeBinding> uniqueBindings = new LinkedHashMap<>();
        if (bindings != null) {
            bindings.stream()
                .map(ThemeBinding::normalized)
                .filter(ThemeBinding::valid)
                .sorted(Comparator.comparingInt(ThemeBinding::order).thenComparing(ThemeBinding::themeName))
                .forEach(binding -> uniqueBindings.putIfAbsent(binding.themeName(), binding));
        }
        return new ThemeRoamingSettings(
            Persistence.from(persistence).value,
            Audience.from(audience).value,
            EntryPosition.from(entryPosition).value,
            Animation.from(animation).value,
            List.copyOf(uniqueBindings.values())
        );
    }

    @JsonIgnore
    public Persistence persistenceMode() {
        return Persistence.from(persistence);
    }

    @JsonIgnore
    public Audience audienceMode() {
        return Audience.from(audience);
    }

    public Optional<ThemeBinding> findEnabledBinding(String themeName) {
        if (StringUtils.isBlank(themeName)) {
            return Optional.empty();
        }
        return bindings.stream()
            .filter(ThemeBinding::isEnabled)
            .filter(binding -> themeName.equals(binding.themeName()))
            .findFirst();
    }

    @JsonIgnore
    public boolean hasEnabledBindings() {
        return bindings.stream().anyMatch(ThemeBinding::isEnabled);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThemeBinding(
        String themeName,
        String menuName,
        String displayName,
        String description,
        String cover,
        Boolean enabled,
        int order
    ) {
        ThemeBinding normalized() {
            return new ThemeBinding(
                trim(themeName),
                trim(menuName),
                trim(displayName),
                trim(description),
                trim(cover),
                !Boolean.FALSE.equals(enabled),
                order
            );
        }

        boolean valid() {
            return !themeName.isBlank() && !menuName.isBlank();
        }

        @JsonIgnore
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

    }

    public enum Persistence {
        SESSION("session", null),
        SEVEN_DAYS("7d", Duration.ofDays(7)),
        THIRTY_DAYS("30d", Duration.ofDays(30));

        private final String value;
        private final Duration maxAge;

        Persistence(String value, Duration maxAge) {
            this.value = value;
            this.maxAge = maxAge;
        }

        public Optional<Duration> maxAge() {
            return Optional.ofNullable(maxAge);
        }

        static Persistence from(String raw) {
            return switch (normalize(raw)) {
                case "session" -> SESSION;
                case "7d" -> SEVEN_DAYS;
                default -> THIRTY_DAYS;
            };
        }
    }

    public enum Audience {
        EVERYONE("everyone"),
        AUTHENTICATED("authenticated");

        private final String value;

        Audience(String value) {
            this.value = value;
        }

        static Audience from(String raw) {
            return "authenticated".equals(normalize(raw)) ? AUTHENTICATED : EVERYONE;
        }
    }

    enum EntryPosition {
        AUTO("auto"), TOP_RIGHT("top-right"), BOTTOM_RIGHT("bottom-right");

        private final String value;

        EntryPosition(String value) {
            this.value = value;
        }

        static EntryPosition from(String raw) {
            return switch (normalize(raw)) {
                case "top-right" -> TOP_RIGHT;
                case "bottom-right" -> BOTTOM_RIGHT;
                default -> AUTO;
            };
        }
    }

    enum Animation {
        FADE("fade"),
        PAPER("paper"),
        SLIDE("slide"),
        BLUR("blur"),
        ZOOM("zoom"),
        NONE("none");

        private final String value;

        Animation(String value) {
            this.value = value;
        }

        static Animation from(String raw) {
            return switch (normalize(raw)) {
                case "paper" -> PAPER;
                case "slide" -> SLIDE;
                case "blur" -> BLUR;
                case "zoom" -> ZOOM;
                case "none" -> NONE;
                default -> FADE;
            };
        }
    }

    private static String trim(String raw) {
        return StringUtils.trimToEmpty(raw);
    }

    private static String normalize(String raw) {
        return trim(raw).toLowerCase(Locale.ROOT);
    }

}
