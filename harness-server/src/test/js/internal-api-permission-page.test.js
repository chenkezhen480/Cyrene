const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

test('permission editing preserves denials on unloaded catalog pages and renders request errors', async () => {
  const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
  const start = source.indexOf('const ToolPermissionPage = {');
  const page = source.slice(start, source.indexOf('\n};', start) + 3);
  let saved;
  let failure;
  let disabledKeys = ['trace.read', 'catalog.unloaded'];
  const pageResponse = items => ({ items, pageInfo: { limit: 50, hasMore: false, nextCursor: '' } });
  const state = runInNewContext(page + '\nToolPermissionPage.setup({ internalApi: true });', {
    EmptyState: {}, ref: value => ({ value }),
    computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : {}, onMounted() {}, showToast() {},
    requirePageResponse: page => page,
    CyreneAPI: {
      async getPermissionIdentities() { throw new Error('Tool permission denied'); },
      async getInternalApiEndpoints() { return pageResponse([{ endpointKey: 'trace.read' }]); },
      async getInternalApiPermissions() {
        if (failure) throw failure;
        return pageResponse(disabledKeys.map(endpointKey => ({ endpointKey })));
      },
      async saveInternalApiPermissions(request) { saved = request; disabledKeys = [...request.disabledEndpointKeys]; },
    },
  });
  await state.load();
  assert.equal(state.disabledEndpointKeys.value.has('trace.read'), true);
  state.toggleEndpoint('trace.read');
  assert.equal(state.disabledEndpointKeys.value.has('trace.read'), false);
  await state.save();
  assert.deepEqual([...saved.disabledEndpointKeys], ['catalog.unloaded']);
  assert.equal('allowedEndpointKeys' in saved, false);
  assert.equal(state.disabledEndpointKeys.value.has('trace.read'), false);
  state.toggleEndpoint('trace.read');
  await state.save();
  assert.deepEqual([...saved.disabledEndpointKeys], ['catalog.unloaded', 'trace.read']);
  failure = new Error('Permission denied');
  await state.load();
  assert.equal(state.error.value, 'Permission denied');
  state.identity.value = 'another-identity';
  saved = undefined;
  await state.save();
  assert.equal(saved, undefined);
});
