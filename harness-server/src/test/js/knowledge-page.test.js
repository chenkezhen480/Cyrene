const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

// Exercise the actual page logic without a browser or a new build dependency.
const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
const page = source.slice(source.indexOf('const KnowledgePage = {'), source.indexOf('function requirePageResponse'));
const contract = source.slice(source.indexOf('function requirePageResponse'), source.indexOf('function requireArrayResponse'));

test('one upload processes every selected file, including a failure and a queued retry', async () => {
  const calls = [];
  const emptyPage = { items: [], pageInfo: { limit: 20, nextCursor: '', hasMore: false } };
  const api = {
    async uploadKnowledge(file, collection) {
      calls.push([file.name, collection]);
      if (file.name === 'failed.md') throw new Error('conversion failed');
      return { status: file.name === 'pending.md' ? 'pending' : 'indexed', documentId: file.name, jobId: file.name, message: 'retry queued' };
    },
    async listCollections() { return emptyPage; },
    async listKnowledge() { return emptyPage; },
    async listWiki() { return emptyPage; },
  };
  const state = runInNewContext(page + contract + '\nKnowledgePage.setup();', {
    EmptyState: {}, CyreneAPI: api,
    ref: value => ({ value }), computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : key === 'userId' ? { value: 'test-user' } : {},
    watch() {}, onMounted() {}, onUnmounted() {}, showToast() {}, clearTimeout, setTimeout,
    window: { confirm: () => true },
  });
  state.uploadCollection.value = '  docs  ';
  state.selectedFiles.value = ['ok.md', 'failed.md', 'pending.md', 'last.md'].map(name => ({ name }));
  state.fileInput.value = { value: 'selected files' };
  await state.uploadFiles();
  assert.deepEqual(calls.map(x => x[0]), ['ok.md', 'failed.md', 'pending.md', 'last.md']);
  assert.ok(calls.every(x => x[1] === 'docs'));
  assert.equal(Array.from(state.uploadQueue.value, x => x.status).join(','), 'indexed,failed,pending,indexed');
  assert.equal(state.uploadQueue.value[1].message, 'conversion failed');
  assert.equal(state.uploadQueue.value[2].message, 'retry queued');
  assert.equal(state.uploadedCount.value, 4);
  assert.equal(state.uploading.value, false);
  assert.equal(state.selectedFiles.value.length, 0);
  assert.equal(state.fileInput.value.value, '');
  state.collectionInput.value = '  arbitraryTable  ';
  state.applyCollection();
  assert.equal(state.selectedCollection.value, 'arbitraryTable');
});


test('global Wiki drawer preserves drafts on cancelled close and exports without table filters', async () => {
  const exported = [];
  let confirmation = false;
  const drawer = { open: false, showModal() { this.open = true; }, close() { this.open = false; } };
  const state = runInNewContext(page + contract + '\nKnowledgePage.setup();', {
    EmptyState: {},
    CyreneAPI: { async exportAllWiki(user) { exported.push(user); return {}; } },
    ref: value => ({ value }), computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : key === 'userId' ? { value: 'alice' } : {},
    watch() {}, onMounted() {}, onUnmounted() {}, showToast() {}, clearTimeout, setTimeout,
    window: { confirm: () => confirmation },
    document: { createElement: () => ({ click() {} }) },
    URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
  });
  state.wikiDrawer.value = drawer;
  state.selectedCollection.value = 'only-the-left-table';
  state.openWiki({ conceptId: 'wiki-1', revisionId: 'revision-1', conceptType: 'SOURCE_DOCUMENT', version: 1,
    namespaceKey: 'other-table', title: 'Saved title', summary: 'Saved summary', capability: '' });
  assert.equal(drawer.open, true);
  state.draftTitle.value = 'Unsaved title';
  state.closeWikiDrawer();
  assert.equal(drawer.open, true);
  assert.equal(state.draftTitle.value, 'Unsaved title');
  confirmation = true;
  state.closeWikiDrawer();
  assert.equal(drawer.open, false);
  assert.equal(state.draftTitle.value, 'Saved title');
  await state.downloadWiki(true);
  assert.deepEqual(exported, ['alice']);
  assert.equal(state.wikiExporting.value, false);
  assert.equal(state.wikiTypes.filter(x => x.value.startsWith('GRAPH_')).length, 1);
});
