const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
const helpers = source.slice(source.indexOf('function requireGraphDraftView('), source.indexOf('const GraphBuildPage = {'));

const view = { draftId: 'v1', rootDraftId: 'root', contentHash: 'hash', graphId: 'graph', schemaId: 'schema', sessionId: 'session', status: 'PENDING', nodeCount: 1, relationCount: 0, deleteNodeCount: 0, deleteRelationCount: 0 };
const before = { nodeId: 'A', labels: ['Person'], properties: { name: 'old', age: 2 } };
const after = { ...before, properties: { name: 'new', age: 2 } };
const changes = [{ changeId: 'node:A', entityType: 'NODE', operation: 'UPDATE', before, after }];

test('chat draft link reads the exact typed tool envelope and encodes the viewed hash', () => {
  const { graphDraftToolLink } = runInNewContext(helpers + '\n({graphDraftToolLink});');
  assert.equal(graphDraftToolLink({ status: 'SUCCESS', data: view, meta: { viewType: 'DRAFT_PREVIEW', requiresHumanConfirmation: true } }), '#graph?draftId=v1&contentHash=hash');
  assert.equal(graphDraftToolLink(view), '');
  assert.equal(graphDraftToolLink({ status: 'SUCCESS', data: view, meta: {} }), '');
  assert.equal(graphDraftToolLink({ status: 'SUCCESS', data: { draftId: 'v2', contentHash: 'hash2' }, meta: { viewType: 'DRAFT_PREVIEW', requiresHumanConfirmation: true } }), '');
});

test('current-version conflict fields form a valid navigation link without another target lookup', () => {
  const { graphDraftLink } = runInNewContext(helpers + '\n({graphDraftLink});');
  assert.equal(graphDraftLink({ draftId: 'v 2', contentHash: 'hash/2' }), '#graph?draftId=v%202&contentHash=hash%2F2');
  assert.equal(graphDraftLink({ draftId: '', contentHash: 'hash2' }), '');
});

test('draft continuation changes loaded entities and removes properties explicitly', () => {
  const { graphDraftContinuationPayload } = runInNewContext(helpers + '\n({graphDraftContinuationPayload});');
  const payload = graphDraftContinuationPayload(view, changes, {
    nodes: [{ ...after, properties: { name: 'final' } }], relations: [],
  }, 'alice');
  assert.equal(payload.sourceDraftId, 'v1');
  assert.equal(payload.expectedSourceContentHash, 'hash');
  assert.equal(payload.sessionId, 'session');
  assert.equal(payload.graphId, 'graph');
  assert.equal(payload.schemaId, 'schema');
  assert.deepEqual(JSON.parse(JSON.stringify(payload.nodes)), [{ nodeId: 'A', labels: ['Person'], properties: { name: 'final', age: null } }]);
  assert.equal(payload.deleteNodeIds.length, 0);
});

test('a visual deletion stays a pending draft change', () => {
  const { graphDraftContinuationPayload } = runInNewContext(helpers + '\n({graphDraftContinuationPayload});');
  const payload = graphDraftContinuationPayload(view, changes, { nodes: [], relations: [] }, 'alice');
  assert.deepEqual(Array.from(payload.deleteNodeIds), ['A']);
  assert.equal(payload.nodes.length, 0);
});

test('unloaded changes are preserved and unchanged preview creates no patches', () => {
  const { graphDraftContinuationPayload } = runInNewContext(helpers + '\n({graphDraftContinuationPayload});');
  const payload = graphDraftContinuationPayload(view, changes, { nodes: [after], relations: [] }, 'alice');
  assert.equal(payload.nodes.length, 0);
  assert.equal(payload.deleteNodeIds.length, 0);
  assert.equal(payload.discardChangeIds.length, 0);
});

test('property field order from a schema does not mark an unchanged draft dirty', () => {
  const { graphDraftContinuationPayload } = runInNewContext(helpers + '\n({graphDraftContinuationPayload});');
  const payload = graphDraftContinuationPayload(view, changes, {
    nodes: [{ ...after, properties: { age: 2, name: 'new' } }], relations: [],
  }, 'alice');
  assert.equal(payload.nodes.length, 0);
  assert.equal(payload.deleteNodeIds.length, 0);
  const removed = graphDraftContinuationPayload(view, changes, {
    nodes: [{ ...after, properties: { age: 2 } }], relations: [],
  }, 'alice');
  assert.equal(removed.nodes[0].properties.name, null);
});

