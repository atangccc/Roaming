<script setup lang="ts">
import type { Menu, Theme } from "@halo-dev/api-client";
import { consoleApiClient, coreApiClient } from "@halo-dev/api-client";
import { Toast } from "@halo-dev/components";
import { computed, onMounted, reactive, ref, watch } from "vue";
import RoamingSelect from "../components/RoamingSelect.vue";

const PLUGIN_NAME = "plugin-theme-roaming";
type Persistence = "session" | "7d" | "30d";
type Audience = "everyone" | "authenticated";
type EntryPosition = "auto" | "top-right" | "bottom-right";
type Animation = "fade" | "paper" | "slide" | "blur" | "zoom" | "none";
type ViewName = "bindings" | "experience";


interface ThemeBinding {
  themeName: string;
  menuName: string;
  displayName: string;
  description: string;
  cover: string;
  enabled: boolean;
  order: number;
}

interface RoamingConfig {
  persistence: Persistence;
  audience: Audience;
  entryPosition: EntryPosition;
  animation: Animation;
  bindings: ThemeBinding[];
}

interface SelectOption { label: string; value: string }

const defaults = (): RoamingConfig => ({
  persistence: "30d",
  audience: "everyone",
  entryPosition: "auto",
  animation: "fade",
  bindings: [],
});

const loading = ref(true);
const saving = ref(false);
const activeView = ref<ViewName>("bindings");
const themes = ref<Theme[]>([]);
const menus = ref<Menu[]>([]);
const rawConfig = ref<Record<string, unknown>>({});
const form = reactive<RoamingConfig>(defaults());
const failedThemeCovers = reactive(new Set<string>());
const autosaveReady = ref(false);
let lastSavedSignature = "";
let saveTimer: ReturnType<typeof setTimeout> | undefined;

const sortedThemes = computed(() => [...themes.value].sort((a, b) =>
  themeLabel(a).localeCompare(themeLabel(b), "zh-CN")));
const sortedMenus = computed(() => [...menus.value].sort((a, b) =>
  menuLabel(a).localeCompare(menuLabel(b), "zh-CN")));
const openCount = computed(() => form.bindings.filter((item) => item.enabled).length);
const pageContext = computed(() => ({
  bindings: { title: "主题绑定", description: "" },
  experience: { title: "漫游配置", description: "配置主题切换入口与访客体验" },
})[activeView.value]);
const menuOptions = computed<SelectOption[]>(() => sortedMenus.value.map((menu) => ({ label: `${menuLabel(menu)} · ${menuCount(menu.metadata.name)} 项`, value: menu.metadata.name })));
const persistenceOptions: SelectOption[] = [{ value: "session", label: "本次会话" }, { value: "7d", label: "7 天" }, { value: "30d", label: "30 天" }];
const audienceOptions: SelectOption[] = [{ value: "everyone", label: "所有访客" }, { value: "authenticated", label: "仅登录用户" }];
const positionOptions: SelectOption[] = [{ value: "auto", label: "自动适配主题" }, { value: "top-right", label: "页面右上角" }, { value: "bottom-right", label: "页面右下角" }];
const animationOptions: SelectOption[] = [
  { value: "fade", label: "淡入淡出" },
  { value: "paper", label: "纸页过渡" },
  { value: "slide", label: "轻盈横移" },
  { value: "blur", label: "柔焦切换" },
  { value: "zoom", label: "轻微缩放" },
  { value: "none", label: "无动画" },
];

const validationErrors = computed(() => {
  const errors: string[] = [];
  const themeNames = new Set<string>();
  form.bindings.forEach((binding, index) => {
    const row = index + 1;
    if (!binding.themeName || !binding.menuName) return;
    if (binding.themeName && themeNames.has(binding.themeName)) {
      errors.push(`主题“${themeNameLabel(binding.themeName)}”被重复绑定`);
    }
    themeNames.add(binding.themeName);
    if (binding.themeName && !themes.value.some((item) => item.metadata.name === binding.themeName)) {
      errors.push(`第 ${row} 项绑定的主题已不存在`);
    }
    if (binding.menuName && !menus.value.some((item) => item.metadata.name === binding.menuName)) {
      errors.push(`第 ${row} 项绑定的菜单已不存在`);
    }
  });
  return errors;
});

onMounted(load);
watch(form, scheduleAutoSave, { deep: true });

async function load() {
  autosaveReady.value = false;
  loading.value = true;
  let shouldPersistReconciledBindings = false;
  try {
    const [themeResponse, menuResponse, configResponse] = await Promise.all([
      consoleApiClient.theme.theme.listThemes({ page: 0, size: 500 }),
      coreApiClient.menu.listMenu({ page: 0, size: 500 }),
      consoleApiClient.plugin.plugin.fetchPluginJsonConfig({ name: PLUGIN_NAME }),
    ]);
    themes.value = themeResponse.data.items || [];
    menus.value = menuResponse.data.items || [];
    rawConfig.value = clone(configResponse.data || {});
    const storedRoaming = (configResponse.data as Record<string, unknown>)?.roaming;
    const supportedFields = new Set(["persistence", "audience", "entryPosition", "animation", "bindings"]);
    const hasLegacyFields = Boolean(storedRoaming && typeof storedRoaming === "object"
      && Object.keys(storedRoaming).some((field) => !supportedFields.has(field)));
    applyConfig(storedRoaming);
    lastSavedSignature = configSignature(persistableConfig());
    synchronizeInstalledThemes();
    shouldPersistReconciledBindings = hasLegacyFields
      || configSignature(persistableConfig()) !== lastSavedSignature;
  } catch (error) {
    console.error(error);
    Toast.error("主题漫游配置加载失败");
  } finally {
    loading.value = false;
    autosaveReady.value = true;
    if (shouldPersistReconciledBindings) scheduleAutoSave();
  }
}

function synchronizeInstalledThemes() {
  const existingBindings = new Map(form.bindings.map((binding) => [binding.themeName, binding]));
  const defaultMenuName = sortedMenus.value[0]?.metadata.name || "";
  const synchronized = sortedThemes.value.map((theme, order) => {
    const existing = existingBindings.get(theme.metadata.name);
    return existing
      ? { ...existing, order }
      : {
          themeName: theme.metadata.name,
          menuName: defaultMenuName,
          displayName: "",
          description: "",
          cover: "",
          enabled: false,
          order,
        };
  });
  form.bindings.splice(0, form.bindings.length, ...synchronized);
}

