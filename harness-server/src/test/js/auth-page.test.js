const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

test('login uses the verified user and logout clears both the token and previous page scope', async () => {
  const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
  const start = source.indexOf('const app = createApp({');
  const root = source.slice(start, source.indexOf('// 全局涟漪', start));
  const storage = () => {
    const values = new Map();
    return { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  };
  let mounted;
  let token;
  let failure;
  const localStorage = storage();
  const sessionStorage = storage();
  const components = Object.fromEntries([
    'ToastContainer', 'StarsBackground', 'PreConfigModal', 'ChatPage', 'KnowledgePage', 'GraphPage',
    'AuditPage', 'GuidePage', 'ConfigPage', 'ModelConfigPage', 'ModelConfigurationPage', 'ToolPermissionPage', 'RealtimeTestDock',
  ].map(name => [name, {}]));
  const state = runInNewContext(root + '\napp.setup();', {
    ...components, createApp: value => value, ref: value => ({ value }),
    computed: fn => ({ get value() { return fn(); } }), inject: () => ({}), provide() {}, watch() {},
    onMounted: fn => { mounted = fn; }, onUnmounted() {}, showToast() {}, localStorage, sessionStorage,
    window: { location: { hash: '' }, addEventListener() {}, removeEventListener() {} },
    CyreneI18n: { init: () => ({ value: 'zh' }), t: key => key },
    CyreneAPI: {
      setToken: value => { token = value; }, getToken: () => token, onTokenRefresh() {},
      async health() { return { authMode: 'jwt' }; },
      async login(user, password) {
        assert.equal(user, 'login-alias'); assert.equal(password, 'password');
        if (failure) throw failure;
        return { token: 'verified-token', userId: 'canonical-user' };
      },
      async logout() {},
      async setupSingleUser(user, password) {
        assert.equal(user, 'local-user'); assert.equal(password, 'future-jwt-password');
        return { userId: user, status: 'saved' };
      },
    },
  });
  await mounted();
  state.editUserId.value = 'login-alias'; state.authSecret.value = 'password';
  failure = new Error('Invalid credentials');
  await state.confirmUserId();
  assert.equal(state.showWelcome.value, true);
  assert.equal(state.authError.value, 'Invalid credentials');
  failure = undefined;
  await state.confirmUserId();
  assert.equal(state.userId.value, 'canonical-user');
  assert.equal(sessionStorage.getItem('cyrene_token'), 'verified-token');
  assert.equal(state.authSecret.value, '');
  assert.equal(state.showWelcome.value, false);
  assert.equal(state.currentPage.value, 'chat');
  assert.equal(state.showGuideHint.value, true);
  assert.equal(state.navItems.value.some(item => item.id === 'guide'), false);
  state.openGuide();
  assert.equal(state.currentPage.value, 'guide');
  assert.equal(state.showGuideHint.value, false);
  assert.equal(localStorage.getItem('cyrene_guide_seen:jwt:canonical-user'), '1');
  await state.startEditUser();
  assert.equal(token, null);
  assert.equal(sessionStorage.getItem('cyrene_token'), undefined);
  assert.equal(state.userId.value, '');
  assert.equal(state.showWelcome.value, true);
  state.authMode.value = 'none';
  state.editUserId.value = 'local-user'; state.authSecret.value = 'future-jwt-password';
  await state.confirmUserId();
  assert.equal(state.userId.value, 'local-user');
  assert.equal(state.authSecret.value, '');
  assert.equal(localStorage.getItem('cyrene_guide_seen:none:local-user'), '1');
  assert.equal(localStorage.getItem('cyrene_token'), undefined);
  assert.equal(localStorage.getItem('password'), undefined);
  state.navigate('chat');
  state.authSecret.value = 'future-jwt-password';
  await state.confirmUserId();
  assert.equal(state.currentPage.value, 'chat');
  assert.equal(state.showGuideHint.value, false);
  state.authError.value = 'Previous failure';
  await state.startEditUser();
  assert.equal(state.editingUser.value, true);
  assert.equal(state.authSecret.value, '');
  assert.equal(state.authError.value, '');
  state.authSecret.value = 'draft password';
  state.authError.value = 'Invalid credentials';
  state.cancelEditUser();
  assert.equal(state.editingUser.value, false);
  assert.equal(state.authSecret.value, '');
  assert.equal(state.authError.value, '');
});
