import { definePlugin } from "@halo-dev/ui-shared";
import { h } from "vue";

const RoamingIcon = {
  __v_skip: true,
  name: "ThemeRoamingIcon",
  render: () => h("svg", {
    viewBox: "0 0 24 24",
    width: "1.2em",
    height: "1.2em",
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "1.7",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
  }, [
    h("path", { d: "M12 3a9 9 0 1 0 0 18h1.2a1.8 1.8 0 1 0 0-3.6h-.6a1.5 1.5 0 0 1 0-3h2A6.8 6.8 0 0 0 14.6 3H12Z" }),
    h("circle", { cx: "7.6", cy: "10.4", r: ".8", fill: "currentColor", stroke: "none" }),
    h("circle", { cx: "10", cy: "6.8", r: ".8", fill: "currentColor", stroke: "none" }),
    h("circle", { cx: "14.4", cy: "6.8", r: ".8", fill: "currentColor", stroke: "none" }),
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