function applyConfig(raw: unknown) {
  const source = raw && typeof raw === "object" ? raw as Partial<RoamingConfig> : {};
  const normalized = defaults();
  if (source.persistence) normalized.persistence = source.persistence;
  if (source.audience) normalized.audience = source.audience;
  if (source.entryPosition) normalized.entryPosition = source.entryPosition;
  if (source.animation) normalized.animation = source.animation;
  normalized.bindings = Array.isArray(source.bindings)
    ? source.bindings
        .map((binding, index) => normalizeBinding(binding, index))
        .filter((binding) => binding.themeName && binding.menuName)
        .map((binding, order) => ({ ...binding, order }))
    : [];
  Object.assign(form, normalized);
}

function normalizeBinding(raw: unknown, index: number): ThemeBinding {
  const binding = raw && typeof raw === "object" ? raw as Partial<ThemeBinding> : {};
  return {
    themeName: String(binding.themeName || ""),
    menuName: String(binding.menuName || ""),
    displayName: String(binding.displayName || ""),
    description: String(binding.description || ""),
    cover: String(binding.cover || ""),
    enabled: binding.enabled !== false,
    order: index,
  };
}

function persistableConfig(): RoamingConfig {
  const config = clone(form);
  config.bindings = config.bindings
    .filter((binding) => binding.themeName && binding.menuName)
    .map((binding, order) => ({ ...binding, order }));
  return config;
}

function configSignature(config: RoamingConfig) {
  return JSON.stringify(config);
}

function scheduleAutoSave() {
  if (!autosaveReady.value) return;
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(save, 550);
}

function saveImmediately() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(save, 0);
}

async function save() {
  const roaming = persistableConfig();
  const signature = configSignature(roaming);
  if (signature === lastSavedSignature) return;
  if (validationErrors.value.length) {
    Toast.error(validationErrors.value[0]);
    return;
  }
  if (saving.value) {
    scheduleAutoSave();
    return;
  }
  saving.value = true;
  try {
    const payload = clone(rawConfig.value);
    payload.roaming = roaming;
    await consoleApiClient.plugin.plugin.updatePluginJsonConfig({ name: PLUGIN_NAME, body: payload });
    rawConfig.value = payload;
    lastSavedSignature = signature;
  } catch (error) {
    console.error(error);
    Toast.error("保存失败，请稍后重试");
  } finally {
    saving.value = false;
    if (configSignature(persistableConfig()) !== lastSavedSignature) scheduleAutoSave();
  }
}

function themeLabel(theme: Theme) { return theme.spec?.displayName || theme.metadata.name; }
function themeNameLabel(name: string) {
  const theme = themes.value.find((item) => item.metadata.name === name);
  return theme ? themeLabel(theme) : name;
}
function menuLabel(menu: Menu) { return menu.spec?.displayName || menu.metadata.name; }
function menuCount(menuName: string) {
  const menu = menus.value.find((item) => item.metadata.name === menuName);
  const names = (menu?.spec as { menuItems?: string[] } | undefined)?.menuItems;
  return Array.isArray(names) ? names.length : 0;
}
function themeReady(name: string) {
  const theme = themes.value.find((item) => item.metadata.name === name);
  return String(theme?.status?.phase || "").toUpperCase() === "READY";
}
function themeInfo(name: string) {
  const theme = themes.value.find((item) => item.metadata.name === name);
  const spec = theme?.spec as (Theme["spec"] & {
    author?: { name?: string };
    description?: string;
    logo?: string;
    version?: string;
  }) | undefined;
  const status = theme?.status as (Theme["status"] & { screenshot?: string }) | undefined;
  return {
    author: spec?.author?.name || "未知作者",
    cover: failedThemeCovers.has(name)
      ? ""
      : spec?.logo || status?.screenshot || `/themes/${encodeURIComponent(name)}/assets/theme-logo.svg`,
    description: spec?.description || "暂无主题说明",
    version: spec?.version || "-",
  };
}
function markThemeCoverFailed(name: string) { failedThemeCovers.add(name); }
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value ?? {})); }
</script>

