const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { join } = require('node:path');

const resources = join(__dirname, '../../main/resources/public/js');
const i18nSource = readFileSync(join(resources, 'i18n.js'), 'utf8');
function translator(locale) {
  const i18n = runInNewContext(i18nSource + '\nCyreneI18n;');
  i18n.locale = { value: locale };
  return i18n;
}

test('Chinese built-in tool descriptions switch back to original English and preserve external descriptions', () => {
  const i18n = translator('zh');
  const sourceText = 'Read one typed Wiki handle after authorization.';
  assert.match(i18n.t('toolDescription.knowledge_read', sourceText), /校验版本/);
  assert.equal(i18n.t('toolDescription.mcp_custom_lookup', 'Vendor supplied description'), 'Vendor supplied description');
  i18n.locale.value = 'en';
  assert.equal(i18n.t('toolDescription.knowledge_read', sourceText), sourceText);
  assert.equal(i18n.t('toolDescription.mcp_custom_lookup', '外部工具说明'), '外部工具说明');
  assert.equal(i18n.t('unknown.translation'), 'unknown.translation');
});

test('API descriptions explain personal drafts separately from tenant-wide review and resource policies', () => {
  const i18n = translator('zh');
  assert.match(i18n.t('apiDescription.graph.draft.apply'), /当前用户自己/);
  assert.match(i18n.t('apiDescription.graph.review.list'), /同租户所有用户/);
  assert.match(i18n.t('apiDescription.graph.review.apply'), /图谱空间访问权/);
  assert.match(i18n.t('apiPolicy.TENANT'), /不代表自动有管理权限/);
  assert.match(i18n.t('apiPolicy.GLOBAL_MANAGEMENT'), /启用 API 权限控制时/);
  assert.match(i18n.t('internalApiPermissionHint'), /根据对接项目/);
});

test('every statically registered API has Chinese and English usage descriptions', () => {
  const server = join(__dirname, '../../main/java/com/harness/server');
  const sources = ['Main.java', 'KnowledgeApiRoutes.java'].map(file => readFileSync(join(server, file), 'utf8')).join('\n');
  const keys = [...sources.matchAll(/(?:apiRoutes|routes)\.route\(app,\s*"[A-Z]+",\s*"[^"]+",\s*"([^"]+)"/g)]
    .map(match => match[1]);
  keys.push('realtime.connect');
  const publicKeys = new Set(['health.read', 'auth.logout', 'auth.login']);
  assert.ok(keys.length > 80);
  for (const locale of ['zh', 'en']) {
    const i18n = translator(locale);
    for (const key of keys.filter(key => !publicKeys.has(key))) {
      assert.notEqual(i18n.t('apiDescription.' + key, 'missing'), 'missing', locale + ': ' + key);
    }
  }
});
