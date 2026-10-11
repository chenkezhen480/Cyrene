const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
function component(name) {
  const start = source.indexOf(`const ${name} = {`);
  return source.slice(start, source.indexOf('\n};', start) + 3);
}
function projectPage(getConfig) {
  const hooks = {};
  const state = runInNewContext(component('ProjectApiConfigPage') + '\nProjectApiConfigPage.setup();', {
    EmptyState: {}, PreConfigModal: {}, ref: value => ({ value }),
    inject: key => key === 't' ? value => value : {},
    onMounted: callback => { hooks.mounted = callback; },
    onActivated: callback => { hooks.activated = callback; },
    onDeactivated: callback => { hooks.deactivated = callback; },
    CyreneAPI: { getConfig },
  });
  return { state, hooks };
}

test('interface configuration defaults to internal API configuration', () => {
  const state = runInNewContext(component('ConfigPage') + '\nConfigPage.setup();', {
    ProjectApiConfigPage: {}, ToolPermissionPage: {},
    ref: value => ({ value }), inject: () => value => value, onActivated() {},
  });
  assert.equal(state.activeTab.value, 'internalApi');
});

test('guide links select project APIs and API permissions while ordinary entry selects API permissions', () => {
  let activate;
  const location = { hash: '#config?tab=projectApis' };
  const state = runInNewContext(component('ConfigPage') + '\nConfigPage.setup();', {
    ProjectApiConfigPage: {}, ToolPermissionPage: {}, ref: value => ({ value }), inject: () => value => value,
    onActivated(callback) { activate = callback; }, window: { location }, URLSearchParams,
  });
  activate(); assert.equal(state.activeTab.value, 'projectApis');
  location.hash = '#config?tab=internalApi'; activate(); assert.equal(state.activeTab.value, 'internalApi');
  state.activeTab.value = 'projectApis';
  location.hash = '#config'; activate(); assert.equal(state.activeTab.value, 'internalApi');
});

test('missing project config opens setup on entry and on reentry', async () => {
  const { state, hooks } = projectPage(async () => {
    throw Object.assign(new Error('Config file not found'), { code: 'NOT_FOUND', status: 404 });
  });
  await hooks.activated();
  assert.equal(state.showPreConfig.value, true);
  hooks.deactivated();
  assert.equal(state.showPreConfig.value, false);
  await hooks.activated();
  assert.equal(state.showPreConfig.value, true);
});

test('existing config is read from its response envelope and errors do not open setup', async () => {
  let failure;
  const config = { endpoints: [] };
  const { state, hooks } = projectPage(async () => {
    if (failure) throw failure;
    return { path: '/config/project-apis.json', config };
  });
  await hooks.activated();
  assert.equal(state.configObj.value, config);
  assert.equal(state.showPreConfig.value, false);
  failure = Object.assign(new Error('Permission denied 404'), { code: 'FORBIDDEN', status: 403 });
  await state.loadConfig();
  assert.equal(state.error.value, failure.message);
  assert.equal(state.showPreConfig.value, false);
});

test('leaving project interfaces suppresses a late missing-file response', async () => {
  let reject;
  const { state, hooks } = projectPage(() => new Promise((resolve, fail) => { reject = fail; }));
  const loading = hooks.activated();
  hooks.deactivated();
  reject(Object.assign(new Error('Config file not found'), { code: 'NOT_FOUND', status: 404 }));
  await loading;
  assert.equal(state.showPreConfig.value, false);
});
