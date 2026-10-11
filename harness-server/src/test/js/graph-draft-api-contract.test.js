const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');

test('all draft client operations target routes registered by the server', async () => {
  const server = readFileSync(join(__dirname, '../../main/java/com/harness/server/Main.java'), 'utf8');
  const routes = new Set(Array.from(server.matchAll(/apiRoutes\.route\(app,\s*"([A-Z]+)",\s*"([^"]+)"/g),
    match => `${match[1]} ${match[2]}`));
  const calls = [];
  const client = readFileSync(join(__dirname, '../../main/resources/public/js/api.js'), 'utf8');
  const api = runInNewContext(client + '\nCyreneAPI;', {
    URLSearchParams,
    fetch: async (path, request) => {
      calls.push({ path, method: request.method });
      return { ok: true, headers: { get: () => null }, json: async () => ({}) };
    },
  });
  await api.getGraphDraft('draft-1', 'user', { expectedContentHash: 'hash' });
  await api.getGraphDraftChanges('draft-1', 'user', { expectedContentHash: 'hash', cursor: 'cursor' });
  await api.saveGraphDraft({ sourceDraftId: 'draft-1', expectedSourceContentHash: 'hash' });
  await api.applyGraphDraft('draft-1', 'user', 'hash');
  assert.equal(calls.length, 4);
  for (const call of calls) {
    const path = new URL(call.path, 'http://localhost').pathname.replace('draft-1', '{draftId}');
    assert.ok(routes.has(`${call.method} ${path}`), `Client route is not registered: ${call.method} ${path}`);
  }
});

test('draft conflict preserves the HTTP status and exact current-version ApiError details', async () => {
  const client = readFileSync(join(__dirname, '../../main/resources/public/js/api.js'), 'utf8');
  const details = { currentDraftId: 'draft-current', currentContentHash: 'hash-current' };
  const api = runInNewContext(client + '\nCyreneAPI;', {
    URLSearchParams,
    fetch: async () => ({
      ok: false, status: 409,
      headers: { get: () => null },
      json: async () => ({ code: 'CONFLICT', message: 'draft has a newer version', details }),
    }),
  });
  await assert.rejects(api.getGraphDraft('draft-old', 'alice', { expectedContentHash: 'hash-old' }), error => {
    assert.equal(error.status, 409);
    assert.equal(error.code, 'CONFLICT');
    assert.equal(error.message, 'draft has a newer version');
    assert.deepEqual(error.details, details);
    return true;
  });
});
