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
    "stroke-width": "1.6",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
  }, [
    h("rect", { x: "2", y: "2", width: "20", height: "20", rx: "6" }),
    h("path", { d: "M6.8 8.3h10.4M6.8 15.7h10.4" }),
    h("circle", { cx: "15.2", cy: "8.3", r: "1.45", fill: "currentColor", stroke: "none" }),
    h("circle", { cx: "8.8", cy: "15.7", r: "1.45", fill: "currentColor", stroke: "none" }),
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