test('JSON properties ignore nested object order and retain array order and value types', () => {
  const { graphJsonValuesEqual } = runInNewContext(helpers + '\n({graphJsonValuesEqual});');
  assert.equal(graphJsonValuesEqual({ data: { name: 'A', count: 2 }, items: [1, 2] },
    { items: [1, 2], data: { count: 2, name: 'A' } }), true);
  assert.equal(graphJsonValuesEqual({ items: [1, 2] }, { items: [2, 1] }), false);
  assert.equal(graphJsonValuesEqual({ age: 2 }, { age: '2' }), false);
  assert.equal(graphJsonValuesEqual({ name: null }, {}), false);
});

test('draft change pagination rejects a different version/hash', () => {
  const contract = source.slice(source.indexOf('function requirePageResponse'), source.indexOf('// ── Graph Page ──'));
  const { requireGraphDraftChangePage } = runInNewContext(contract + helpers + '\n({requireGraphDraftChangePage});');
  const page = { items: [{ ...changes[0], viewType: 'DRAFT_PREVIEW', draftId: 'v2', contentHash: 'other' }], pageInfo: { limit: 50, nextCursor: '', hasMore: false } };
  assert.throws(() => requireGraphDraftChangePage(page, view, key => key), /graphInvalidDraftResponse/);
});

function setupPage(apiOverrides = {}, uiOverrides = {}) {
  const graphPage = source.slice(source.indexOf('const GraphBuildPage = {'), source.indexOf('// ── Audit Page ──', source.indexOf('const GraphBuildPage = {')));
  const dataHelpers = source.slice(source.indexOf('const GRAPH_DATA_NUMBER_TYPES'), source.indexOf('const GraphDataPropertyEditor = {'));
  const contract = source.slice(source.indexOf('function requirePageResponse'), source.indexOf('// ── Graph Page ──'));
  const schema = { schemaId: 'schema', nodeTypes: { Person: { properties: { name: { type: 'STRING', required: true }, age: { type: 'INTEGER', required: false } } } }, relationTypes: {} };
  const calls = [];
  const api = {
    async getGraphDraft(id) { calls.push(['read', id]); return { ...view, draftId: id }; },
    async getGraphDraftChanges(id) { return { items: changes.map(change => ({ ...change, viewType: 'DRAFT_PREVIEW', draftId: id, contentHash: view.contentHash })), pageInfo: { limit: 50, nextCursor: '', hasMore: false } }; },
    async getGraphSchema() { return schema; },
    async saveGraphDraft(payload) { calls.push(['save', payload]); return { ...view, draftId: 'v2', contentHash: 'hash2' }; },
    async applyGraphDraft(...args) { calls.push(['apply', ...args]); return { committed: true, requestId: 'req' }; },
    async listGraphNodes() { assert.fail('draft mode must not load the whole graph'); },
    async deleteGraphNode() { assert.fail('draft mode must never call the immediate delete API'); },
    async buildGraph() { assert.fail('draft mode must use hash-confirmed draft apply'); },
    ...apiOverrides,
  };
  const state = runInNewContext(contract + helpers + dataHelpers + graphPage + '\nGraphBuildPage.setup({draftId:"v1",draftContentHash:"hash"});', {
    CyreneAPI: api, AbortController,
    ref: value => ({ value }), computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : key === 'userId' ? { value: 'alice' } : {},
    watch() {}, onMounted() {}, onUnmounted() {}, showToast() {}, confirm: () => true,
    window: { location: { hash: '#graph?draftId=v1' } },
    graphDraftLink: draft => `#graph?draftId=${draft.draftId}`, formatStructuredData: data => JSON.stringify(data),
    ...uiOverrides,
  });
  return { state, calls };
}

