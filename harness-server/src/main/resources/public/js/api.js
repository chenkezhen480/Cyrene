/**
 * Cyrene API Client
 * 封装所有后端 API 调用
 */
const CyreneAPI = (() => {
  let _token = null;
  let _onTokenRefresh = null;

  function setToken(token) {
    _token = token;
  }

  function getToken() {
    return _token;
  }

  function onTokenRefresh(callback) {
    _onTokenRefresh = callback;
  }

  async function request(method, path, body = null, options = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (_token) headers['Authorization'] = `Bearer ${_token}`;

    const config = { method, headers };
    if (body && method !== 'GET') {
      config.body = typeof body === 'string' ? body : JSON.stringify(body);
    }

    const resp = await fetch(path, config);

    // JWT refresh
    const newToken = resp.headers.get('X-New-Token');
    if (newToken && _onTokenRefresh) {
      _token = newToken;
      _onTokenRefresh(newToken);
    }

    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ error: resp.statusText }));
      throw new Error(err.error || `HTTP ${resp.status}`);
    }

    return resp.json();
  }

  async function requestText(method, path, body = null) {
    const headers = { 'Content-Type': 'application/json' };
    if (_token) headers['Authorization'] = `Bearer ${_token}`;

    const config = { method, headers };
    if (body) config.body = typeof body === 'string' ? body : JSON.stringify(body);

    const resp = await fetch(path, config);
    return resp.text();
  }

  // ── Auth ──
  function login(userId, password) {
    return request('POST', '/api/auth/token', { userId, password });
  }

  // ── Chat (SSE) ──
  function chat(sessionId, text, context = {}, attachments = []) {
    const headers = { 'Content-Type': 'application/json' };
    if (_token) headers['Authorization'] = `Bearer ${_token}`;
    if (sessionId) headers['X-Session-Id'] = sessionId;

    const body = { text, context, attachments };
    return fetch('/api/chat', { method: 'POST', headers, body: JSON.stringify(body) });
  }

  function cancelChat(sessionId) {
    return request('DELETE', `/api/chat/${sessionId}`);
  }

  // ── Sessions ──
  function createSession(userId, title) {
    return request('POST', '/api/sessions', { userId, title });
  }

  function listSessions(userId, { status, limit, cursor } = {}) {
    const params = new URLSearchParams();
    if (userId) params.set('userId', userId);
    if (status) params.set('status', status);
    if (limit) params.set('limit', limit);
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/sessions?${params}`);
  }

  function getSession(sessionId) {
    return request('GET', `/api/sessions/${sessionId}`);
  }

  function getMessages(sessionId, { limit, cursor, direction } = {}) {
    const params = new URLSearchParams();
    if (limit) params.set('limit', limit);
    if (cursor) params.set('cursor', cursor);
    if (direction) params.set('direction', direction);
    return request('GET', `/api/sessions/${sessionId}/messages?${params}`);
  }

  function getSessionStats(sessionId) {
    return request('GET', `/api/sessions/${sessionId}/stats`);
  }

  function closeSession(sessionId) {
    return request('DELETE', `/api/sessions/${sessionId}`);
  }

  // ── Knowledge ──
  function uploadKnowledge(file, collection) {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('collection', collection);
    const headers = {};
    if (_token) headers['Authorization'] = `Bearer ${_token}`;
    return fetch('/api/knowledge/upload', { method: 'POST', headers, body: formData })
      .then(r => r.json());
  }

  function listKnowledge(collection) {
    return request('GET', `/api/knowledge/${collection}`);
  }

  function deleteCollection(collection) {
    return request('DELETE', `/api/knowledge/${collection}`);
  }

  function deleteDocument(collection, documentId) {
    return request('DELETE', `/api/knowledge/${collection}/${documentId}`);
  }

  // ── Traces ──
  function getTrace(traceId) {
    return request('GET', `/api/trace/${traceId}`);
  }

  function listTraces(limit = 20) {
    return request('GET', `/api/traces?limit=${limit}`);
  }

  function getTraceStats() {
    return request('GET', '/api/traces/stats');
  }

  function cleanupTraces() {
    return request('DELETE', '/api/traces/cleanup');
  }

  function deleteTrace(traceId) {
    return request('DELETE', `/api/traces/${traceId}`);
  }

  // ── Project Discovery ──
  function scanProject(sourceRoot, baseUrl) {
    return request('POST', '/api/project-discovery/scan', { sourceRoot, baseUrl });
  }

  function generateConfig(config) {
    return request('POST', '/api/project-discovery/generate', config);
  }

  function getConfig() {
    return request('GET', '/api/project-discovery/config');
  }

  function updateConfig(config) {
    return request('PUT', '/api/project-discovery/config', config);
  }

  function reloadConfig() {
    return request('POST', '/api/project-discovery/reload');
  }

  // ── Health ──
  function health() {
    return request('GET', '/api/health');
  }

  return {
    setToken, getToken, onTokenRefresh,
    login,
    chat, cancelChat,
    createSession, listSessions, getSession, getMessages, getSessionStats, closeSession,
    uploadKnowledge, listKnowledge, deleteCollection, deleteDocument,
    getTrace, listTraces, getTraceStats, cleanupTraces, deleteTrace,
    scanProject, generateConfig, getConfig, updateConfig, reloadConfig,
    health,
  };
})();
