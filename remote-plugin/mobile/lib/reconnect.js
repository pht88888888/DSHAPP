/** 远程连接登记、退避唤醒与连续历史补拉。 */
export function createReconnect({ readRange, isWireEvent, warn, setTimer = setTimeout, clearTimer = clearTimeout }) {
	const follows = new Map();
	const streams = new Set();
	const wakers = new Set();
	function stop(id) {
		const controller = follows.get(id);
		follows.delete(id);
		try { controller?.abort(); } catch { /* 已断开 */ }
		for (const stream of streams) if (stream.id === id) stream.abort();
	}
	return {
		has: (id) => follows.has(id),
		get size() { return follows.size; },
		start(id) {
			if (follows.has(id)) return undefined;
			const controller = new AbortController();
			follows.set(id, controller);
			return controller;
		},
		finish(id, controller) { if (follows.get(id) === controller) follows.delete(id); },
		stop,
		stopAll() {
			for (const id of [...follows.keys()]) stop(id);
			for (const stream of streams) stream.abort();
			this.wakeAll();
		},
		createStream(id, fromSeq = -1, signal) {
			let cursor = fromSeq;
			let wait = 1000;
			let revision = 0;
			let lastEventAt = Date.now();
			let lastCheckAt = 0;
			const lifetime = new AbortController();
			let controller;
			let watchdog;
			const onAbort = () => lifetime.abort();
			signal?.addEventListener?.('abort', onAbort);
			if (signal?.aborted) lifetime.abort();
			const stream = {
				id,
				get signal() { return lifetime.signal; },
				get cursor() { return cursor; },
				get wait() { return wait; },
				get lastEventAt() { return lastEventAt; },
				advance(seq) { cursor = seq; },
				nextRevision() { return ++revision; },
				connected() { wait = 1000; },
				backoff(lived, stableMs) { wait = lived >= stableMs ? 1000 : Math.min(wait * 2, 15000); },
				touch() { lastEventAt = Date.now(); },
				shouldCheck(now, interval) {
					if (now - lastEventAt <= interval || now - lastCheckAt <= interval) return false;
					lastCheckAt = now;
					return true;
				},
				connect() {
					this.disconnect();
					controller = new AbortController();
					lifetime.signal.addEventListener('abort', abortConnection);
					if (lifetime.signal.aborted) controller.abort();
					return controller;
				},
				watch(check, interval) { watchdog = setInterval(check, interval); },
				disconnect() {
					clearInterval(watchdog);
					lifetime.signal.removeEventListener('abort', abortConnection);
					controller?.abort();
				},
				abort() { lifetime.abort(); this.disconnect(); },
				close() {
					this.abort();
					signal?.removeEventListener?.('abort', onAbort);
					streams.delete(stream);
				},
			};
			function abortConnection() { controller?.abort(); }
			streams.add(stream);
			return stream;
		},
		sleep(ms, signal) {
			if (signal?.aborted) return Promise.resolve();
			return new Promise((resolve) => {
				let done = false;
				const finish = () => {
					if (done) return;
					done = true;
					wakers.delete(finish);
					signal?.removeEventListener?.('abort', finish);
					clearTimer(timer);
					resolve();
				};
				const timer = setTimer(finish, ms);
				wakers.add(finish);
				signal?.addEventListener?.('abort', finish);
			});
		},
		wakeAll() {
			const count = wakers.size;
			for (const finish of [...wakers]) finish();
			return count;
		},
		async fillGap(config, sessionId, fromSeq, toSeq) {
			if (toSeq - fromSeq > 200) return null;
			let page;
			try { page = await readRange(config, 'readEventRange', { sessionId, fromSeq: fromSeq + 1, toSeq }); }
			catch (error) { warn(sessionId, fromSeq, toSeq, error); return null; }
			if (!Array.isArray(page?.events) || page.events.length === 0) return null;
			const sorted = page.events.filter((event) => isWireEvent(event) && event.seq > fromSeq && event.seq <= toSeq)
				.sort((left, right) => left.seq - right.seq);
			if (sorted.length === 0 || sorted[0].seq !== fromSeq + 1 || sorted.at(-1).seq !== toSeq) return null;
			for (let at = 1; at < sorted.length; at += 1) if (sorted[at].seq !== sorted[at - 1].seq + 1) return null;
			return sorted;
		},
	};
}
