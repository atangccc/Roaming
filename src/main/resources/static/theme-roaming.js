(function themeRoamingBootstrap() {
  "use strict";

  const CONFIG_ID = "theme-roaming-config";
  const HOST_ID = "theme-roaming-root";
  const API_VERSION = "1.0";
  const COOKIE_NAME = "theme-roaming";
  const TRANSITION_KEY = "theme-roaming-transition";
  const SCRIPT = document.currentScript;
  const STYLESHEET_URL = (() => {
    if (!SCRIPT || !SCRIPT.src) return "/plugins/plugin-theme-roaming/assets/static/theme-roaming.css";
    const scriptUrl = new URL(SCRIPT.src);
    const stylesheetUrl = new URL("theme-roaming.css", scriptUrl);
    stylesheetUrl.search = scriptUrl.search;
    return stylesheetUrl.href;
  })();

  const state = {
    config: readConfig(),
    catalog: null,
    open: false,
    loading: true,
    error: "",
    host: null,
    shadow: null,
    trigger: null,
    panel: null,
    lastFocused: null,
    placementObserver: null,
    manualAnchor: null,
  };

  if (!state.config) {
    return;
  }

  const existingHost = document.getElementById(HOST_ID);
  if (existingHost && window.ThemeRoaming) {
    window.ThemeRoaming.refresh();
    return;
  }
  if (existingHost) existingHost.remove();

  prepareEnteringTransition();
  installIntegrationApi();
  installDeclarativeControls();
  installDismissHandler();
  installFreshnessHandlers();
  loadCatalog();

  function ensureMounted() {
    if (state.host && state.host.isConnected) return;
    mount();
    installAutoPlacement();
  }

  function unmount() {
    if (state.placementObserver) {
      state.placementObserver.disconnect();
      state.placementObserver = null;
    }
    if (state.host) state.host.remove();
    state.host = null;
    state.shadow = null;
    state.trigger = null;
    state.panel = null;
    state.open = false;
  }

  function readConfig() {
    const node = document.getElementById(CONFIG_ID);
    if (!node) return null;
    try {
      const parsed = JSON.parse(node.textContent || "{}");
      return Object.freeze({
        catalogEndpoint: String(parsed.catalogEndpoint || ""),
        selectedTheme: String(parsed.selectedTheme || ""),
        persistence: String(parsed.persistence || "30d"),
        entryPosition: String(parsed.entryPosition || "auto"),
        animation: String(parsed.animation || "fade"),
      });
    } catch (error) {
      console.warn("[ThemeRoaming] Invalid client configuration.", error);
      return null;
    }
  }

  function mount() {
    const host = document.createElement("div");
    host.id = HOST_ID;
    host.dataset.position = state.config.entryPosition;
    const shadow = host.attachShadow({ mode: "open" });
    const stylesheet = document.createElement("link");
    stylesheet.rel = "stylesheet";
    stylesheet.href = STYLESHEET_URL;
    shadow.appendChild(stylesheet);
    document.body.appendChild(host);
    state.host = host;
    state.shadow = shadow;
    render();
  }

  function installAutoPlacement() {
    if (state.config.entryPosition !== "auto" || !state.host) return;
    if (state.placementObserver) state.placementObserver.disconnect();
    placeHost();
    const observer = new MutationObserver(() => placeHost());
    observer.observe(document.body, { childList: true, subtree: true });
    state.placementObserver = observer;
    window.addEventListener("pagehide", () => {
      observer.disconnect();
      if (state.placementObserver === observer) state.placementObserver = null;
    }, { once: true });
  }

  function placeHost() {
    if (!state.host) return false;
    const explicitAnchor = state.manualAnchor || document.querySelector("[data-theme-roaming-anchor]");
    if (explicitAnchor) {
      if (state.host.parentElement !== explicitAnchor) explicitAnchor.appendChild(state.host);
      state.host.dataset.embedded = "true";
      return true;
    }

    const lumenActions = document.querySelector(".header-actions");
    const lumenThemeButton = lumenActions && lumenActions.querySelector("button.round:last-of-type");
    const graceThemeButton = document.querySelector(".header-right #theme-toggle");
    const anchor = lumenThemeButton || graceThemeButton;
    if (!anchor || !anchor.parentElement) return false;
    if (state.host.parentElement !== anchor.parentElement || state.host.nextSibling !== anchor) {
      anchor.parentElement.insertBefore(state.host, anchor);
    }
    state.host.dataset.embedded = "true";
    return true;
  }

  function installIntegrationApi() {
    const api = Object.freeze({
      version: API_VERSION,
      open: () => openPanel(),
      close: () => setOpen(false),
      toggle: () => state.open ? setOpen(false) : openPanel(),
      refresh: () => refreshCatalog(),
      select: (themeName) => selectTheme(themeName),
      getState: () => publicState(),
      mount: (anchor) => mountAt(anchor),
    });
    Object.defineProperty(window, "ThemeRoaming", {
      configurable: true,
      enumerable: false,
      value: api,
      writable: false,
    });
  }

  function installDeclarativeControls() {
    const handleClick = (event) => {
      if (!(event.target instanceof Element)) return;
      const selection = event.target.closest("[data-theme-roaming-select]");
      if (selection) {
        event.preventDefault();
        selectTheme(selection.getAttribute("data-theme-roaming-select"));
        return;
      }
      const opener = event.target.closest("[data-theme-roaming-open]");
      if (opener) {
        event.preventDefault();
        openPanel();
      }
    };
    document.addEventListener("click", handleClick);
    window.addEventListener("pagehide", () => {
      document.removeEventListener("click", handleClick);
    }, { once: true });
  }

  function mountAt(anchor) {
    const target = typeof anchor === "string" ? document.querySelector(anchor) : anchor;
    if (!(target instanceof Element)) return false;
    state.manualAnchor = target;
    ensureMounted();
    placeHost();
    return true;
  }

  function openPanel() {
    state.loading = !state.catalog;
    state.error = "";
    ensureMounted();
    setOpen(true);
    return loadCatalog();
  }

  function refreshCatalog() {
    state.loading = true;
    state.error = "";
    render();
    return loadCatalog();
  }

  function installDismissHandler() {
    const dismiss = (event) => {
      if (!state.open || !state.host || event.composedPath().includes(state.host)) return;
      setOpen(false);
    };
    document.addEventListener("pointerdown", dismiss, true);
    window.addEventListener("pagehide", () => document.removeEventListener("pointerdown", dismiss, true), { once: true });
  }

  function installFreshnessHandlers() {
    const refresh = () => {
      if (!document.hidden) loadCatalog();
    };
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    window.addEventListener("pagehide", () => {
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    }, { once: true });
  }

  async function loadCatalog() {
    if (!state.config.catalogEndpoint) {
      state.loading = false;
      state.error = "主题目录不可用";
      render();
      return;
    }
    try {
      const endpoint = new URL(state.config.catalogEndpoint, window.location.origin);
      endpoint.searchParams.set("_", String(Date.now()));
      const response = await fetch(endpoint.href, {
        credentials: "same-origin",
        headers: { Accept: "application/json", "Cache-Control": "no-cache" },
        cache: "no-store",
      });
      if (!response.ok) throw new Error("HTTP " + response.status);
      const catalog = await response.json();
      state.catalog = normalizeCatalog(catalog);
      state.error = "";
      emit("catalog", publicState());
    } catch (error) {
      state.error = "暂时无法读取主题列表";
      console.warn("[ThemeRoaming] Catalog request failed.", error);
    } finally {
      state.loading = false;
      const hasThemes = Boolean(
        state.catalog && state.catalog.available && state.catalog.themes.length
      );
      if (state.error || hasThemes) {
        ensureMounted();
        render();
      } else {
        unmount();
      }
      emit("statechange", publicState());
    }
  }

  function normalizeCatalog(raw) {
    const themes = Array.isArray(raw && raw.themes) ? raw.themes : [];
    return {
      apiVersion: String((raw && raw.apiVersion) || "v1alpha1"),
      available: raw && raw.available !== false,
      activeTheme: String((raw && raw.activeTheme) || ""),
      themes: themes
        .filter((theme) => theme && theme.installed && theme.ready)
        .map((theme) => ({
          themeName: String(theme.themeName || ""),
          displayName: String(theme.displayName || theme.themeName || "未命名主题"),
          description: String(theme.description || ""),
          cover: safeAssetUrl(theme.cover) || conventionalThemeLogo(theme.themeName),
          version: String(theme.version || ""),
          order: Number.isFinite(Number(theme.order)) ? Number(theme.order) : 0,
        }))
        .filter((theme) => theme.themeName)
        .sort((a, b) => a.order - b.order || a.displayName.localeCompare(b.displayName, "zh-CN")),
    };
  }

  function safeAssetUrl(value) {
    const source = String(value || "").trim();
    if (!source) return "";
    try {
      const url = new URL(source, window.location.origin);
      return ["http:", "https:", "data:"].includes(url.protocol) ? url.href : "";
    } catch (_error) {
      return "";
    }
  }

  function conventionalThemeLogo(themeName) {
    const name = String(themeName || "").trim();
    return name ? "/themes/" + encodeURIComponent(name) + "/assets/theme-logo.svg" : "";
  }

  function render() {
    const shadow = state.shadow;
    if (!shadow) return;
    Array.from(shadow.querySelectorAll(".tr-app")).forEach((node) => node.remove());

    const app = element("div", "tr-app");
    const nativeTriggerHidden = document.documentElement.dataset.themeRoamingTrigger === "hidden";
    const trigger = nativeTriggerHidden ? null : createTrigger();
    if (trigger) app.appendChild(trigger);
    if (state.open) app.appendChild(createPanel());
    shadow.appendChild(app);
    state.trigger = trigger;
    state.panel = shadow.querySelector(".tr-panel");
    if (state.open) queueMicrotask(() => focusPanel());
  }

  function createTrigger() {
    const button = element("button", "tr-trigger");
    button.type = "button";
    button.setAttribute("aria-haspopup", "dialog");
    button.setAttribute("aria-expanded", String(state.open));
    button.setAttribute("aria-label", "切换站点主题");
    button.title = currentThemeLabel();
    button.innerHTML = paletteIcon();
    button.addEventListener("click", () => {
      if (state.open) setOpen(false);
      else openPanel();
    });
    return button;
  }

  function createPanel() {
    const panel = element("section", "tr-panel");
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-modal", "false");
    panel.setAttribute("aria-label", "主题漫游");
    panel.tabIndex = -1;
    panel.addEventListener("keydown", handlePanelKeydown);

    const content = element("div", "tr-panel__content");
    if (state.loading) {
      content.appendChild(createLoading());
    } else if (state.error) {
      content.appendChild(createMessage(state.error, true));
    } else if (!state.catalog || !state.catalog.available) {
      content.appendChild(createMessage("站点暂未开放主题漫游", false));
    } else {
      const list = element("div", "tr-theme-list");
      state.catalog.themes.forEach((theme) => list.appendChild(createThemeCard(theme)));
      if (!state.catalog.themes.length) {
        list.appendChild(createMessage("还没有开放可漫游的主题", false));
      }
      content.appendChild(list);
    }
    panel.appendChild(content);
    return panel;
  }

  function createThemeCard(theme) {
    const selected = currentThemeName() === theme.themeName;
    const button = element("button", selected ? "tr-card is-selected" : "tr-card");
    button.type = "button";
    button.disabled = selected;
    button.setAttribute("aria-current", selected ? "true" : "false");
    button.addEventListener("click", () => selectTheme(theme.themeName));

    const preview = element("span", "tr-card__preview");
    if (theme.cover) {
      const image = document.createElement("img");
      image.src = theme.cover;
      image.alt = "";
      image.loading = "lazy";
      image.decoding = "async";
      image.addEventListener("error", () => {
        preview.replaceChildren(element("span", "tr-card__monogram", fallbackAvatarLabel(theme.displayName)));
      }, { once: true });
      preview.appendChild(image);
    } else {
      preview.appendChild(element("span", "tr-card__monogram", fallbackAvatarLabel(theme.displayName)));
    }

    const copy = element("span", "tr-card__copy");
    copy.appendChild(element("strong", "tr-card__name", theme.displayName));
    copy.appendChild(element(
      "span",
      "tr-card__description",
      theme.description || "切换到这个主题继续浏览"
    ));

    const status = element("span", "tr-card__status");
    status.innerHTML = selected ? checkIcon() : "";
    status.setAttribute("aria-hidden", "true");
    button.append(preview, copy, status);
    return button;
  }

  function createLoading() {
    const node = element("div", "tr-loading");
    node.innerHTML = '<span></span><span></span><span></span><em>正在读取主题</em>';
    return node;
  }

  function createMessage(message, retryable) {
    const node = element("div", "tr-message");
    node.appendChild(element("span", "tr-message__icon", infoIcon(), true));
    node.appendChild(element("p", "", message));
    if (retryable) {
      const retry = element("button", "tr-retry", "重新加载");
      retry.type = "button";
      retry.addEventListener("click", () => {
        state.loading = true;
        state.error = "";
        render();
        loadCatalog();
      });
      node.appendChild(retry);
    }
    return node;
  }

  function setOpen(open) {
    state.open = Boolean(open);
    if (state.open) state.lastFocused = document.activeElement;
    render();
    if (!state.open) {
      const focusTarget = state.trigger || state.lastFocused;
      if (focusTarget && typeof focusTarget.focus === "function") {
        focusTarget.focus({ preventScroll: true });
      }
    }
    emit("statechange", publicState());
  }

  function focusPanel() {
    if (state.panel) state.panel.focus({ preventScroll: true });
  }

  function handlePanelKeydown(event) {
    if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      return;
    }
    if (event.key !== "Tab" || !state.panel) return;
    const focusable = Array.from(state.panel.querySelectorAll("button:not([disabled])"));
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (state.shadow.activeElement === state.panel || state.shadow.activeElement === first)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && state.shadow.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  async function selectTheme(themeName) {
    const candidate = String(themeName || "").trim();
    if (!state.catalog) await loadCatalog();
    const allowed = state.catalog && state.catalog.themes.some((theme) => theme.themeName === candidate);
    if (!candidate || !allowed) {
      emit("error", { code: "THEME_NOT_AVAILABLE", themeName: candidate });
      return false;
    }
    if (candidate === currentThemeName()) return true;
    if (!emit("before-switch", { from: currentThemeName(), to: candidate }, true)) return false;
    persistSelectionCookie(candidate);
    const target = new URL(window.location.href);
    beginTransition(target.href);
    return true;
  }

  function beginTransition(target) {
    setOpen(false);
    if (state.config.animation === "none" || prefersReducedMotion()) {
      window.location.assign(target);
      return;
    }
    try { sessionStorage.setItem(TRANSITION_KEY, state.config.animation); } catch (_error) {}
    document.documentElement.dataset.themeRoamingTransition = state.config.animation;
    document.documentElement.classList.add("theme-roaming-leave");
    window.setTimeout(() => window.location.assign(target), 190);
  }

  function prepareEnteringTransition() {
    let animation = "";
    try {
      animation = sessionStorage.getItem(TRANSITION_KEY) || "";
      sessionStorage.removeItem(TRANSITION_KEY);
    } catch (_error) {}
    if (!animation || animation === "none" || prefersReducedMotion()) return;
    document.documentElement.dataset.themeRoamingTransition = animation;
    document.documentElement.classList.add("theme-roaming-enter");
    requestAnimationFrame(() => requestAnimationFrame(() => {
      document.documentElement.classList.remove("theme-roaming-enter");
    }));
  }

  function currentThemeName() {
    return state.config.selectedTheme || (state.catalog && state.catalog.activeTheme) || "";
  }

  function currentThemeLabel() {
    const current = currentThemeName();
    const theme = state.catalog && state.catalog.themes.find((item) => item.themeName === current);
    return theme ? "当前主题：" + theme.displayName : "主题漫游";
  }

  function publicState() {
    const themes = state.catalog ? state.catalog.themes.map((theme) => ({ ...theme })) : [];
    return Object.freeze({
      available: Boolean(state.catalog && state.catalog.available && themes.length),
      currentTheme: currentThemeName(),
      activeTheme: state.catalog ? state.catalog.activeTheme : "",
      open: state.open,
      loading: state.loading,
      error: state.error,
      themes,
    });
  }

  function emit(name, detail, cancelable) {
    return window.dispatchEvent(new CustomEvent("theme-roaming:" + name, {
      cancelable: Boolean(cancelable),
      detail,
    }));
  }

  function persistSelectionCookie(themeName) {
    const maxAges = { "7d": 604800, "30d": 2592000 };
    let cookie = COOKIE_NAME + "=" + encodeURIComponent(themeName) + "; Path=/; SameSite=Lax";
    if (maxAges[state.config.persistence]) cookie += "; Max-Age=" + maxAges[state.config.persistence];
    if (window.location.protocol === "https:") cookie += "; Secure";
    document.cookie = cookie;
  }

  function prefersReducedMotion() {
    return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  function fallbackAvatarLabel(displayName) {
    const words = String(displayName || "主题").trim().split(/[\s_-]+/).filter(Boolean);
    if (!words.length) return "主";
    if (words.length === 1) return Array.from(words[0]).slice(0, 2).join("").toUpperCase();
    return (Array.from(words[0])[0] + Array.from(words[words.length - 1])[0]).toUpperCase();
  }

  function element(tag, className, content, html) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) {
      if (html) node.innerHTML = content;
      else node.textContent = content;
    }
    return node;
  }

  function paletteIcon() {
    return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.2a8.8 8.8 0 1 0 0 17.6h1.25a1.65 1.65 0 0 0 0-3.3h-.7a1.4 1.4 0 0 1 0-2.8h1.95a6.7 6.7 0 0 0 0-13.4H12Z"/><circle cx="7.7" cy="10.4" r="1"/><circle cx="10" cy="6.9" r="1"/><circle cx="14.2" cy="6.8" r="1"/><circle cx="17" cy="10" r="1"/></svg>';
  }
  function checkIcon() { return '<svg viewBox="0 0 20 20"><path d="m5.2 10.2 3.1 3.1 6.5-6.7"/></svg>'; }
  function infoIcon() { return '<svg viewBox="0 0 20 20"><circle cx="10" cy="10" r="7"/><path d="M10 9v4m0-7.2v.1"/></svg>'; }
})();
