const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');
const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
const page = source.slice(source.indexOf('const GuidePage = {'), source.indexOf('const TraceAuditPage = {'));
const contracts = source.slice(source.indexOf('function requireKnowledgeStatus'), source.indexOf('function requireArrayResponse'));
const modelConfig = { sections: ['chat', 'embedding'].map(id => ({ id, fields: ['provider', 'model'].map(name => ({ key: id + '.' + name, value: name === 'provider' ? 'openai' : 'test-model', configured: true, runtimeSynchronized: true })) })) };
function setup(overrides = {}) {
  const calls = [];
  const hooks = {};
  const state = runInNewContext(page + contracts + '\nGuidePage.setup();', {
    ref: value => ({ value }), inject: key => key === 't' ? value => value : { value: key === 'userId' ? 'owner' : 'none' },
    onActivated: callback => { hooks.activate = callback; }, onDeactivated: callback => { hooks.deactivate = callback; },
    CyreneAPI: {
      async getModelConfiguration() { calls.push('models'); return modelConfig; },
      async getKnowledgeStatus() { return { state: 'ready', message: '' }; },
      async getConfig() { return { config: { endpoints: [{ path: '/example' }] } }; },
      async listCollections(options) { calls.push(['collections', options]); return { items: ['docs'], pageInfo: { limit: 1, nextCursor: 'next', hasMore: true } }; },
      async getGraphStatus() { return { enabled: true }; },
      async listGraphSpaces(options) { calls.push(['spaces', options]); return { items: [{ graphId: 'business', schemaId: 'schema' }], pageInfo: { limit: 1, nextCursor: '', hasMore: false } }; },
      ...overrides,
    },
  });
  return { state, calls, hooks };
}
test('guide uses one model read and bounded list pages for actual setup status', async () => {
  const { state, calls, hooks } = setup();
  await hooks.activate();
  assert.equal(state.statuses.value.chat.state, 'saved');
  assert.equal(state.statuses.value.embedding.state, 'saved');
  assert.equal(state.statuses.value.project.state, 'ready');
  assert.equal(state.statuses.value.knowledge.state, 'ready');
  assert.equal(state.statuses.value.graph.state, 'ready');
  assert.equal(calls.filter(call => call === 'models').length, 1);
  assert.equal(calls.find(call => call[0] === 'collections')[1].limit, 1);
  assert.equal(calls.find(call => call[0] === 'spaces')[1].limit, 1);
});
test('missing project config is pending while forbidden reads remain explicit errors', async () => {
  const { state } = setup({
    async getConfig() { throw Object.assign(new Error('No config'), { code: 'NOT_FOUND', status: 404 }); },
    async getModelConfiguration() { throw new Error('Permission denied'); },
    async getGraphStatus() { return { enabled: false }; },
  });
  await state.loadStatus();
  assert.equal(state.statuses.value.project.state, 'pending');
  assert.equal(state.statuses.value.chat.state, 'unknown');
  assert.equal(state.statuses.value.chat.message, 'Permission denied');
  assert.equal(state.statuses.value.graph.state, 'disabled');
});
test('leaving the guide invalidates delayed status reads', async () => {
  let finish;
  const { state, hooks } = setup({ getModelConfiguration: () => new Promise(resolve => { finish = resolve; }) });
  const loading = hooks.activate();
  hooks.deactivate();
  finish(modelConfig);
  await loading;
  assert.equal(state.statuses.value.chat, undefined);
  assert.equal(state.loading.value, false);
});