test('draft page loads only affected changes and saves deletion as an immutable continuation', async () => {
  const { state, calls } = setupPage({
    async getGraphDraftChanges(id) {
      return { items: changes.map(change => ({ ...change, viewType: 'DRAFT_PREVIEW', draftId: id, contentHash: id === 'v1' ? 'hash' : 'hash2' })), pageInfo: { limit: 50, nextCursor: '', hasMore: false } };
    },
    async getGraphDraft(id) { return { ...view, draftId: id, contentHash: id === 'v1' ? 'hash' : 'hash2' }; },
  });
  await state.openDraft();
  assert.equal(state.draftError.value, '');
  assert.equal(state.draftView.value.graphId, 'graph');
  assert.equal(state.draftDesignerModel.value.nodes[0].persisted, true);
  state.draftDesignerModel.value.nodes = [];
  assert.equal(state.draftHasEdits.value, true);
  await state.saveDraft();
  const save = calls.find(call => call[0] === 'save')[1];
  assert.equal(save.sourceDraftId, 'v1');
  assert.deepEqual(Array.from(save.deleteNodeIds), ['A']);
  assert.equal(calls.some(call => call[0] === 'apply'), false);
  assert.equal(state.draftView.value.draftId, 'v2');
});

test('draft application submits the viewed hash and blocks unsaved edits', async () => {
  const { state, calls } = setupPage();
  await state.openDraft();
  assert.equal(state.draftHasEdits.value, false);
  await state.applyDraft();
  assert.deepEqual(calls.find(call => call[0] === 'apply'), ['apply', 'v1', 'alice', 'hash']);
  state.draftDesignerModel.value.nodes = [];
  await state.applyDraft();
  assert.equal(calls.filter(call => call[0] === 'apply').length, 1);
});

test('an empty saved draft cannot invoke apply', async () => {
  const { state, calls } = setupPage({
    async getGraphDraft() { return { ...view, nodeCount: 0 }; },
    async getGraphDraftChanges() { return { items: [], pageInfo: { limit: 50, nextCursor: '', hasMore: false } }; },
  });
  await state.openDraft();
  assert.equal(state.draftError.value, '');
  assert.equal(state.draftCanApply.value, false);
  await state.applyDraft();
  assert.equal(calls.some(call => call[0] === 'apply'), false);
});

test('a permanent apply failure refreshes its terminal state without retrying the mutation', async () => {
  let failed = false;
  const { state, calls } = setupPage({
    async getGraphDraft() { return failed ? { ...view, status: 'FAILED', failure: { message: 'knowledge failed', graphCommitted: true } } : view; },
    async applyGraphDraft() { calls.push(['apply']); failed = true; throw new Error('knowledge failed'); },
  });
  await state.openDraft();
  await state.applyDraft();
  assert.equal(state.draftView.value.status, 'FAILED');
  assert.equal(state.draftView.value.failure.message, 'knowledge failed');
  assert.equal(state.draftBusy.value, false);
  assert.equal(state.draftEditable.value, false);
  assert.equal(state.draftCanApply.value, false);
  await state.applyDraft();
  assert.equal(calls.filter(call => call[0] === 'apply').length, 1);
});

test('a failed uncommitted draft remains editable but requires saving a new version', async () => {
  const { state } = setupPage({
    async getGraphDraft() { return { ...view, status: 'FAILED', failure: { message: 'baseline conflict', graphCommitted: false } }; },
  });
  await state.openDraft();
  assert.equal(state.draftError.value, '');
  assert.equal(state.draftEditable.value, true);
  assert.equal(state.draftCanApply.value, false);
});

test('a terminal uncommitted 409 refresh unlocks editing and saves an immutable continuation', async () => {
  let failed = false;
  let attempts = 0;
  const { state, calls } = setupPage({
    async getGraphDraft(id) {
      if (id === 'v2') return { ...view, draftId: id, contentHash: 'hash2' };
      return failed ? { ...view, status: 'FAILED', failure: { message: 'baseline conflict', graphCommitted: false } }
        : { ...view, status: 'APPLYING' };
    },
    async getGraphDraftChanges(id) { return changePage(id, id === 'v2' ? 'hash2' : 'hash'); },
    async applyGraphDraft() {
      attempts++;
      failed = true;
      throw Object.assign(new Error('baseline conflict'), { status: 409, code: 'CONFLICT' });
    },
  });
  await state.openDraft();
  await state.applyDraft();
  assert.equal(state.draftView.value.status, 'FAILED');
  assert.equal(state.draftError.value, 'baseline conflict');
  assert.equal(state.draftConflict.value, null);
  assert.equal(state.draftEditable.value, true);
  assert.equal(state.draftCanApply.value, false);
  state.setDraftEditorView('source');
  const desired = JSON.parse(state.draftSourceText.value);
  desired.nodes[0].properties.name = 'corrected';
  state.draftSourceText.value = JSON.stringify(desired);
  await state.saveDraft();
  const payload = calls.find(call => call[0] === 'save')[1];
  assert.equal(payload.sourceDraftId, 'v1');
  assert.equal(payload.expectedSourceContentHash, 'hash');
  assert.equal(payload.nodes[0].properties.name, 'corrected');
  assert.equal(state.draftView.value.draftId, 'v2');
  assert.equal(attempts, 1);
});

