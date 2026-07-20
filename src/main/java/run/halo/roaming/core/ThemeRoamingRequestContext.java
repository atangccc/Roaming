package run.halo.roaming.core;

public final class ThemeRoamingRequestContext {

    public static final String SELECTED_THEME_ATTRIBUTE =
        ThemeRoamingRequestContext.class.getName() + ".selectedTheme";
    public static final String BOUND_MENU_ATTRIBUTE =
        ThemeRoamingRequestContext.class.getName() + ".boundMenu";
    public static final String FRONTEND_ENABLED_ATTRIBUTE =
        ThemeRoamingRequestContext.class.getName() + ".frontendEnabled";

    private ThemeRoamingRequestContext() {
    }
}
