const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
const page = source.slice(source.indexOf('const GraphDraftReviewPage = {'), source.indexOf('const GuidePage = {'));
const contracts = source.slice(source.indexOf('function requirePageResponse'), source.indexOf('// ── Graph Page ──'));
const helpers = source.slice(source.indexOf('function requireGraphDraftView('), source.indexOf('const GraphBuildPage = {'));
const draft = { draftId: 'v1', rootDraftId: 'root', contentHash: 'hash', graphId: 'graph', schemaId: 'schema', userId: 'business-user', sessionId: 'their-session', createdAt: '2026-10-11T00:00:00Z', status: 'PENDING', nodeCount: 1, relationCount: 0, deleteNodeCount: 0, deleteRelationCount: 0 };
function setup(overrides = {}) {
  const calls = []; const hooks = {};
  const state = runInNewContext(contracts + helpers + page + '\nGraphDraftReviewPage.setup({draftId:"",draftContentHash:""});', {
    GraphBuildPage: {}, EmptyState: {}, AbortController,
    ref: value => ({ value }), inject: key => key === 't' ? value => value : { value: 'admin' }, watch() {},
    onMounted: callback => { hooks.mount = callback; }, onUnmounted: callback => { hooks.unmount = callback; },
    CyreneAPI: {
      async getGraphStatus() { assert.fail('Tenant review must not require global graph management'); },
      async listGraphReviews(user, options) {
        calls.push({ user, options });
        return { items: [{ ...draft, rootDraftId: options.cursor ? 'root2' : 'root', draftId: options.cursor ? 'v2' : 'v1' }], pageInfo: { limit: 50, nextCursor: options.cursor ? '' : 'next', hasMore: !options.cursor } };
      }, ...overrides,
    },
  });
  return { state, calls, hooks };
}
test('administrator reads same-tenant submitters through bounded management pages', async () => {
  const { state, calls } = setup();
  await state.loadDrafts(); await state.loadDrafts(true);
  assert.equal(calls[0].user, 'admin');
  assert.equal(calls[0].options.status, 'PENDING');
  assert.equal(calls[0].options.limit, 50);
  assert.equal(calls[1].options.cursor, 'next');
  assert.equal(state.drafts.value.length, 2);
  assert.equal(state.drafts.value[0].userId, 'business-user');
  assert.equal(state.hasMore.value, false);
  assert.equal(state.graphDraftLink(draft, true), '#audit?tab=graphReview&draftId=v1&contentHash=hash');
});
test('permission errors render and disabled graph reports the missing management endpoint', async () => {
  const denied = setup({ async listGraphReviews() { throw new Error('Permission denied'); } });
  await denied.state.loadDrafts();
  assert.equal(denied.state.error.value, 'Permission denied');
  const disabled = setup({ async listGraphReviews() { throw Object.assign(new Error('missing'), { status: 404 }); } });
  await disabled.state.loadDrafts();
  assert.equal(disabled.state.enabled.value, false);
  assert.equal(disabled.state.error.value, 'graphProviderDisabledHint');
  assert.equal(disabled.calls.length, 0);
});
test('unmount invalidates delayed pages and duplicate draft chains surface contract errors', async () => {
  let finish;
  const delayed = setup({ listGraphReviews: () => new Promise(resolve => { finish = resolve; }) });
  const loading = delayed.state.loadDrafts();
  await new Promise(resolve => setImmediate(resolve));
  delayed.hooks.unmount();
  finish({ items: [draft], pageInfo: { limit: 50, nextCursor: '', hasMore: false } });
  await loading;
  assert.equal(delayed.state.drafts.value.length, 0);
  const duplicated = setup({ async listGraphReviews() { return { items: [draft, draft], pageInfo: { limit: 50, nextCursor: '', hasMore: false } }; } });
  await duplicated.state.loadDrafts();
  assert.equal(duplicated.state.error.value, 'graphInvalidDraftResponse');
});
test('audit direct link opens review instead of mounting the trace page first', () => {
  const wrapper = source.slice(source.indexOf('const AuditPage = {'), source.indexOf('// ── Model Configuration Page ──'));
  let hash = '#audit?tab=graphReview&draftId=v1&contentHash=hash';
  const location = { get hash() { return hash; }, set hash(value) { hash = value.startsWith('#') ? value : '#' + value; } };
  const state = runInNewContext(wrapper + '\nAuditPage.setup();', {
    TraceAuditPage: {}, GraphDraftReviewPage: {}, URLSearchParams, ref: value => ({ value }),
    inject: () => key => key, onActivated() {}, onMounted() {}, onUnmounted() {},
    window: { location, addEventListener() {}, removeEventListener() {} },
  });
  assert.equal(state.activeTab.value, 'graphReview');
  assert.equal(state.draftId.value, 'v1');
  state.selectTab('traces');
  assert.equal(state.activeTab.value, 'traces');
  assert.equal(state.draftId.value, '');
});
