const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

test('permission editing preserves grants on unloaded catalog pages and renders request errors', async () => {
  const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
  const start = source.indexOf('const ToolPermissionPage = {');
  const page = source.slice(start, source.indexOf('\n};', start) + 3);
  let saved;
  let failure;
  const pageResponse = items => ({ items, pageInfo: { limit: 50, hasMore: false, nextCursor: '' } });
  const state = runInNewContext(page + '\nToolPermissionPage.setup();', {
    EmptyState: {}, ref: value => ({ value }),
    computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : {}, onMounted() {}, showToast() {},
    requirePageResponse: page => page,
    CyreneAPI: {
      async getPermissionIdentities() { throw new Error('Tool permission denied'); },
      async getInternalApiEndpoints() { return pageResponse([{ endpointKey: 'trace.read' }]); },
      async getInternalApiPermissions() {
        if (failure) throw failure;
        return pageResponse([{ endpointKey: 'trace.read' }, { endpointKey: 'catalog.unloaded' }]);
      },
      async saveInternalApiPermissions(request) { saved = request; },
    },
  });
  state.permissionTab.value = 'internalApi';
  await state.load();
  state.toggleEndpoint('trace.read');
  await state.save();
  assert.deepEqual([...saved.allowedEndpointKeys], ['catalog.unloaded']);
  failure = new Error('Permission denied');
  await state.load();
  assert.equal(state.error.value, 'Permission denied');
  state.identity.value = 'another-identity';
  saved = undefined;
  await state.save();
  assert.equal(saved, undefined);
});
