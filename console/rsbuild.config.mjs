import { rsbuildConfig } from "@halo-dev/ui-plugin-bundler-kit";

export default rsbuildConfig({
  rsbuild: ({ envMode }) => ({
    output: {
      distPath: {
        root: envMode === "production" ? "../src/main/resources/ui" : "../build/resources/main/ui",
      },
    },
  }),
});
