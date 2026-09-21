const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

test('group denials affect children and action permissions survive a save and reload', async () => {
  const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
  const start = source.indexOf('const ToolPermissionPage = {');
  const page = source.slice(start, source.indexOf('\n};', start) + 3);
  let disabledTools = ['web'];
  const tools = [
    { name: 'web', groupName: null },
    { name: 'web.search', groupName: 'web' },
    { name: 'web.read', groupName: 'web' },
    { name: 'web.external', groupName: null },
  ];
  const state = runInNewContext(page + '\nToolPermissionPage.setup();', {
    EmptyState: {}, ref: value => ({ value }),
    computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : {}, onMounted() {}, showToast() {},
    CyreneAPI: {
      async getToolPermissions() {
        return { identity: 'DEFAULT', profiles: [], tenants: [], tools, disabledTools, restricted: disabledTools.length > 0 };
      },
      async saveToolPermissions(request) { disabledTools = [...request.disabledTools]; },
    },
  });
  await state.load();
  assert.equal(state.isDisabled(tools[1]), true);
  assert.equal(state.inheritedDenial(tools[1]), true);
  assert.equal(state.isDisabled(tools[3]), false);
  assert.equal(state.disabledCount.value, 3);
  state.toggleTool('web');
  state.toggleTool('web.search');
  await state.save();
  assert.deepEqual(disabledTools, ['web.search']);
  assert.equal(state.isDisabled(tools[1]), true);
  assert.equal(state.isDisabled(tools[2]), false);
  assert.equal(state.disabledCount.value, 1);
});