<template>
  <div class="tr-shell">
    <div class="tr-frame">
      <i class="tr-corner tr-corner-tl"></i><i class="tr-corner tr-corner-tr"></i>
      <i class="tr-corner tr-corner-bl"></i><i class="tr-corner tr-corner-br"></i>

      <nav class="tr-float-nav" aria-label="主题漫游导航">
        <button :class="{ active: activeView === 'bindings' }" type="button" title="主题绑定" aria-label="主题绑定" @click="activeView = 'bindings'">
          <svg viewBox="0 0 24 24"><rect x="3" y="4" width="7" height="7" rx="2"/><rect x="14" y="4" width="7" height="7" rx="2"/><rect x="3" y="15" width="7" height="6" rx="2"/><path d="M14 18h7M17.5 14.5v7"/></svg>
          <span v-if="form.bindings.length" class="tr-nav-badge">{{ form.bindings.length > 99 ? '99+' : form.bindings.length }}</span>
        </button>
        <button :class="{ active: activeView === 'experience' }" type="button" title="漫游配置" aria-label="漫游配置" @click="activeView = 'experience'">
          <svg viewBox="0 0 24 24"><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/></svg>
        </button>
      </nav>

      <header class="tr-topbar">
        <div class="tr-brand">
          <span class="tr-brand-mark" aria-hidden="true">
            <svg viewBox="0 0 1024 1024"><rect x="72" y="72" width="880" height="880" rx="224" fill="#f3f5f5" stroke="#292d30" stroke-width="24"/><rect x="224" y="246" width="500" height="382" rx="88" fill="#dce5e7" stroke="#292d30" stroke-width="20" transform="rotate(-6 474 437)"/><rect x="296" y="326" width="500" height="382" rx="88" fill="#fff" stroke="#292d30" stroke-width="20"/><circle cx="390" cy="420" r="24" fill="#e87870" stroke="#292d30" stroke-width="10"/><circle cx="458" cy="420" r="24" fill="#e4b758" stroke="#292d30" stroke-width="10"/><circle cx="526" cy="420" r="24" fill="#62aa9d" stroke="#292d30" stroke-width="10"/><path d="M390 520h302l-54-50m54 50-54 50M704 618H402l54 50m-54-50 54-50" fill="none" stroke="#292d30" stroke-width="24" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
          <strong>主题<span>漫游</span></strong>
        </div>

        <div class="tr-page-context">
          <strong>{{ pageContext.title }}</strong>
          <span v-if="pageContext.description">{{ pageContext.description }}</span>
        </div>

        <div class="tr-actions">
          <span v-if="activeView === 'bindings'" class="tr-open-count">{{ openCount }} 个已开放</span>
          <button class="tr-icon-button" type="button" title="重新读取" :disabled="loading || saving" @click="load">
            <svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/></svg>
          </button>
        </div>
      </header>

      <section class="tr-workspace">
        <div v-if="loading" class="tr-empty"><span class="tr-spinner"></span>正在同步主题与菜单…</div>

        <main v-else-if="activeView === 'bindings'" class="tr-content">
          <div v-if="!form.bindings.length" class="tr-empty"><b>还没有主题绑定</b><span>新增后直接打开“前台开放”即可。</span></div>
          <div v-else class="tr-binding-list">
            <article v-for="(binding, index) in form.bindings" :key="`${binding.themeName}-${index}`" class="tr-binding-row">
              <div class="tr-binding-theme">
                <div class="tr-binding-cover" :class="{ empty: !themeInfo(binding.themeName).cover }">
                  <img v-if="themeInfo(binding.themeName).cover" :src="themeInfo(binding.themeName).cover" alt="" @error="markThemeCoverFailed(binding.themeName)">
                  <svg v-else viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M7 9h7M7 13h10M7 17h5"/></svg>
                  <b>{{ String(index + 1).padStart(2, '0') }}</b>
                </div>
                <div class="tr-binding-summary">
                  <strong>{{ themeNameLabel(binding.themeName) || '未选择主题' }}</strong>
                  <p>{{ themeInfo(binding.themeName).description }}</p>
                  <span><i :class="themeReady(binding.themeName) ? 'ready' : 'pending'"></i>v{{ themeInfo(binding.themeName).version }} · {{ themeInfo(binding.themeName).author }}</span>
                </div>
              </div>
              <div class="tr-binding-fields">
                <label><span>已安装主题</span><span class="tr-readonly-field"><i></i><strong>{{ themeNameLabel(binding.themeName) }}</strong></span></label>
                <div class="tr-binding-link"><i></i><svg viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg><i></i></div>
                <label><span>对应菜单 · {{ menuCount(binding.menuName) }} 项</span><RoamingSelect v-model="binding.menuName" :options="menuOptions" placeholder="选择菜单" /></label>
              </div>
              <div class="tr-binding-actions">
                <label class="tr-toggle"><input v-model="binding.enabled" type="checkbox" @change="saveImmediately"><i></i><span>{{ binding.enabled ? '前台开放' : '暂不开放' }}</span></label>
              </div>
            </article>
          </div>

        </main>

        <main v-else class="tr-content">
          <div class="tr-settings">
            <label><span class="tr-setting-icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="7.5"/><path d="M12 8v4.4l3 1.8M8.2 3.8 5.8 6.2M15.8 3.8l2.4 2.4"/></svg></span><div><strong>记住选择</strong><small>访客主题的保存时间</small></div><RoamingSelect v-model="form.persistence" :options="persistenceOptions" /></label>
            <label><span class="tr-setting-icon"><svg viewBox="0 0 24 24"><circle cx="9" cy="9" r="3"/><path d="M3.8 19c.5-3.2 2.2-5 5.2-5s4.7 1.8 5.2 5M15 7.2a2.7 2.7 0 0 1 0 5.3M16.2 14.3c2.3.5 3.6 2.1 4 4.7"/></svg></span><div><strong>开放范围</strong><small>谁可以使用主题切换</small></div><RoamingSelect v-model="form.audience" :options="audienceOptions" /></label>
            <label><span class="tr-setting-icon"><svg viewBox="0 0 24 24"><path d="M8 4H5a1 1 0 0 0-1 1v3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3"/><circle cx="12" cy="12" r="2.5"/></svg></span><div><strong>入口位置</strong><small>切换按钮的页面位置</small></div><RoamingSelect v-model="form.entryPosition" :options="positionOptions" /></label>
            <label><span class="tr-setting-icon"><svg viewBox="0 0 24 24"><path d="m12 3 1.3 3.7L17 8l-3.7 1.3L12 13l-1.3-3.7L7 8l3.7-1.3L12 3ZM18.5 13.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2ZM6 13l1 2.8 2.8 1L7 17.8 6 20.5 5 17.8l-2.8-1 2.8-1L6 13Z"/></svg></span><div><strong>切换动画</strong><small>主题重载时的过渡方式</small></div><RoamingSelect v-model="form.animation" :options="animationOptions" /></label>
          </div>
        </main>
      </section>
    </div>
  </div>
</template>

