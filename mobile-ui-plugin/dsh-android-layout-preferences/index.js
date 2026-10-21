import z from '@deepseek-ai/schemastery';
export const name = 'dsh-android-layout-preferences';
export const inject = ['settings'];
export const Config = z.object({
  layoutMode: z.union(['auto', 'mobile', 'official']).description('自动、手机适配或官方响应式布局').volatile(),
  lastLayout: z.union(['mobile', 'official']).description('上次实际使用的布局，供自动模式在中间区间恢复').volatile(),
});
// Settings ownership only. The existing native Android client owns all UI,
// layout effects and cleanup. No HTTP route, frontend module or new layout.
export function apply(ctx) {
  ctx.effect(() => ctx.settings.configure({ auto: false }));
}
