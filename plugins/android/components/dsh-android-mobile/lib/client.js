import { createMobileLayout } from "../src/layout.js";

window.__ModuleLoader__.load({
  id: "dsh-android-mobile",
  factory: () => ({ inject: [], apply(ctx) { return createMobileLayout().apply(ctx); } }),
});
