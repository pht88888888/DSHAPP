import { createPrivilegedSection } from "../src/privilege/section.js";
import { createBubbleSection } from "../src/bubble/section.js";
import { createLogsSection } from "../src/logs/section.js";
import { createContactSection } from "../src/contact/section.js";

window.__ModuleLoader__.load({
  id: "dsh-android-mobile-settings",
  factory: (require) => {
    // Queue mode invokes factory before official services/static modules exist.
    // Each section resolves React only when apply registers it after boot.
    const sections = [
      createPrivilegedSection(require), createBubbleSection(require),
      createLogsSection(require), createContactSection(require),
    ];
    return { inject: [], apply(ctx) { for (const register of sections) register(ctx); } };
  },
});
