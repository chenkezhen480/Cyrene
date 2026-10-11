const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

function setupPage(apiFormat = '', capabilities = '') {
  const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
  const start = source.indexOf('const ModelConfigPage = {');
  const page = source.slice(start, source.indexOf('\n};', start) + 3);
  const field = (key, value) => ({ key, value, label: key, configured: Boolean(value), sensitive: false, runtimeSynchronized: true });
  const capabilityField = { ...field('chat.capabilities', capabilities), control: 'multiselect', options: ['TEXT', 'IMAGE_INPUT', 'AUDIO_INPUT', 'VIDEO_INPUT'] };
  let configuration = {
    path: 'model.conf', runtimeSynchronized: true,
    sections: [{ id: 'chat', fields: [field('chat.apiFormat', apiFormat), capabilityField] }],
  };
  let saved;
  const state = runInNewContext(page + '\nModelConfigPage.setup();', {
    ref: value => ({ value }), reactive: value => value,
    computed: getter => ({ get value() { return getter(); } }),
    inject: key => key === 't' ? value => value : {}, onMounted() {}, showToast() {},
    requireKnowledgeStatus: value => value,
    CyreneAPI: {
      async getModelConfiguration() { return configuration; },
      async getKnowledgeStatus() { return { state: 'pending' }; },
      async updateModelConfiguration(values, clearKeys) {
        saved = { values, clearKeys };
        configuration.sections[0].fields.forEach(item => {
          if (Object.hasOwn(values, item.key)) item.value = values[item.key];
          if (clearKeys.includes(item.key)) item.value = '';
        });
        return configuration;
      },
    },
  });
  return { state, capabilityField, get saved() { return saved; } };
}

test('empty configuration proposes chat_completions and text/image capabilities for saving', async () => {
  const { state } = setupPage();
  await state.loadConfiguration();
  assert.equal(state.draftValues['chat.apiFormat'], 'chat_completions');
  assert.equal(state.draftValues['chat.capabilities'], 'TEXT,IMAGE_INPUT');
  assert.equal(state.hasChanges.value, true);
  const configured = setupPage('responses');
  await configured.state.loadConfiguration();
  assert.equal(configured.state.draftValues['chat.apiFormat'], 'responses');
});

test('existing explicit capability choices are preserved', async () => {
  const { state } = setupPage('responses', 'TEXT');
  await state.loadConfiguration();
  assert.equal(state.draftValues['chat.capabilities'], 'TEXT');
  assert.equal(state.hasChanges.value, false);
});

test('capability checkboxes round trip comma-separated values and clear the override when unchecked', async () => {
  const page = setupPage('', 'text, image_input');
  const { state, capabilityField } = page;
  await state.loadConfiguration();
  assert.deepEqual([...state.selectedOptions(capabilityField)], ['TEXT', 'IMAGE_INPUT']);
  state.toggleOption(capabilityField, 'AUDIO_INPUT', true);
  state.toggleOption(capabilityField, 'VIDEO_INPUT', true);
  await state.saveConfiguration();
  assert.equal(page.saved.values['chat.capabilities'], 'TEXT,IMAGE_INPUT,AUDIO_INPUT,VIDEO_INPUT');
  assert.equal(state.hasChanges.value, false);
  capabilityField.options.forEach(option => state.toggleOption(capabilityField, option, false));
  await state.saveConfiguration();
  assert.deepEqual([...page.saved.clearKeys], ['chat.capabilities']);
  assert.equal(state.draftValues['chat.capabilities'], '');
  assert.equal(state.hasChanges.value, false);
});