test('a typed version conflict remains locked after an uncommitted failure refresh', async () => {
  let failed = false;
  const { state } = setupPage({
    async getGraphDraft() { return failed ? { ...view, status: 'FAILED', failure: { message: 'failed', graphCommitted: false } } : view; },
    async applyGraphDraft() {
      failed = true;
      throw Object.assign(new Error('superseded'), {
        status: 409, code: 'CONFLICT', details: { currentDraftId: 'v2', currentContentHash: 'hash2' },
      });
    },
  });
  await state.openDraft();
  await state.applyDraft();
  assert.equal(state.draftConflict.value.currentDraftId, 'v2');
  assert.equal(state.draftEditable.value, false);
  assert.equal(state.draftCanApply.value, false);
});

test('stale hash and API failures render without an apply or a hidden retry', async () => {
  let readCalls = 0;
  const { state, calls } = setupPage({ async getGraphDraft() { readCalls++; throw new Error('stale draft hash'); } });
  await state.openDraft();
  assert.equal(state.draftError.value, 'stale draft hash');
  assert.equal(state.draftLoading.value, false);
  await state.applyDraft();
  assert.equal(readCalls, 1);
  assert.equal(calls.length, 0);
});

test('multi-label draft remains reviewable and edits through explicit JSON mode', async () => {
  const multiLabel = { ...after, labels: ['Person', 'Member'] };
  const { state, calls } = setupPage({
    async getGraphSchema() { return { schemaId: 'schema', nodeTypes: { Person: { properties: { name: { type: 'STRING' }, age: { type: 'INTEGER' } } }, Member: { properties: {} } }, relationTypes: {} }; },
    async getGraphDraftChanges() { return { items: [{ ...changes[0], before: multiLabel, after: multiLabel, viewType: 'DRAFT_PREVIEW', draftId: 'v1', contentHash: 'hash' }], pageInfo: { limit: 50, nextCursor: '', hasMore: false } }; },
  });
  await state.openDraft();
  assert.equal(state.draftError.value, '');
  assert.equal(state.draftEditorView.value, 'source');
  assert.match(state.draftVisualError.value, /graphDataSingleNodeTypeRequired/);
  const desired = JSON.parse(state.draftSourceText.value);
  desired.nodes[0].properties.name = 'final';
  state.draftSourceText.value = JSON.stringify(desired);
  await state.saveDraft();
  const payload = calls.find(call => call[0] === 'save')[1];
  assert.equal(payload.nodes[0].properties.name, 'final');
  assert.deepEqual(Array.from(payload.nodes[0].labels), ['Person', 'Member']);
});

function changePage(id, hash = 'hash', pageInfo = { limit: 50, nextCursor: '', hasMore: false }) {
  return { items: changes.map(change => ({ ...change, viewType: 'DRAFT_PREVIEW', draftId: id, contentHash: hash })), pageInfo };
}

test('a failed new draft preview clears the old editor and blocks every write', async () => {
  const { state, calls } = setupPage({
    async getGraphDraftChanges(id) {
      if (id === 'v2') throw new Error('preview unavailable');
      return changePage(id);
    },
  });
  await state.openDraft();
  assert.equal(state.draftDesignerModel.value.nodes.length, 1);
  await state.openDraft('v2', 'hash');
  assert.equal(state.draftError.value, 'preview unavailable');
  assert.equal(state.draftDesignerModel.value.nodes.length, 0);
  assert.equal(state.draftSourceText.value, '');
  assert.equal(state.draftSchema.value, null);
  assert.equal(state.draftReady.value, false);
  assert.equal(state.draftEditable.value, false);
  assert.equal(state.draftCanApply.value, false);
  await state.saveDraft();
  await state.applyDraft();
  assert.equal(calls.some(call => call[0] === 'save' || call[0] === 'apply'), false);
});

