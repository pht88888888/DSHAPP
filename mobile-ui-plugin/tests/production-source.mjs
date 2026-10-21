import assert from 'node:assert/strict';
import vm from 'node:vm';

const escapeRegExp = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// These tests execute production text, not rewritten implementations. Formatting
// is not an API: transferred source can have mixed tabs/spaces and moved helpers.
// Node's parser chooses the first complete declaration, independent of indent.
export function exactFunction(source, name) {
  const match = new RegExp(`^[ \\t]*function ${escapeRegExp(name)}\\s*\\(`, 'm').exec(source);
  assert.ok(match, `Missing production function ${name}`);
  const tail = source.slice(match.index);
  for (const closing of tail.matchAll(/^[ \t]*\}[ \t]*;?[ \t]*$/gm)) {
    const text = tail.slice(0, closing.index + closing[0].length);
    try {
      new vm.Script(text, { filename: `${name}.production.js` });
      return text;
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error;
    }
  }
  assert.fail(`Cannot extract complete production function ${name}`);
}

export function exactDeclaration(source, name) {
  const match = new RegExp(`\\b(?:const|let|var) ${escapeRegExp(name)}\\s*=`).exec(source);
  assert.ok(match, `Missing production declaration ${name}`);
  for (let end = source.indexOf(';', match.index); end !== -1; end = source.indexOf(';', end + 1)) {
    const text = source.slice(match.index, end + 1);
    try {
      new vm.Script(text, { filename: `${name}.production.js` });
      return text;
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error;
    }
  }
  assert.fail(`Cannot extract production declaration ${name}`);
}
