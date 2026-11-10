/** 远程会话归属与持久映射；所有写入和落盘在同一 owner 内完成。 */
export function createSessionIndex({ load, saveSessions, saveMeta, saveWorkspaces, now = Date.now }) {
	const remote = load.remote();
	const synced = load.synced();
	const paths = load.paths();
	const models = load.models();
	const titles = load.titles();
	const times = load.times();
	const pins = load.pins();
	const syncedAt = load.syncedAt();
	const offsets = load.offsets();
	const workspaces = load.workspaces();
	const validId = (id) => typeof id === 'string' && id !== '';
	const persist = () => saveSessions(remote, synced, paths, titles, offsets, models);
	return {
		has: (id) => remote.has(id),
		isSynced: (id) => synced.has(id),
		ids: () => [...remote],
		syncedIds: () => [...synced],
		get size() { return remote.size; },
		pathOf: (id) => paths[id],
		modelOf: (id) => models[id],
		titleOf: (id) => titles[id],
		offsetOf: (id) => offsets[id],
		workspaceOf: (slug) => workspaces[slug],
		workspaceSnapshot: () => ({ ...workspaces }),
		metaSnapshot: () => ({ times: { ...times }, pins: { ...pins }, syncedAt: { ...syncedAt } }),
		add(id) { remote.add(id); },
		markSynced(id) { synced.add(id); },
		unmarkSynced(id) { synced.delete(id); },
		rememberPath(id, path) {
			if (validId(id) && typeof path === 'string' && path !== '') paths[id] = path;
		},
		rememberModel(id, model) { models[id] = model; persist(); },
		rememberTitle(id, title) {
			if (!validId(id) || typeof title !== 'string' || title === '') return;
			titles[id] = title;
			persist();
		},
		rememberTurnOffset(id, offset) {
			if (!validId(id) || !Number.isInteger(offset) || offset <= 0) return;
			offsets[id] = offset;
			persist();
		},
		rememberDesktopMeta(id, updatedAt, pinned) {
			if (!validId(id)) return;
			let dirty = false;
			if (typeof updatedAt === 'number' && Number.isFinite(updatedAt) && updatedAt >= 0 && times[id] !== updatedAt) {
				times[id] = updatedAt;
				dirty = true;
			}
			if (pinned === true && pins[id] !== true) { pins[id] = true; dirty = true; }
			else if (pinned === false && pins[id] === true) { delete pins[id]; dirty = true; }
			if (syncedAt[id] === undefined) { syncedAt[id] = now(); dirty = true; }
			if (dirty) saveMeta(times, pins, syncedAt);
		},
		rememberWorkspace(slug, path) {
			if (workspaces[slug] === path) return;
			workspaces[slug] = path;
			saveWorkspaces(workspaces);
		},
		registerListed(id, path, title) {
			if (remote.has(id)) return false;
			remote.add(id);
			synced.add(id);
			if (typeof title === 'string' && title !== '') titles[id] = title;
			if (paths[id] === undefined) paths[id] = path;
			return true;
		},
		remove(id) {
			remote.delete(id);
			synced.delete(id);
			delete paths[id];
			delete titles[id];
			persist();
		},
		clear() { remote.clear(); synced.clear(); persist(); },
		persist,
	};
}
