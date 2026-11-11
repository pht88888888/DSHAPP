import { CSS as base } from "./base.js";
import { CSS as shell } from "./shell.js";
import { CSS as components } from "./components.js";

export function injectStyles() {
  if (document.querySelector("style[data-plugin-css='dsh-android-mobile/main.css']")) return () => {};
  const tag = document.createElement("style");
  tag.dataset.plugin = "dsh-android-mobile";
  tag.dataset.pluginCss = "dsh-android-mobile/main.css";
  tag.textContent = base + shell + components;
  document.head.appendChild(tag);
  return () => tag.remove();
}
