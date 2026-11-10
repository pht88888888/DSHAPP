import { randomUUID } from 'node:crypto';

/** 待创建计划及提交确认；失败保留计划，重试沿用调用方的 requestId。 */
export function createOutbox({ send, onAccepted, onFailed, requestId = randomUUID }) {
	const plans = new Map();
	const attempts = new Map();
	return {
		planOf: (id) => plans.get(id),
		setPlan(id, plan) { plans.set(id, { ...plan }); },
		selectModel(id, model) {
			const plan = plans.get(id);
			if (plan !== undefined) plans.set(id, { ...plan, model });
		},
		remove(id) {
			plans.delete(id);
			for (const [key, attempt] of attempts) if (attempt.sessionId === id) attempts.delete(key);
		},
		clear() { plans.clear(); attempts.clear(); },
		createBody(body) {
			return { ...body, requestId: typeof body.requestId === 'string' && body.requestId !== '' ? body.requestId : requestId() };
		},
		submit(body, plan) {
			const attempt = { sessionId: body.sessionId };
			attempts.set(body.requestId, attempt);
			return Promise.resolve().then(() => send(body)).then(() => {
				if (attempts.get(body.requestId) !== attempt) return;
				attempts.delete(body.requestId);
				// 旧请求的确认不能删掉用户在等待期间替换的新计划。
				if (plan !== undefined && plans.get(body.sessionId) === plan) plans.delete(body.sessionId);
				onAccepted(body.sessionId);
			}, (error) => {
				if (attempts.get(body.requestId) !== attempt) return;
				attempts.delete(body.requestId);
				onFailed(body, error);
			});
		},
	};
}
