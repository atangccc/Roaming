import { definePlugin } from "@halo-dev/ui-shared";
import { h } from "vue";

const RoamingIcon = {
  __v_skip: true,
  name: "ThemeRoamingIcon",
  render: () => h("svg", {
    viewBox: "0 0 1024 1024",
    width: "1.2em",
    height: "1.2em",
    fill: "none",
  }, [
    h("defs", {}, [
      h("linearGradient", {
        id: "theme-roaming-menu-gradient",
        x1: "110",
        y1: "90",
        x2: "920",
        y2: "940",
        gradientUnits: "userSpaceOnUse",
      }, [
        h("stop", { "stop-color": "#ed8db7" }),
        h("stop", { offset: ".36", "stop-color": "#a18bdd" }),
        h("stop", { offset: ".7", "stop-color": "#61b4d5" }),
        h("stop", { offset: "1", "stop-color": "#68c798" }),
      ]),
    ]),
    h("path", {
      d: "M73.130667 73.130667m438.869333 0 0 0q438.869333 0 438.869333 438.869333l0 0q0 438.869333-438.869333 438.869333l0 0q-438.869333 0-438.869333-438.869333l0 0q0-438.869333 438.869333-438.869333Z",
      fill: "url(#theme-roaming-menu-gradient)",
      stroke: "rgba(62, 57, 76, .38)",
      "stroke-width": "24",
    }),
    h("path", {
      d: "M305.066667 585.130667h-34.389334L343.893333 512l73.216 73.130667h-31.744a146.432 146.432 0 0 0 253.44 0h80.213334a219.477333 219.477333 0 0 1-413.866667 0z m413.866666-146.261334h32.170667L677.888 512l-73.130667-73.130667h34.048a146.432 146.432 0 0 0-253.525333 0h-80.213333a219.477333 219.477333 0 0 1 413.866666 0z",
      fill: "#fff",
    }),
  ]),
};

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/theme-roaming",
        name: "ThemeRoaming",
        component: () => import("./views/ThemeRoamingView.vue"),
        meta: {
          title: "主题漫游",
          searchable: true,
          permissions: ["plugin:theme-roaming:manage"],
          menu: {
            name: "主题漫游",
            group: "interface",
            icon: RoamingIcon,
            priority: 10,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
