# 主题漫游

主题漫游是一个面向 Halo 2.25+ 的多主题访客切换插件。站长可以把已安装主题与对应菜单绑定后开放给访客；每位访客只改变自己的页面渲染，不会启用或停用 Halo 的全局主题，也不会影响其他访客。

> 告别一个站只能穿一套衣服的烦恼。今天宠这个，明天宠那个，让安装过的主题都能雨露均沾。

## 主要能力

- 自动读取 Halo 中已经安装的主题和菜单，无需手工新增主题数据。
- 每套主题都能绑定自己的菜单，并决定是否开放给访客。访客可以自由切换站长准备的多套主题，不再只能看到一种风格，也让安装的每套主题都有机会派上用场。

## 安装与使用

1. 安装并启动插件。
2. 在 Console 左侧进入“主题漫游”。
3. 为每个主题选择对应菜单。
4. 打开需要向访客展示的主题。
5. 在“漫游配置”中设置记忆时间、开放范围、入口位置和切换动画。

如果没有任何主题处于“前台开放”状态，插件不会向站点页面注入入口。

## 权限边界

- 公共目录权限模板隐藏，并聚合到 Halo 匿名角色，只提供只读目录查询。
- Console 管理权限模板隐藏，并聚合到 Halo 管理员角色。
- 公共接口不会返回后台配置、未开放主题或敏感数据。
- 服务端始终重新校验主题白名单与 `READY` 状态，不能通过伪造 Cookie 绕过。

## 主题接入

插件不要求主题适配，默认会自动注入完整切换入口。主题需要自定义位置时，可提供一个锚点：

```html
<div data-theme-roaming-anchor></div>
```

使用主题自己的按钮打开面板：

```html
<button type="button" data-theme-roaming-open>切换主题</button>
```

前台 JavaScript 接口为 `window.ThemeRoaming`，提供 `open()`、`close()`、`toggle()`、`refresh()`、`select(themeName)`、`getState()` 和 `mount(anchor)`。调用前应判断接口是否存在：

```js
window.ThemeRoaming?.open();
```

开放主题目录：

```text
GET /apis/api.theme-roaming.halo.run/v1alpha1/catalog
```

Thymeleaf 可通过 `themeRoamingFinder.getCatalog()` 获取当前主题和开放主题列表。

菜单绑定建议使用 `themeRoamingMenuFinder`。未安装插件时必须保留 Halo 原生 `menuFinder` 回退：

```html
${themeRoamingMenuFinder != null
  ? themeRoamingMenuFinder.getForTheme(theme.metadata.name).menuItems
  : menuFinder.getPrimary().menuItems}
```

## 开发与构建

插件版本仅在 `gradle.properties` 的 `pluginVersion` 中维护，插件元数据和 JAR 文件名会在构建时自动同步。

```powershell
.\gradlew clean build
```

构建包含 Console 前端、Java 编译和单元测试，产物位于 `build/libs/`。

## 兼容性

- Halo：`>= 2.25.0`
- Java：21

Halo 升级后建议重新执行完整构建，并验证首页、列表页、文章页、主题静态资源和菜单绑定。

## 源码与反馈

- 源码仓库：https://github.com/atangccc/Roaming
- 问题反馈：https://github.com/atangccc/Roaming/issues
- 协议：GPL-3.0
