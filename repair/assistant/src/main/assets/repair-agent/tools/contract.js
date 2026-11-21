/** Closed model-to-native arguments. Native owns roots, target, grant and consent. */
export const PROTOCOL_VERSION = 1;
export const MAX_MESSAGE_BYTES = 768 * 1024;
// A decoded transfer/candidate payload bound, NOT a bound on the target file.
export const MAX_CHUNK_BYTES = 128 * 1024;
export const TIMEOUT_MS = 180_000;
export const MAX_IN_FLIGHT = 8;
const field = (kind, required = true) => Object.freeze({ kind, required });
export const METHODS = Object.freeze({
  status: {},
  evidence: {},
  list_files: { path: field('directory', false), offset: field('offset', false), limit: field('limit', false) },
  read_file: { path: field('path'), offset: field('offset', false), length: field('length', false), encoding: field('encoding', false) },
  stage_edit: { path: field('path'), expected_sha256: field('sha256'), content: field('content'), encoding: field('encoding', false), mode: field('mode', false) },
  stage_patch: { path: field('path'), expected_sha256: field('sha256'), offset: field('offset'), delete_bytes: field('offset'), content: field('content'), encoding: field('encoding', false), mode: field('mode', false) },
  stage_file: { path: field('path'), operation: field('operation'), expected_sha256: field('sha256', false), content: field('content', false), encoding: field('encoding', false), mode: field('mode', false) },
  list_changes: {},
  apply_changes: { change_id: field('change_id') },
  rollback: { change_id: field('change_id') },
  launch_target: {},
  validate_target: { level: field('validation_level') },
  verify_target: {},
});
const ROOT = /^(?:app|device_app|engine|external_(?:files|cache|media)(?:_[1-9][0-9]*)?)$/;
export function validPath(path, directory = false) {
  if (directory && path === '') return true;
  if (typeof path !== 'string' || path.length > 4096 || !path.isWellFormed() || /[\0\\]/u.test(path)) return false;
  const parts = path.split('/');
  return ROOT.test(parts[0]) && (directory || parts.length > 1) &&
    !parts.some(part => part === '' || part === '.' || part === '..');
}
function contentBytes(content, encoding) {
  if (typeof content !== 'string' || !content.isWellFormed()) throw new Error('INVALID_FILE_CONTENT');
  if (encoding === 'base64') {
    // Buffer.from alone silently accepts malformed/truncated base64.
    if (content.length > 4 * Math.ceil(MAX_CHUNK_BYTES / 3) || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(content)) throw new Error('INVALID_FILE_CONTENT');
    const bytes = Buffer.from(content, 'base64');
    if (bytes.toString('base64') !== content) throw new Error('INVALID_FILE_CONTENT');
    return bytes.length;
  }
  return Buffer.byteLength(content, 'utf8');
}
export function validateArgs(method, args) {
  // Internal activity is never exposed as a model tool and carries no model data.
  if (method === 'activity' && args && typeof args === 'object' && !Array.isArray(args) && Object.keys(args).length === 0) return args;
  if (!Object.hasOwn(METHODS, method) || !args || typeof args !== 'object' || Array.isArray(args)) throw new Error('INVALID_ARGUMENTS');
  const fields = METHODS[method];
  if (Object.keys(args).some(key => !Object.hasOwn(fields, key))) throw new Error('INVALID_ARGUMENTS');
  for (const [key, { kind, required }] of Object.entries(fields)) {
    if (!Object.hasOwn(args, key)) {
      if (required) throw new Error('INVALID_ARGUMENTS');
      continue;
    }
    const value = args[key];
    if ((kind === 'path' || kind === 'directory') && !validPath(value, kind === 'directory')) throw new Error('PATH_OUTSIDE_REPAIR_SCOPE');
    if (kind === 'sha256' && (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value))) throw new Error('INVALID_SHA256');
    if (kind === 'change_id' && (typeof value !== 'string' || !/^[a-zA-Z0-9_-]{1,128}$/.test(value))) throw new Error('INVALID_CHANGE_ID');
    if (kind === 'validation_level' && !['syntax', 'engine'].includes(value)) throw new Error('INVALID_VALIDATION_LEVEL');
    if (kind === 'encoding' && !['utf8', 'base64'].includes(value)) throw new Error('INVALID_ENCODING');
    if (kind === 'operation' && !['create', 'delete', 'mkdir', 'rmdir'].includes(value)) throw new Error('INVALID_OPERATION');
    if (['offset', 'length', 'limit', 'mode'].includes(kind) && (!Number.isSafeInteger(value) || value < 0 || (kind === 'length' && (value < 1 || value > MAX_CHUNK_BYTES)) || (kind === 'limit' && (value < 1 || value > 200)) || (kind === 'mode' && value > 0o777))) throw new Error('INVALID_ARGUMENTS');
  }
  if (Object.hasOwn(args, 'content') && contentBytes(args.content, args.encoding ?? 'utf8') > MAX_CHUNK_BYTES) throw new Error('INVALID_FILE_CONTENT');
  if (method === 'stage_patch' && !Number.isSafeInteger(args.offset + args.delete_bytes)) throw new Error('INVALID_ARGUMENTS');
  if (Object.hasOwn(args, 'mode') && (method === 'stage_edit' || method === 'stage_patch' || (method === 'stage_file' && ['create', 'mkdir'].includes(args.operation)))) {
    const needed = args.operation === 'mkdir' ? 0o500 : 0o400;
    if ((args.mode & needed) !== needed) throw new Error('unsupported_mode');
  }
  if (method === 'stage_file') {
    const has = key => Object.hasOwn(args, key);
    if (args.operation === 'delete') {
      if (!has('expected_sha256') || has('content') || has('encoding') || has('mode')) throw new Error('INVALID_ARGUMENTS');
    } else if (has('expected_sha256') || (args.operation !== 'create' && (has('content') || has('encoding'))) || (args.operation === 'rmdir' && has('mode'))) throw new Error('INVALID_ARGUMENTS');
  }
  return args;
}
