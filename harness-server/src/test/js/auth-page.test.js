const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

function createLogin(savedToken, sessionError) {
  const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
  const start = source.indexOf('const app = createApp({');
  const root = source.slice(start, source.indexOf('// 全局涟漪', start));
  const values = new Map(savedToken ? [['cyrene_console_token', savedToken]] : []);
  const storage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  let mounted, token, loginError, changes = 0;
  const components = Object.fromEntries([
    'ToastContainer', 'StarsBackground', 'ChatPage', 'KnowledgePage', 'GraphPage',
    'AuditPage', 'GuidePage', 'ConfigPage', 'ModelConfigPage', 'ToolPermissionPage', 'RealtimeTestDock',
  ].map(name => [name, {}]));
  const credential = { token: 'verified-token', userId: 'root', mustChangePassword: true, expiresAt: '2026-11-11T00:00:00Z' };
  const state = runInNewContext(root + '\napp.setup();', {
    ...components, createApp: value => value, ref: value => ({ value }),
    computed: fn => ({ get value() { return fn(); } }), inject: () => ({}), provide() {}, watch() {},
    onMounted: fn => { mounted = fn; }, onUnmounted() {}, showToast() {},
    localStorage: storage, sessionStorage: { removeItem() {} },
    window: { location: { hash: '' }, addEventListener() {}, removeEventListener() {} },
    CyreneI18n: { init: () => ({ value: 'zh' }), t: key => key },
    CyreneAPI: {
      setToken: value => { token = value; }, getToken: () => token, onTokenRefresh() {},
      async health() { return { authMode: 'none', consoleUserId: 'root' }; },
      async consoleLogin(user, password) {
        assert.equal(user, 'root'); assert.equal(password, 'root');
        if (loginError) throw loginError;
        return credential;
      },
      async getConsoleSession() {
        if (sessionError) throw sessionError;
        return { userId: 'root', mustChangePassword: false };
      },
      async changeConsolePassword(current, replacement) {
        assert.equal(current, 'root'); assert.equal(replacement, 'new-password'); changes++;
        return { ...credential, token: 'renewed-token', mustChangePassword: false };
      },
      async logout() {},
    },
  });
  return { state, mounted, storage, getToken: () => token, failLogin: error => { loginError = error; }, changes: () => changes, root };
}

test('root defaults, verified login, initial password prompt, password change and logout', async () => {
  const fixture = createLogin();
  const { state, storage } = fixture;
  await fixture.mounted();
  assert.equal(state.editUserId.value, 'root');
  assert.equal(state.authSecret.value, 'root');
  fixture.failLogin(new Error('Invalid credentials'));
  await state.confirmUserId();
  assert.equal(state.showWelcome.value, true);
  assert.equal(state.authError.value, 'Invalid credentials');
  fixture.failLogin(undefined);
  await state.confirmUserId();
  assert.equal(state.userId.value, 'root');
  assert.equal(storage.getItem('cyrene_console_token'), 'verified-token');
  assert.equal(state.authSecret.value, '');
  assert.equal(state.showWelcome.value, false);
  assert.equal(state.showPasswordDialog.value, true);
  assert.equal(state.showGuideHint.value, true);
  state.currentPassword.value = 'root';
  state.newPassword.value = 'new-password';
  state.confirmPassword.value = 'mismatch';
  await state.changePassword();
  assert.equal(fixture.changes(), 0);
  assert.equal(state.passwordError.value, 'passwordMismatch');
  state.confirmPassword.value = 'new-password';
  const version = state.authVersion.value;
  await state.changePassword();
  assert.equal(state.authVersion.value, version);
  assert.equal(storage.getItem('cyrene_console_token'), 'renewed-token');
  assert.equal(state.mustChangePassword.value, false);
  assert.equal(state.showPasswordDialog.value, false);
  assert.equal(state.currentPassword.value, '');
  assert.equal(storage.getItem('password'), undefined);
  state.openGuide();
  assert.equal(state.currentPage.value, 'guide');
  await state.signOut();
  assert.equal(fixture.getToken(), null);
  assert.equal(storage.getItem('cyrene_console_token'), undefined);
  assert.equal(state.userId.value, '');
  assert.equal(state.showWelcome.value, true);
});

test('saved credential is validated before auto entering and expired credentials are cleared', async () => {
  const valid = createLogin('saved-token');
  assert.equal(valid.state.showWelcome.value, true);
  await valid.mounted();
  assert.equal(valid.state.showWelcome.value, false);
  assert.equal(valid.state.userId.value, 'root');
  const expired = createLogin('expired-token', Object.assign(new Error('expired'), { status: 401 }));
  await expired.mounted();
  assert.equal(expired.state.showWelcome.value, true);
  assert.equal(expired.storage.getItem('cyrene_console_token'), undefined);
  assert.equal(expired.getToken(), null);
  assert.equal(expired.state.authError.value, 'expired');
  assert.match(valid.root, /<keep-alive v-if="!showWelcome"/);
});

test('service errors preserve the credential while rendering the failure', async () => {
  const fixture = createLogin('saved-token', Object.assign(new Error('database offline'), { status: 503 }));
  await fixture.mounted();
  assert.equal(fixture.state.showWelcome.value, true);
  assert.equal(fixture.storage.getItem('cyrene_console_token'), 'saved-token');
  assert.equal(fixture.state.authError.value, 'database offline');
});