test('an obsolete pagination failure cannot clear the loading state of another draft', async () => {
  let rejectMore, resolveNewPreview;
  const more = new Promise((_, reject) => { rejectMore = reject; });
  const newPreview = new Promise(resolve => { resolveNewPreview = resolve; });
  const { state } = setupPage({
    async getGraphDraftChanges(id, userId, options) {
      if (id === 'v2') return newPreview;
      if (options.cursor) return more;
      return changePage(id, 'hash', { limit: 50, nextCursor: 'page2', hasMore: true });
    },
  });
  await state.openDraft();
  const oldLoad = state.loadMoreDraftChanges();
  const newLoad = state.openDraft('v2', 'hash');
  for (let i = 0; i < 8; i++) await Promise.resolve();
  rejectMore(new Error('old page failed'));
  await oldLoad;
  assert.equal(state.draftLoading.value, true);
  assert.equal(state.draftError.value, '');
  resolveNewPreview(changePage('v2'));
  await newLoad;
  assert.equal(state.draftView.value.draftId, 'v2');
});

test('an obsolete save response cannot navigate away from a newly opened draft', async () => {
  let resolveSave;
  const save = new Promise(resolve => { resolveSave = resolve; });
  const browser = { location: { hash: '#graph?draftId=v1' } };
  const { state } = setupPage({ async saveGraphDraft() { return save; } }, { window: browser });
  await state.openDraft();
  state.draftDesignerModel.value.nodes = [];
  const pendingSave = state.saveDraft();
  await state.openDraft('v3', 'hash');
  browser.location.hash = '#graph?draftId=v3';
  resolveSave({ ...view, draftId: 'v2', contentHash: 'hash2' });
  await pendingSave;
  assert.equal(state.draftView.value.draftId, 'v3');
  assert.equal(browser.location.hash, '#graph?draftId=v3');
});

test('APPLYING is read only but permits an explicitly confirmed retry of the same hash', async () => {
  const { state, calls } = setupPage({ async getGraphDraft() { return { ...view, status: 'APPLYING' }; } });
  await state.openDraft();
  assert.equal(state.draftEditable.value, false);
  assert.equal(state.draftCanApply.value, true);
  await state.saveDraft();
  assert.equal(calls.some(call => call[0] === 'save'), false);
  await state.applyDraft();
  assert.deepEqual(calls.find(call => call[0] === 'apply'), ['apply', 'v1', 'alice', 'hash']);
});

test('a failed apply remains retryable after a complete preview without reloading', async () => {
  let attempts = 0;
  const { state } = setupPage({
    async applyGraphDraft() {
      if (++attempts === 1) throw new Error('temporary commit failure');
      return { committed: true };
    },
  });
  await state.openDraft();
  await state.applyDraft();
  assert.equal(state.draftError.value, 'temporary commit failure');
  assert.equal(state.draftCanApply.value, true);
  await state.applyDraft();
  assert.equal(attempts, 2);
});

test('409 preserves local edits and opens the explicit current version only after confirmation', async () => {
  const conflict = Object.assign(new Error('draft was superseded'), {
    status: 409, code: 'CONFLICT', details: { currentDraftId: 'v2', currentContentHash: 'hash2' },
  });
  let allowDiscard = false;
  const reads = [];
  const { state } = setupPage({
    async getGraphDraft(id) { reads.push(id); return { ...view, draftId: id, contentHash: id === 'v2' ? 'hash2' : 'hash' }; },
    async getGraphDraftChanges(id) { return changePage(id, id === 'v2' ? 'hash2' : 'hash'); },
    async saveGraphDraft() { throw conflict; },
  }, { confirm: () => allowDiscard });
  await state.openDraft();
  state.setDraftEditorView('source');
  const localSource = JSON.parse(state.draftSourceText.value);
  localSource.nodes[0].properties.name = 'local edit';
  state.draftSourceText.value = JSON.stringify(localSource);
  await state.saveDraft();
  assert.equal(state.draftError.value, 'draft was superseded');
  assert.equal(JSON.parse(state.draftSourceText.value).nodes[0].properties.name, 'local edit');
  assert.equal(state.draftView.value.draftId, 'v1');
  assert.deepEqual(reads, ['v1']);
  assert.equal(state.draftConflict.value.currentDraftId, 'v2');
  assert.equal(state.draftEditable.value, false);
  assert.equal(state.draftCanApply.value, false);
  await state.openCurrentDraft();
  assert.deepEqual(reads, ['v1']);
  allowDiscard = true;
  await state.openCurrentDraft();
  assert.equal(state.draftView.value.draftId, 'v2');
  assert.equal(state.draftView.value.contentHash, 'hash2');
  assert.equal(state.draftHasEdits.value, false);
});

