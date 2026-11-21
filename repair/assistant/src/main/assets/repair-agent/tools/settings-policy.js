/** Closed JSON model settings policy, before official ConfigEditor touches YAML. */
export const MODEL_NAMESPACES = Object.freeze(['llm-deepseek', 'llm-pi-ai', 'agent-default-model']);
const forbidden = new Set(['__jsExpr', '__proto__', 'prototype', 'constructor']);
export function assertSettingsJson(value) {
  let count = 0;
  const seen = new Set();
  const visit = (node, depth) => {
    if (++count > 50_000 || depth > 48) throw new Error('REPAIR_SETTINGS_TOO_COMPLEX');
    if (node === null || typeof node === 'string' || typeof node === 'boolean' || typeof node === 'number' && Number.isFinite(node)) return;
    if (!node || typeof node !== 'object' || seen.has(node)) throw new Error('REPAIR_SETTINGS_JSON_ONLY');
    seen.add(node);
    if (!Array.isArray(node) && Object.getPrototypeOf(node) !== Object.prototype && Object.getPrototypeOf(node) !== null) throw new Error('REPAIR_SETTINGS_JSON_ONLY');
    for (const key of Reflect.ownKeys(node)) {
      if (Array.isArray(node) && key === 'length') continue;
      if (typeof key !== 'string' || forbidden.has(key)) throw new Error('REPAIR_SETTINGS_FORBIDDEN_KEY');
      const descriptor = Object.getOwnPropertyDescriptor(node, key);
      if (!descriptor || !Object.hasOwn(descriptor,'value') || !descriptor.enumerable) throw new Error('REPAIR_SETTINGS_JSON_ONLY');
      visit(descriptor.value, depth + 1);
    }
    seen.delete(node);
  };
  visit(value, 0);
  if (Buffer.byteLength(JSON.stringify(value),'utf8') > 256 * 1024) throw new Error('REPAIR_SETTINGS_TOO_LARGE');
  return value;
}
export function assertModelNamespace(namespace) {
  if (!MODEL_NAMESPACES.includes(namespace)) throw new Error('REPAIR_SETTINGS_NAMESPACE_FORBIDDEN');
}
