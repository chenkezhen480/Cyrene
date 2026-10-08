const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');
const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
const page = source.slice(source.indexOf('const AuditPage = {'), source.indexOf('const ModelConfigPage = {'));
const contract = source.slice(source.indexOf('function requirePageResponse'), source.indexOf('function requireArrayResponse'));
test('load more reuses stats and reads the next cursor without rescanning counts', async () => {
  let statsCalls = 0; const cursors = [];
  const state = runInNewContext(page + contract + '\nAuditPage.setup();', {
    Icons: {}, EmptyState: {}, ref: value => ({ value }), onMounted() {},
    inject: key => key === 't' ? value => value : { value: 'owner' },
    CyreneAPI: {
      async listTraces(limit, cursor, owner) {
        cursors.push(cursor); assert.equal(owner, 'owner');
        return { items: [{ traceId: cursor ? 'second' : 'first' }], pageInfo: { limit, nextCursor: cursor ? '' : 'next', hasMore: !cursor } };
      },
      async getTraceStats() { statsCalls++; return { count: 2, retentionDays: 30 }; },
    },
  });
  await state.loadTraces(); await state.loadTraces(true);
  assert.deepEqual(cursors, ['', 'next']); assert.equal(statsCalls, 1);
  assert.equal(Array.from(state.traces.value, row => row.traceId).join(','), 'first,second');
  assert.equal(state.hasMore.value, false);
});
