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
    "stroke-width": "1.55",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
  }, [
    h("path", { d: "M5 14.5V6.8A2.8 2.8 0 0 1 7.8 4h7.7" }),
    h("rect", { x: "8", y: "7", width: "11", height: "10", rx: "2.5" }),
    h("path", { d: "M10.8 10.5h5.4l-1.5-1.4M16.2 13.5h-5.4l1.5 1.4" }),
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
