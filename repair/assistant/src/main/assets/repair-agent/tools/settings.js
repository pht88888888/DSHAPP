import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { isAbsolute, join, resolve } from 'node:path';
import { assertSettingsJson, assertModelNamespace } from './settings-policy.js';
const anchor=process.env.DSH_REPAIR_ENGINE_PACKAGE;
if (!anchor || !isAbsolute(anchor) || resolve(anchor)!==anchor) throw new Error('REPAIR_ENGINE_ANCHOR_REQUIRED');
const requireEngine=createRequire(anchor);
const {default:ConfigEditor}=await import(pathToFileURL(requireEngine.resolve('@deepseek-ai/dsh-config-editor')).href);

// No Remote endpoints. The official settings controller reaches this service
// only through schema-validated volatile field edits. Keep its atomic writing,
// lock, overlay-precedence validation and Loader reconciliation intact.
export default class RepairModelConfigEditor extends ConfigEditor {
  static inject = ['loader','profileContext'];
  constructor(ctx) { super(ctx); this.repairContext=ctx; }
  get documentPath() {
    const path=super.documentPath;
    const profileDir=process.env.DSH_REPAIR_PROFILE;
    const dir=resolve(profileDir ?? '');
    if (!isAbsolute(profileDir ?? '') || path !== join(dir,'cordis.patch.yml')) throw new Error('REPAIR_SETTINGS_PATH_FORBIDDEN');
    return path;
  }
  async edit(entry, change) {
    assertModelNamespace(entry?.options?.id);
    // Force the fixed path check before delegating to any filesystem operation.
    void this.documentPath;
    return super.edit(entry,(current,inherited) => {
      const next=change(current,inherited);
      assertSettingsJson(next);
      return next;
    });
  }
}
