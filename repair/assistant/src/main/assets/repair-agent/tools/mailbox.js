import { constants } from 'node:fs';
import { open, lstat, realpath, rename, unlink } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { MAX_MESSAGE_BYTES, MAX_IN_FLIGHT, TIMEOUT_MS, validateArgs } from './contract.js';

export class MailboxError extends Error {
  constructor(code) { super(code); this.name = 'MailboxError'; this.code = code; }
}
async function directory(path) {
  const stat = await lstat(path);
  if (!stat.isDirectory() || stat.isSymbolicLink() || await realpath(path) !== path) throw new MailboxError('INVALID_MAILBOX_DIRECTORY');
}
async function readBounded(path) {
  const fd = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const stat = await fd.stat();
    if (!stat.isFile() || stat.size > MAX_MESSAGE_BYTES) throw new MailboxError('INVALID_MAILBOX_RESPONSE');
    const bytes = Buffer.alloc(Math.min(stat.size + 1, MAX_MESSAGE_BYTES + 1));
    let length = 0;
    while (length < bytes.length) {
      const { bytesRead } = await fd.read(bytes, length, bytes.length - length, null);
      if (!bytesRead) break;
      length += bytesRead;
    }
    if (length > MAX_MESSAGE_BYTES || length !== stat.size) throw new MailboxError('INVALID_MAILBOX_RESPONSE');
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, length)));
  } finally { await fd.close(); }
}
export function createMailbox({ root = process.env.DSH_REPAIR_MAILBOX, timeoutMs = TIMEOUT_MS, pollMs = 50 } = {}) {
  if (typeof root !== 'string' || !isAbsolute(root) || resolve(root) !== root || !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > TIMEOUT_MS || !Number.isFinite(pollMs) || pollMs < 1) throw new MailboxError('INVALID_MAILBOX_CONFIGURATION');
  const requestDir = join(root, 'request');
  const responseDir = join(root, 'response');
  let active = 0;
  const bindingFile = join(root, 'binding.json');
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  async function currentBinding() {
    await directory(root);
    let value;
    try { value = await readBounded(bindingFile); }
    catch (error) { if (error.code === 'ENOENT') throw new MailboxError('REPAIR_AUTHORIZATION_REQUIRED'); throw error; }
    if (!value || Object.keys(value).join(',') !== 'binding' || !uuid.test(value.binding)) throw new MailboxError('INVALID_MAILBOX_BINDING');
    return value.binding;
  }
  return Object.freeze({
    currentBinding,
    async call(method, args, { signal, binding } = {}) {
      validateArgs(method, args);
      signal?.throwIfAborted();
      if (active >= MAX_IN_FLIGHT) throw new MailboxError('MAILBOX_BUSY');
      active++;
      const id = randomUUID();
      const request = join(requestDir, `${id}.json`);
      const temporary = join(requestDir, `${id}.tmp`);
      const response = join(responseDir, `${id}.json`);
      try {
        await directory(root); await directory(requestDir); await directory(responseDir);
        const current = await currentBinding();
        if (binding !== undefined && binding !== current) throw new MailboxError('REPAIR_BINDING_CHANGED_NEW_SESSION_REQUIRED');
        const text = JSON.stringify({ id, method, args, binding: binding ?? current });
        if (Buffer.byteLength(text, 'utf8') > MAX_MESSAGE_BYTES) throw new MailboxError('REQUEST_TOO_LARGE');
        const file = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
        try { await file.writeFile(text, 'utf8'); await file.sync(); } finally { await file.close(); }
        signal?.throwIfAborted();
        await rename(temporary, request);
        const deadline = performance.now() + timeoutMs;
        while (true) {
          signal?.throwIfAborted();
          try {
            const reply = await readBounded(response);
            if (!reply || Array.isArray(reply) || typeof reply !== 'object' || reply.id !== id || typeof reply.ok !== 'boolean' || Object.keys(reply).some(key => !['id', 'ok', 'result', 'error'].includes(key))) throw new MailboxError('INVALID_MAILBOX_RESPONSE');
            if (!reply.ok) throw new MailboxError(typeof reply.error === 'string' && /^[a-zA-Z0-9_]{1,80}$/.test(reply.error) ? reply.error : 'NATIVE_REQUEST_REJECTED');
            if (!Object.hasOwn(reply, 'result') || reply.result === null || Array.isArray(reply.result) || typeof reply.result !== 'object') throw new MailboxError('INVALID_MAILBOX_RESPONSE');
            return reply.result;
          } catch (error) {
            if (error.code !== 'ENOENT') throw error;
          }
          if (performance.now() >= deadline) throw new MailboxError('MAILBOX_TIMEOUT');
          await delay(Math.min(pollMs, Math.max(1, deadline - performance.now())), undefined, { signal });
        }
      } finally {
        active--;
        // These paths are minted above, under a verified private mailbox, never model-supplied.
        await Promise.all([temporary, request, response].map(path => unlink(path).catch(error => { if (error.code !== 'ENOENT') throw error; })));
      }
    },
  });
}