<style scoped>
*{box-sizing:border-box}.tr-shell{height:calc(100vh - 64px);min-height:0;padding:16px;color:#292634;background:#f7f7fa;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.tr-frame{position:relative;display:flex;height:100%;min-height:0;flex-direction:column;padding:16px;overflow:hidden;border:1px solid #e5e1eb;border-radius:24px;background:rgba(255,255,255,.76);box-shadow:0 18px 48px rgba(45,36,62,.06);backdrop-filter:blur(24px)}.tr-corner{position:absolute;z-index:3;width:24px;height:24px;pointer-events:none}.tr-corner-tl{top:8px;left:8px;border-top:2px solid #cfc4dc;border-left:2px solid #cfc4dc;border-top-left-radius:24px}.tr-corner-tr{top:8px;right:8px;border-top:2px solid #cfc4dc;border-right:2px solid #cfc4dc;border-top-right-radius:24px}.tr-corner-bl{bottom:8px;left:8px;border-bottom:2px solid #cfc4dc;border-left:2px solid #cfc4dc;border-bottom-left-radius:24px}.tr-corner-br{right:8px;bottom:8px;border-right:2px solid #cfc4dc;border-bottom:2px solid #cfc4dc;border-bottom-right-radius:24px}.tr-topbar{position:relative;z-index:4;display:flex;min-height:54px;align-items:center;gap:18px;margin-bottom:14px;padding:6px 9px 6px 13px;border:1px solid #e7e3ec;border-radius:999px;background:rgba(255,255,255,.86);box-shadow:0 5px 18px rgba(48,39,64,.045)}.tr-brand{display:flex;align-items:center;gap:10px;white-space:nowrap}.tr-brand-mark{display:grid;width:34px;height:34px;place-items:center;border-radius:12px;background:linear-gradient(145deg,#8876bd,#665292);box-shadow:0 6px 14px rgba(105,82,148,.19);color:#fff}.tr-brand-mark svg{width:21px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.tr-brand strong{font-size:14px;font-weight:800}.tr-brand strong span{color:#7862aa}.tr-brand em{padding-left:10px;border-left:1px solid #e5e1e9;color:#8a8490;font-size:11px;font-style:normal}.tr-nav{display:flex;align-items:center;gap:4px;margin:auto;padding:4px;border:1px solid #ece8f0;border-radius:999px;background:#f7f5f9}.tr-nav button{display:flex;height:34px;align-items:center;gap:6px;padding:0 13px;border:0;border-radius:999px;background:transparent;color:#77717f;font-size:11px;font-weight:650;cursor:pointer;transition:.18s}.tr-nav button svg{width:15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.tr-nav button b{display:grid;min-width:18px;height:18px;place-items:center;border-radius:999px;background:#ece8f1;color:#76688c;font-size:9px}.tr-nav button:hover{color:#6d589b}.tr-nav button.active{background:#fff;color:#685092;box-shadow:0 4px 13px rgba(65,51,87,.09)}.tr-actions{display:flex;align-items:center;gap:8px}.tr-icon-button{display:grid;width:34px;height:34px;place-items:center;border:1px solid #e4dfea;border-radius:50%;background:#fff;color:#756a80;cursor:pointer}.tr-icon-button svg{width:15px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}.tr-save{height:34px;padding:0 15px;border:1px solid #725c9d;border-radius:999px;background:#725c9d;color:#fff;font-size:11px;font-weight:700;cursor:pointer;box-shadow:0 5px 13px rgba(98,76,140,.18)}button:disabled{cursor:not-allowed;opacity:.42}.tr-workspace{min-height:0;flex:1;overflow:auto;border:1px solid #e8e4ec;border-radius:22px;background:#fff;padding:20px;scrollbar-width:none}.tr-workspace::-webkit-scrollbar{display:none}.tr-content{width:100%;max-width:1380px;margin:0 auto}.tr-section-head{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin-bottom:17px}.tr-section-head small{color:#8c78b5;font-size:9px;font-weight:750;letter-spacing:.16em}.tr-section-head h1{margin:4px 0 3px;font-size:18px}.tr-section-head p{margin:0;color:#8c8692;font-size:11px}.tr-section-actions{display:flex;align-items:center;gap:10px}.tr-section-actions>span{color:#958e9d;font-size:10px}.tr-section-actions button{height:34px;padding:0 13px;border:1px solid #dcd3e8;border-radius:999px;background:#f4effa;color:#70599e;font-size:11px;font-weight:700;cursor:pointer}.tr-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(310px,1fr));gap:14px}.tr-card{overflow:hidden;border:1px solid #e5e0e9;border-radius:17px;background:#fff;box-shadow:0 7px 21px rgba(55,43,72,.045);transition:.18s}.tr-card:hover{border-color:#d6cce1;transform:translateY(-2px);box-shadow:0 13px 30px rgba(55,43,72,.08)}.tr-preview{position:relative;height:142px;overflow:hidden;background:linear-gradient(135deg,#f1edf6,#faf9fc)}.tr-preview img{width:100%;height:100%;object-fit:cover}.tr-preview.empty{display:grid;place-items:center}.tr-preview.empty>span{display:flex;align-items:center;gap:7px;color:#a39baa;font-size:10px}.tr-preview.empty svg{width:20px;fill:none;stroke:currentColor;stroke-width:1.5}.tr-preview>i{position:absolute;top:10px;left:10px;padding:4px 7px;border-radius:8px;background:rgba(38,32,47,.68);color:#fff;font-size:9px;font-style:normal;backdrop-filter:blur(5px)}.tr-preview>em{position:absolute;top:10px;right:10px;padding:4px 8px;border-radius:999px;background:rgba(255,255,255,.88);font-size:9px;font-style:normal;backdrop-filter:blur(5px)}.tr-preview>em.ready{color:#37836b}.tr-preview>em.pending{color:#ae7340}.tr-card-body{display:grid;grid-template-columns:minmax(0,1fr) 55px minmax(0,1fr);align-items:end;gap:8px;padding:15px}.tr-card-body label{display:grid;gap:6px;color:#77707f;font-size:9px;font-weight:650}.tr-card-body select,.tr-settings select{width:100%;height:36px;padding:0 28px 0 10px;border:1px solid #e1dce6;border-radius:10px;background:#faf9fb;color:#3c3742;outline:none;font-size:11px}.tr-card-body select:focus,.tr-settings select:focus{border-color:#b4a4ca;box-shadow:0 0 0 3px rgba(117,91,159,.08)}.tr-binding-line{display:flex;align-items:center;gap:5px;padding-bottom:12px;color:#a39baa;font-size:8px;white-space:nowrap}.tr-binding-line i{height:1px;flex:1;background:#ddd6e4}.tr-card footer{display:flex;align-items:center;justify-content:space-between;padding:11px 15px;border-top:1px solid #eeeaf1;background:#fcfbfd}.tr-toggle{display:flex;align-items:center;gap:7px;color:#706a76;font-size:10px;cursor:pointer}.tr-toggle input{position:absolute;opacity:0}.tr-toggle i{position:relative;width:30px;height:17px;border-radius:999px;background:#d9d4dd;transition:.18s}.tr-toggle i:after{position:absolute;top:2px;left:2px;width:13px;height:13px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.16);content:"";transition:.18s}.tr-toggle input:checked+i{background:linear-gradient(90deg,#8974c2,#ad7fba)}.tr-toggle input:checked+i:after{left:15px}.tr-delete{display:flex;align-items:center;gap:4px;padding:5px 8px;border:0;border-radius:8px;background:transparent;color:#aa6570;font-size:9px;cursor:pointer}.tr-delete:hover{background:#fff1f3}.tr-delete svg{width:13px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.tr-empty{display:flex;min-height:280px;align-items:center;justify-content:center;flex-direction:column;gap:7px;border:1px dashed #dad4df;border-radius:16px;color:#918a97;font-size:11px}.tr-empty b{color:#514a58;font-size:12px}.tr-settings{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px}.tr-settings>label{display:grid;grid-template-columns:42px minmax(0,1fr) minmax(150px,220px);align-items:center;gap:12px;padding:17px;border:1px solid #e6e1e9;border-radius:16px;background:#fdfcfe;box-shadow:0 6px 18px rgba(54,43,70,.035)}.tr-setting-icon{display:grid;width:38px;height:38px;place-items:center;border-radius:13px;background:#f0ebf6;color:#745c9d;font-family:Georgia,serif;font-size:18px}.tr-settings div{display:grid;gap:3px}.tr-settings strong{font-size:12px}.tr-settings small{color:#96909b;font-size:9px}.tr-validation{margin-top:14px;padding:13px 16px;border:1px solid #ecd6da;border-radius:12px;background:#fff7f8;color:#9f4f5b;font-size:10px}.tr-validation ul{margin:6px 0 0;padding-left:17px}.tr-spinner{display:inline-block;width:13px;height:13px;margin-right:5px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;vertical-align:-2px;animation:spin .7s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:1050px){.tr-brand em{display:none}.tr-grid{grid-template-columns:repeat(auto-fill,minmax(270px,1fr))}.tr-card-body{grid-template-columns:1fr}.tr-binding-line{display:none}.tr-settings{grid-template-columns:1fr}}@media(max-width:760px){.tr-shell{height:auto;min-height:100%;padding:9px}.tr-frame{min-height:calc(100vh - 82px);padding:10px;border-radius:18px}.tr-topbar{align-items:flex-start;flex-wrap:wrap;border-radius:18px;padding:9px}.tr-nav{order:3;width:100%}.tr-nav button{flex:1;justify-content:center}.tr-actions{margin-left:auto}.tr-workspace{padding:14px}.tr-grid{grid-template-columns:1fr}.tr-settings>label{grid-template-columns:38px minmax(0,1fr)}.tr-settings select{grid-column:1/-1}.tr-section-head{align-items:flex-start;flex-direction:column}.tr-section-actions{width:100%;justify-content:space-between}}
.tr-frame{padding:16px}
.tr-topbar{margin-left:0}
.tr-actions{margin-left:auto}
.tr-workspace{padding:0;overflow:hidden}
.tr-layout{display:grid;height:100%;min-height:0;grid-template-columns:190px minmax(0,1fr)}
.tr-side-nav{display:flex;min-height:0;flex-direction:column;gap:6px;padding:18px 12px;border-right:1px solid #ece8f0;background:#fbfafc}
.tr-side-nav__title{padding:0 10px 7px;color:#a099a7;font-size:9px;font-weight:750;letter-spacing:.16em}
.tr-side-nav button{display:grid;grid-template-columns:34px minmax(0,1fr) auto;align-items:center;gap:9px;width:100%;padding:8px;border:1px solid transparent;border-radius:13px;background:transparent;color:#77717f;text-align:left;cursor:pointer;transition:background .16s ease,border-color .16s ease,color .16s ease,box-shadow .16s ease}
.tr-side-nav button:hover{background:#f4f1f6;color:#62576e}
.tr-side-nav button.active{border-color:#e4ddeb;background:#fff;color:#674f8f;box-shadow:0 5px 15px rgba(57,43,76,.06)}
.tr-side-nav__icon{display:grid;width:34px;height:34px;place-items:center;border-radius:10px;background:#f1edf4;color:#88759c}
.tr-side-nav button.active .tr-side-nav__icon{background:#eee8f5;color:#72599a}
.tr-side-nav__icon svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.tr-side-nav__copy{display:grid;min-width:0;gap:2px}
.tr-side-nav__copy strong{font-size:11px;font-weight:700}
.tr-side-nav__copy small{color:#9b94a1;font-size:9px}
.tr-side-nav button b{display:grid;min-width:19px;height:19px;place-items:center;padding:0 5px;border-radius:999px;background:#eee9f2;color:#77668a;font-size:9px}
.tr-view{min-width:0;min-height:0;padding:20px;overflow:auto;scrollbar-width:none}
.tr-view::-webkit-scrollbar{display:none}
.tr-binding-list{display:flex;flex-direction:column;gap:9px}
.tr-binding-row{display:grid;grid-template-columns:68px minmax(0,1fr) 135px;align-items:center;gap:16px;min-height:86px;padding:12px 14px;border:1px solid #e8e3eb;border-radius:15px;background:#fff;box-shadow:0 4px 14px rgba(54,43,70,.035);transition:border-color .16s ease,box-shadow .16s ease}
.tr-binding-row:hover{border-color:#d9d0e1;box-shadow:0 7px 18px rgba(54,43,70,.055)}
.tr-binding-order{display:grid;align-content:center;gap:6px;padding-right:13px;border-right:1px solid #eeeaf1}
.tr-binding-order strong{color:#5f5369;font-size:15px;font-weight:750;letter-spacing:.06em}
.tr-binding-order span{display:flex;align-items:center;gap:5px;color:#99919f;font-size:8px;white-space:nowrap}
.tr-binding-order span i{width:6px;height:6px;border-radius:50%;background:#c0bac5}
.tr-binding-order span.ready{color:#41806b}.tr-binding-order span.ready i{background:#5fa88e}
.tr-binding-order span.pending{color:#a8754e}.tr-binding-order span.pending i{background:#c99468}
.tr-binding-fields{display:grid;grid-template-columns:minmax(170px,1fr) 56px minmax(170px,1fr);align-items:end;gap:10px}
.tr-binding-fields label{display:grid;gap:6px;color:#77707f;font-size:9px;font-weight:650}
.tr-binding-fields select{width:100%;height:36px;padding:0 28px 0 10px;border:1px solid #e1dce6;border-radius:10px;background:#faf9fb;color:#3c3742;outline:none;font-size:11px}
.tr-binding-fields select:focus{border-color:#b4a4ca;box-shadow:0 0 0 3px rgba(117,91,159,.08)}
.tr-binding-link{display:flex;align-items:center;gap:4px;padding-bottom:10px;color:#a69cad}
.tr-binding-link i{height:1px;flex:1;background:#e3dce8}
.tr-binding-link svg{width:13px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.tr-binding-actions{display:flex;align-items:flex-end;justify-content:center;flex-direction:column;gap:9px;padding-left:14px;border-left:1px solid #eeeaf1}
@media(max-width:1050px){.tr-layout{grid-template-columns:164px minmax(0,1fr)}.tr-binding-row{grid-template-columns:58px minmax(0,1fr)}.tr-binding-actions{grid-column:2;align-items:center;justify-content:space-between;flex-direction:row;padding:8px 0 0;border-top:1px solid #eeeaf1;border-left:0}.tr-binding-fields{grid-template-columns:1fr 34px 1fr}}
@media(max-width:760px){.tr-frame{padding:10px}.tr-layout{display:flex;flex-direction:column}.tr-side-nav{flex:none;flex-direction:row;padding:8px;border-right:0;border-bottom:1px solid #ece8f0}.tr-side-nav__title{display:none}.tr-side-nav button{grid-template-columns:30px minmax(0,1fr) auto}.tr-side-nav__icon{width:30px;height:30px}.tr-view{padding:14px}.tr-binding-row{grid-template-columns:1fr;gap:10px}.tr-binding-order{display:flex;align-items:center;justify-content:space-between;padding:0 0 8px;border-right:0;border-bottom:1px solid #eeeaf1}.tr-binding-fields{grid-template-columns:1fr}.tr-binding-link{display:none}.tr-binding-actions{grid-column:auto}.tr-side-nav__copy small{display:none}}
.tr-frame{padding:16px 16px 16px 72px}
.tr-topbar{margin-left:-56px}
.tr-topbar{min-height:62px;padding:7px 10px 7px 13px}
.tr-page-context{display:grid;min-width:0;gap:2px;margin-left:8px;padding-left:16px;border-left:1px solid #e5e0e9}.tr-page-context strong{color:#48404f;font-size:13px;font-weight:750}.tr-page-context span{overflow:hidden;color:#98909d;font-size:9px;text-overflow:ellipsis;white-space:nowrap}
.tr-open-count{padding:5px 9px;border-radius:999px;background:#f4f1f7;color:#85788f;font-size:9px;white-space:nowrap}
.tr-workspace{padding:20px;overflow:auto}
.tr-float-nav{position:absolute;top:50%;left:16px;z-index:20;display:flex;flex-direction:column;gap:6px;padding:10px 8px;border:1px solid rgba(67,52,86,.07);border-radius:18px;background:rgba(248,246,250,.94);box-shadow:0 5px 18px rgba(48,39,64,.055);transform:translateY(-50%);backdrop-filter:blur(16px)}
.tr-float-nav button{position:relative;display:grid;width:36px;height:36px;place-items:center;padding:0;border:0;border-radius:12px;background:transparent;color:#9a91a3;cursor:pointer;transition:background .18s ease,color .18s ease,box-shadow .18s ease,transform .18s ease}
.tr-float-nav button:hover{background:rgba(112,86,151,.065);color:#72599b;transform:translateY(-1px)}
.tr-float-nav button.active{background:rgba(112,86,151,.11);color:#6e5597;box-shadow:inset 0 0 0 1px rgba(112,86,151,.06),0 0 14px rgba(112,86,151,.09)}
.tr-float-nav button:focus-visible{outline:2px solid rgba(112,86,151,.3);outline-offset:2px}
.tr-float-nav svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.tr-nav-badge{position:absolute;top:-2px;right:-3px;display:grid;min-width:16px;height:16px;place-items:center;padding:0 4px;border:2px solid #faf9fb;border-radius:999px;background:#76609d;color:#fff;font-size:8px;font-weight:750;line-height:1;box-shadow:0 2px 6px rgba(66,48,91,.18);pointer-events:none}
.tr-binding-row{grid-template-columns:minmax(250px,.9fr) minmax(390px,1.25fr) 130px;gap:18px;min-height:106px;padding:11px 14px}
.tr-binding-theme{display:grid;grid-template-columns:116px minmax(0,1fr);align-items:center;gap:12px;min-width:0}
.tr-binding-cover{position:relative;display:grid;width:116px;height:72px;place-items:center;overflow:hidden;border:1px solid #d7dbdc;border-radius:11px;background:#eef1f1;color:#687174}
.tr-binding-cover img{width:100%;height:100%;object-fit:cover}
.tr-binding-cover>svg{width:25px;fill:none;stroke:currentColor;stroke-width:1.35;stroke-linecap:round;stroke-linejoin:round}
.tr-binding-cover>b{position:absolute;bottom:6px;left:6px;display:grid;min-width:22px;height:18px;place-items:center;padding:0 5px;border-radius:6px;background:rgba(39,32,48,.7);color:#fff;font-size:8px;backdrop-filter:blur(5px)}
.tr-binding-summary{display:grid;min-width:0;gap:4px}
.tr-binding-summary>strong{overflow:hidden;color:#443d4b;font-size:12px;font-weight:750;text-overflow:ellipsis;white-space:nowrap}
.tr-binding-summary>p{display:-webkit-box;overflow:hidden;margin:0;color:#928b97;font-size:9px;line-height:1.45;-webkit-box-orient:vertical;-webkit-line-clamp:2}
.tr-binding-summary>span{display:flex;align-items:center;gap:5px;overflow:hidden;color:#aaa2ae;font-size:8px;text-overflow:ellipsis;white-space:nowrap}
.tr-binding-summary>span i{width:6px;height:6px;flex:none;border-radius:50%;background:#c99468}.tr-binding-summary>span i.ready{background:#5fa88e}
.tr-binding-actions{align-items:flex-end}
@media(max-width:1180px){.tr-binding-row{grid-template-columns:minmax(230px,.9fr) minmax(300px,1.1fr)}.tr-binding-actions{grid-column:1/-1;align-items:center;justify-content:flex-end;flex-direction:row;padding:9px 0 0;border-top:1px solid #eeeaf1;border-left:0}}
@media(max-width:820px){.tr-frame{padding:10px 10px 10px 62px}.tr-topbar{margin-left:-52px}.tr-float-nav{left:12px;padding:8px 6px}.tr-float-nav button{width:34px;height:34px}.tr-binding-row{grid-template-columns:1fr}.tr-binding-theme{grid-template-columns:104px minmax(0,1fr)}.tr-binding-cover{width:104px;height:66px}.tr-binding-fields{grid-template-columns:1fr}.tr-binding-link{display:none}.tr-binding-actions{grid-column:auto;justify-content:space-between}}
.tr-content{max-width:none}
.tr-section-head{margin-bottom:22px}
.tr-section-head small{font-size:10px}.tr-section-head h1{margin:6px 0 5px;font-size:21px}.tr-section-head p{font-size:12px}
.tr-section-actions>span{font-size:11px}.tr-section-actions button{height:39px;padding:0 17px;font-size:12px}
.tr-binding-row{grid-template-columns:minmax(310px,1fr) minmax(520px,1.65fr) 190px;gap:24px;min-height:122px;padding:16px 18px;border-radius:18px}
.tr-binding-theme{grid-template-columns:132px minmax(0,1fr);gap:15px}
.tr-binding-cover{width:132px;height:84px;border-radius:13px}
.tr-binding-summary{gap:6px}.tr-binding-summary>strong{font-size:14px}.tr-binding-summary>p{font-size:10px;line-height:1.55}.tr-binding-summary>span{font-size:9px}
.tr-binding-fields{grid-template-columns:minmax(210px,1fr) 64px minmax(210px,1fr);gap:14px}
.tr-binding-fields label{gap:8px;font-size:10px}
.tr-binding-fields select,.tr-settings select{-webkit-appearance:none;appearance:none;height:44px;padding:0 42px 0 14px;border:1px solid #ddd5e5;border-radius:12px;background-color:#faf8fc;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 20' fill='none'%3E%3Cpath d='m6 8 4 4 4-4' stroke='%23756383' stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");background-position:right 13px center;background-repeat:no-repeat;background-size:17px;color:#37313e;font-size:12px;font-weight:600;cursor:pointer;transition:border-color .16s ease,box-shadow .16s ease,background-color .16s ease}
.tr-binding-fields select:hover,.tr-settings select:hover{border-color:#c9bdd5;background-color:#fff}
.tr-binding-fields select:focus,.tr-settings select:focus{border-color:#9f8ab8;background-color:#fff;box-shadow:0 0 0 4px rgba(117,91,159,.09)}
.tr-binding-actions{align-items:center;justify-content:flex-end;flex-direction:row;gap:10px;padding-left:18px}
.tr-binding-actions .tr-toggle{min-height:36px;padding:0 11px;border:1px solid #e4ddeb;border-radius:11px;background:#faf8fc;color:#665b70;font-size:10px;font-weight:650;white-space:nowrap}
.tr-binding-actions .tr-toggle:hover{border-color:#d1c4dc;background:#fff}
.tr-binding-actions .tr-toggle i{width:34px;height:19px}.tr-binding-actions .tr-toggle i:after{width:15px;height:15px}.tr-binding-actions .tr-toggle input:checked+i:after{left:17px}
.tr-binding-actions .tr-delete{min-height:36px;padding:0 11px;border:1px solid #ecd9dd;border-radius:11px;background:#fff8f9;color:#a35664;font-size:10px;font-weight:650;white-space:nowrap}
.tr-binding-actions .tr-delete:hover{border-color:#dfbdc4;background:#fff1f3;color:#943f50}
.tr-binding-actions .tr-delete svg{width:14px}
.tr-settings{grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}
.tr-settings>label{grid-template-columns:50px minmax(0,1fr) minmax(210px,34%);gap:16px;min-height:92px;padding:20px;border-radius:18px}
.tr-setting-icon{width:46px;height:46px;border-radius:14px;font-size:21px}.tr-settings strong{font-size:14px}.tr-settings small{font-size:10px;line-height:1.5}
@media(max-width:1360px){.tr-binding-row{grid-template-columns:minmax(270px,.9fr) minmax(420px,1.35fr)}.tr-binding-actions{grid-column:1/-1;padding:10px 0 0;border-top:1px solid #eeeaf1;border-left:0}.tr-settings>label{grid-template-columns:46px minmax(0,1fr) minmax(180px,34%)}}
@media(max-width:900px){.tr-binding-row{grid-template-columns:1fr}.tr-binding-fields{grid-template-columns:1fr}.tr-binding-actions{grid-column:auto;justify-content:flex-start}.tr-settings{grid-template-columns:1fr}}
.tr-frame{padding:16px 16px 16px 72px}
.tr-topbar{margin-left:-56px}
.tr-add{height:36px;padding:0 14px;border:1px solid #d9cee4;border-radius:999px;background:#f4eff8;color:#6d5594;font-size:10px;font-weight:700;cursor:pointer;transition:border-color .16s ease,background .16s ease,transform .16s ease}.tr-add:hover{border-color:#bdaaca;background:#fff;transform:translateY(-1px)}
@media(max-width:760px){.tr-frame{padding:10px 10px 10px 62px}.tr-topbar{margin-left:-52px}.tr-page-context{order:3;width:100%;margin:2px 0 0;padding:7px 5px 2px;border-top:1px solid #ebe7ee;border-left:0}.tr-open-count{display:none}.tr-add{height:34px}.tr-float-nav{left:12px;padding:8px 6px}.tr-float-nav button{width:34px;height:34px}}

/* Neutral console palette: intentionally independent from any theme brand color. */
.tr-shell{color:#242629;background:#f5f6f7}
.tr-frame{border-color:#e2e4e7;background:rgba(255,255,255,.8);box-shadow:0 18px 48px rgba(19,22,26,.06)}
.tr-corner-tl,.tr-corner-tr,.tr-corner-bl,.tr-corner-br{border-color:#cdd1d5}
.tr-topbar{gap:14px;border-color:#e2e4e7;background:rgba(255,255,255,.92);box-shadow:0 5px 18px rgba(20,23,27,.05)}
.tr-brand-mark{width:36px;height:36px;border-radius:11px;background:#26292d;color:#fff;box-shadow:0 5px 13px rgba(19,22,26,.16)}
.tr-brand-mark svg{width:23px;stroke-width:1.55}
.tr-brand strong,.tr-brand strong span{color:#292c30}
.tr-page-context{margin-left:4px;padding-left:0;border-left:0}
.tr-page-context strong{color:#34373b}.tr-page-context span{color:#8a8f95}
.tr-open-count{background:#f0f2f3;color:#6f757b}
.tr-icon-button{border-color:#dfe2e5;color:#656b71}.tr-icon-button:hover{border-color:#c5c9cd;background:#f7f8f8;color:#24272a}
.tr-add{border-color:#303338;background:#303338;color:#fff}.tr-add:hover{border-color:#17191c;background:#17191c}
.tr-workspace{border-color:#e3e5e7}
.tr-float-nav{border-color:rgba(26,29,32,.08);background:rgba(248,249,249,.96);box-shadow:0 5px 18px rgba(20,23,27,.06)}
.tr-float-nav button{color:#8a9096}.tr-float-nav button:hover{background:#eceeef;color:#34383c}.tr-float-nav button.active{background:#25282c;color:#fff;box-shadow:0 5px 12px rgba(20,23,27,.16)}.tr-float-nav button:focus-visible{outline-color:rgba(37,40,44,.3)}
.tr-nav-badge{border-color:#f8f9f9;background:#5f656b;box-shadow:0 2px 6px rgba(20,23,27,.16)}
.tr-binding-row{border-color:#e2e5e7;box-shadow:0 4px 14px rgba(22,25,29,.035)}.tr-binding-row:hover{border-color:#cfd3d6;box-shadow:0 7px 18px rgba(22,25,29,.055)}
.tr-binding-cover{border-color:#d7dbdc;background:#eef1f1;color:#687174}
.tr-binding-summary>strong{color:#35383c}.tr-binding-summary>p{color:#858b90}.tr-binding-summary>span{color:#9ba0a5}
.tr-binding-link{color:#9ca1a6}.tr-binding-link i{background:#dde0e2}
.tr-binding-actions{border-left-color:#e7e9ea}.tr-binding-actions .tr-toggle{border-color:#dfe2e4;background:#f7f8f8;color:#5c6268}.tr-binding-actions .tr-toggle:hover{border-color:#c8cccf}.tr-toggle input:checked+i{background:#373b3f}
.tr-settings>label{border-color:#e1e4e6;background:#fdfdfd;box-shadow:0 6px 18px rgba(22,25,29,.035)}
.tr-setting-icon{background:#eef0f1;color:#50565b}
@media(max-width:760px){.tr-page-context{padding:5px 2px 1px;border-top:0}}

/* Editorial identity: flat surfaces, strong structure, restrained status color. */
.tr-shell{background:#eef0f0;color:#292d30}
.tr-frame{border-color:#cfd3d4;background:#f7f8f7;box-shadow:none;backdrop-filter:none}
.tr-corner-tl,.tr-corner-tr,.tr-corner-bl,.tr-corner-br{border-color:#292d30}
.tr-topbar{border-color:#d3d6d6;background:#fff;box-shadow:none}
.tr-brand-mark{width:42px;height:42px;flex:none;border:0;background:transparent;color:#292d30;box-shadow:none}
.tr-brand strong,.tr-brand strong span{color:#292d30}
.tr-page-context strong{color:#292d30}.tr-page-context span{color:#737a7d}
.tr-open-count{border:1px solid #d8dbdc;background:#f3f4f4;color:#555c60}
.tr-workspace{border-color:#d3d6d6;background:#fff}
.tr-float-nav{border-color:#d4d7d8;background:#fff;box-shadow:none;backdrop-filter:none}
.tr-float-nav button{color:#6b7275}.tr-float-nav button:hover{background:#eef0f0;color:#292d30}.tr-float-nav button.active{background:#292d30;color:#fff;box-shadow:none}
.tr-nav-badge{border-color:#fff;background:#e87870;box-shadow:none}
.tr-binding-row{border-color:#d8dbdc;background:#fff;box-shadow:none}.tr-binding-row:hover{border-color:#292d30;box-shadow:none}
.tr-binding-cover{border-color:#d7dbdc;background:#eef2f2;color:#687174}
.tr-binding-cover>b{background:#292d30;backdrop-filter:none}
.tr-binding-summary>strong{color:#292d30}.tr-binding-summary>p{color:#747b7e}.tr-binding-summary>span{color:#899093}
.tr-binding-link{color:#8b9295}.tr-binding-link i{background:#d8dbdc}
.tr-binding-fields select,.tr-settings select{border-color:#cfd3d4;background-color:#fff;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 20' fill='none'%3E%3Cpath d='m6 8 4 4 4-4' stroke='%23292d30' stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");color:#292d30}
.tr-binding-fields select:hover,.tr-settings select:hover{border-color:#858b8d}.tr-binding-fields select:focus,.tr-settings select:focus{border-color:#292d30;box-shadow:0 0 0 3px rgba(41,45,48,.1)}
.tr-binding-actions .tr-toggle{border-color:#d1d5d6;background:#f7f8f7;color:#404649}.tr-binding-actions .tr-toggle:hover{border-color:#8f9597;background:#fff}
.tr-toggle input:checked+i{background:#62aa9d}
.tr-settings>label{border-color:#d8dbdc;background:#fff;box-shadow:none}
.tr-setting-icon{border:1px solid #d7dbdc;background:#eef1f1;color:#292d30}
.tr-readonly-field{border-color:#d4d8d9;background:#f7f8f7;color:#292d30}.tr-readonly-field>i{background:#e87870;box-shadow:none}
.tr-add{border-color:#292d30;background:#292d30;color:#fff}.tr-add:hover{border-color:#292d30;background:#fff;color:#292d30;transform:none}
.tr-icon-button{border-color:#d3d6d7;color:#555c5f}.tr-icon-button:hover{border-color:#292d30;background:#fff;color:#292d30}
.tr-section-head small{color:#687174}.tr-section-head h1{color:#292d30}.tr-section-head p{color:#747b7e}
.tr-side-nav button:hover{background:#eef0f0;color:#292d30}.tr-side-nav button.active{border-color:#292d30;background:#fff;color:#292d30;box-shadow:none}
.tr-side-nav__icon,.tr-side-nav button.active .tr-side-nav__icon{background:#eef1f1;color:#292d30}
.tr-binding-row{min-height:142px;padding:18px 20px}
.tr-binding-theme{grid-template-columns:158px minmax(0,1fr);gap:17px}
.tr-binding-cover{width:158px;height:104px;padding:5px;background:#fafbfc}
.tr-binding-cover img{position:absolute;inset:5px;width:calc(100% - 10px);height:calc(100% - 10px);object-fit:contain;border-radius:8px}
.tr-setting-icon svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.55;stroke-linecap:round;stroke-linejoin:round}
.tr-binding-cover>b{bottom:10px;left:10px}
.tr-readonly-field{display:flex;height:44px;align-items:center;gap:10px;padding:0 14px;border:1px solid #dfe2e4;border-radius:12px;background:#f8f9f9;color:#34383c}
.tr-readonly-field>i{width:7px;height:7px;flex:none;border-radius:50%;background:#e87870;box-shadow:none}
.tr-readonly-field>strong{overflow:hidden;font-size:12px;font-weight:650;text-overflow:ellipsis;white-space:nowrap}
.tr-binding-actions{padding-left:12px}
@media(max-width:820px){.tr-binding-theme{grid-template-columns:128px minmax(0,1fr)}.tr-binding-cover{width:128px;height:92px}}

/* Respond to the actual plugin canvas, including the space occupied by Halo's sidebar. */
.tr-frame{container-name:roaming-frame;container-type:inline-size}
.tr-brand-mark svg{width:32px;height:32px;fill:none;stroke:none}
@container roaming-frame (max-width:1100px){
  .tr-binding-row{grid-template-columns:minmax(0,.9fr) minmax(0,1.15fr);gap:18px}
  .tr-binding-theme{grid-template-columns:132px minmax(0,1fr);gap:14px}
  .tr-binding-cover{width:132px;height:92px}
  .tr-binding-fields{grid-template-columns:minmax(0,1fr) 42px minmax(0,1fr);gap:10px}
  .tr-binding-actions{grid-column:1/-1;justify-content:flex-end;padding:10px 0 0;border-top:1px solid #e7e9ea;border-left:0}
  .tr-settings>label{grid-template-columns:46px minmax(0,1fr);gap:12px}
  .tr-settings>label>:last-child{grid-column:1/-1}
}
@container roaming-frame (max-width:760px){
  .tr-topbar{align-items:center;flex-wrap:wrap;border-radius:20px}
  .tr-page-context{min-width:0;flex:1}
  .tr-binding-row{grid-template-columns:minmax(0,1fr);gap:15px;padding:16px}
  .tr-binding-theme{grid-template-columns:124px minmax(0,1fr)}
  .tr-binding-cover{width:124px;height:88px}
  .tr-binding-fields{grid-template-columns:minmax(0,1fr)}
  .tr-binding-link{display:none}
  .tr-binding-actions{grid-column:auto;justify-content:flex-start}
  .tr-settings{grid-template-columns:minmax(0,1fr)}
}
@container roaming-frame (max-width:520px){
  .tr-topbar{align-items:flex-start;padding:9px 10px}
  .tr-page-context{order:3;width:100%;flex-basis:100%;padding-top:7px;border-top:1px solid #ebeef0}
  .tr-open-count{display:none}
  .tr-binding-theme{grid-template-columns:minmax(0,1fr)}
  .tr-binding-cover{width:100%;height:118px}
  .tr-binding-summary>p{-webkit-line-clamp:3}
  .tr-binding-actions .tr-toggle{width:100%;justify-content:space-between}
  .tr-settings>label{grid-template-columns:42px minmax(0,1fr);padding:16px}
  .tr-setting-icon{width:42px;height:42px}
}

</style>