test('a stale initial link exposes the exact current draft reference without automatic reads', async () => {
  const conflict = Object.assign(new Error('stale link'), {
    status: 409, code: 'CONFLICT', details: { currentDraftId: 'v2', currentContentHash: 'hash2' },
  });
  let reads = 0;
  const { state } = setupPage({ async getGraphDraft() { reads++; throw conflict; } });
  await state.openDraft();
  assert.equal(reads, 1);
  assert.equal(state.draftConflict.value.currentContentHash, 'hash2');
  assert.equal(state.draftReady.value, false);
});

test('a schema failure clears all editor and pagination state from the previous version', async () => {
  let failSchema = false;
  const { state, calls } = setupPage({
    async getGraphSchema() {
      if (failSchema) throw new Error('schema unavailable');
      return { schemaId: 'schema', nodeTypes: { Person: { properties: { name: { type: 'STRING' }, age: { type: 'INTEGER' } } } }, relationTypes: {} };
    },
  });
  await state.openDraft();
  state.draftPageInfo.value = { limit: 50, nextCursor: 'old cursor', hasMore: true };
  failSchema = true;
  await state.openDraft('v2', 'hash');
  assert.equal(state.draftError.value, 'schema unavailable');
  assert.equal(state.draftReady.value, false);
  assert.equal(state.draftSourceText.value, '');
  assert.equal(state.draftVisualError.value, '');
  assert.equal(state.draftPageInfo.value.nextCursor, '');
  assert.equal(state.draftPageInfo.value.hasMore, false);
  await state.saveDraft();
  await state.applyDraft();
  assert.equal(calls.some(call => call[0] === 'save' || call[0] === 'apply'), false);
});

test('an obsolete apply response cannot reload another draft or display a stale success', async () => {
  let resolveApply;
  const pending = new Promise(resolve => { resolveApply = resolve; });
  const toasts = [];
  const { state } = setupPage({ async applyGraphDraft() { return pending; } }, { showToast: message => toasts.push(message) });
  await state.openDraft();
  const applying = state.applyDraft();
  await state.openDraft('v3', 'hash');
  resolveApply({ committed: true });
  await applying;
  assert.equal(state.draftView.value.draftId, 'v3');
  assert.equal(state.draftError.value, '');
  assert.deepEqual(toasts, []);
});

test('an obsolete apply failure cannot overwrite the error of a newly opened draft', async () => {
  let rejectApply;
  const pending = new Promise((_, reject) => { rejectApply = reject; });
  const { state } = setupPage({
    async applyGraphDraft() { return pending; },
    async getGraphDraftChanges(id) {
      if (id === 'v3') throw new Error('new preview failed');
      return changePage(id);
    },
  });
  await state.openDraft();
  const applying = state.applyDraft();
  await state.openDraft('v3', 'hash');
  rejectApply(new Error('obsolete apply failed'));
  await applying;
  assert.equal(state.draftView.value.draftId, 'v3');
  assert.equal(state.draftError.value, 'new preview failed');
  assert.equal(state.draftReady.value, false);
});

test('409 without the typed current-version fields blocks writes and offers no guessed navigation', async () => {
  let attempts = 0;
  const { state, calls } = setupPage({
    async applyGraphDraft() {
      attempts++;
      throw Object.assign(new Error('conflict missing metadata'), { status: 409, code: 'CONFLICT', details: { draftId: 'guessed' } });
    },
  });
  await state.openDraft();
  await state.applyDraft();
  assert.equal(state.draftError.value, 'conflict missing metadata');
  assert.equal(state.draftCanApply.value, false);
  assert.equal(state.draftEditable.value, false);
  assert.equal(state.draftConflict.value.currentDraftId, undefined);
  await state.openCurrentDraft();
  await state.applyDraft();
  await state.saveDraft();
  assert.equal(attempts, 1);
  assert.equal(calls.some(call => call[0] === 'save'), false);
});
