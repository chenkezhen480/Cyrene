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

  async function requireOkResponse(resp) {
    if (resp.ok) return resp;

    let errorBody;
    try {
      errorBody = await resp.json();
    } catch {
      throw new Error(`HTTP ${resp.status}: invalid API error response`);
    }
    if (!errorBody
        || typeof errorBody.code !== 'string'
        || typeof errorBody.message !== 'string'
        || !errorBody.details
        || typeof errorBody.details !== 'object'
        || Array.isArray(errorBody.details)) {
      throw new Error(`HTTP ${resp.status}: invalid API error response`);
    }

    const error = new Error(errorBody.message);
    error.code = errorBody.code;
    error.details = errorBody.details;
    throw error;
  }

  async function request(method, path, body = null) {
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

    return (await requireOkResponse(resp)).json();
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
    return fetch('/api/chat', { method: 'POST', headers, body: JSON.stringify(body) })
      .then(requireOkResponse);
  }

  // ── File Upload (for image-to-image and other file references) ──
  async function uploadFile(file) {
    const formData = new FormData();
    formData.append('file', file);
    const headers = {};
    if (_token) headers['Authorization'] = `Bearer ${_token}`;

    const resp = await fetch('/api/files/upload', { method: 'POST', headers, body: formData });
    return (await requireOkResponse(resp)).json(); // { url, name, size }
  }

  function getModelConfiguration() {
    return request('GET', '/api/model-config');
  }

  function updateModelConfiguration(values, clearKeys) {
    return request('PUT', '/api/model-config', { values, clearKeys });
  }

  function cancelChat(sessionId) {
    return request('DELETE', `/api/chat/${sessionId}`);
  }

  function approveConfirmation(requestId, userId, sessionId) {
    return request(
      'POST',
      `/api/confirmations/${encodeURIComponent(requestId)}/approve`,
      { userId, sessionId }
    );
  }

  function rejectConfirmation(requestId, userId, sessionId) {
    return request(
      'POST',
      `/api/confirmations/${encodeURIComponent(requestId)}/reject`,
      { userId, sessionId }
    );
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
  function listCollections({ limit = 50, cursor = '' } = {}) {
    const params = new URLSearchParams();
    params.set('limit', String(limit));
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/knowledge?${params}`);
  }

  function uploadKnowledge(file, collection) {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('collection', collection);
    const headers = {};
    if (_token) headers['Authorization'] = `Bearer ${_token}`;
    return fetch('/api/knowledge/upload', { method: 'POST', headers, body: formData })
      .then(requireOkResponse)
      .then(r => r.json());
  }

  function listKnowledge(collection, { fileName = '', limit = 50, cursor = '' } = {}) {
    const params = new URLSearchParams();
    if (fileName) params.set('fileName', fileName);
    params.set('limit', String(limit));
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/knowledge/${encodeURIComponent(collection)}?${params}`);
  }

  function deleteCollection(collection) {
    return request('DELETE', `/api/knowledge/${collection}`);
  }

  function getDocument(collection, documentId) {
    return request('GET', `/api/knowledge/${collection}/${documentId}`);
  }

  function updateDocument(collection, documentId, content) {
    return request('PUT', `/api/knowledge/${collection}/${documentId}`, { content });
  }

  function deleteDocument(collection, documentId) {
    return request('DELETE', `/api/knowledge/${collection}/${documentId}`);
  }

  // ── Knowledge Graph ──
  function getGraphStatus() {
    return request('GET', '/api/graph/status');
  }

  function listGraphSchemas({ limit = 20, cursor } = {}) {
    const params = new URLSearchParams();
    params.set('limit', limit);
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/graph/schemas?${params}`);
  }

  function getGraphSchema(schemaId) {
    return request('GET', `/api/graph/schemas/${encodeURIComponent(schemaId)}`);
  }

  function listGraphSchemaConfigs({ limit = 20, cursor } = {}) {
    const params = new URLSearchParams();
    params.set('limit', limit);
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/graph/schema-configs?${params}`);
  }

  function getGraphSchemaConfig(schemaId) {
    return request('GET', `/api/graph/schema-configs/${encodeURIComponent(schemaId)}`);
  }

  function createGraphSchemaConfig(payload) {
    return request('POST', '/api/graph/schema-configs', payload);
  }

  function updateGraphSchemaConfig(schemaId, payload) {
    return request('PUT', `/api/graph/schema-configs/${encodeURIComponent(schemaId)}`, payload);
  }

  function enableGraphSchemaConfig(schemaId) {
    return request('POST', `/api/graph/schema-configs/${encodeURIComponent(schemaId)}/enable`);
  }

  function disableGraphSchemaConfig(schemaId) {
    return request('POST', `/api/graph/schema-configs/${encodeURIComponent(schemaId)}/disable`);
  }

  function deleteGraphSchemaConfig(schemaId) {
    return request('DELETE', `/api/graph/schema-configs/${encodeURIComponent(schemaId)}`);
  }

  function listGraphSpaces({ limit = 20, cursor } = {}) {
    const params = new URLSearchParams();
    params.set('limit', limit);
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/graph/graphs?${params}`);
  }

  function deleteGraphSpace({ graphId, schemaId }) {
    const params = new URLSearchParams({ graphId, schemaId });
    return request('DELETE', `/api/graph/graphs?${params}`);
  }

  function listGraphNodes({ graphId, schemaId, label, name, limit = 50, cursor } = {}) {
    const params = new URLSearchParams();
    params.set('graphId', graphId);
    params.set('schemaId', schemaId);
    params.set('limit', limit);
    if (label) params.set('label', label);
    if (name) params.set('name', name);
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/graph/nodes?${params}`);
  }

  function listGraphRelations({
    graphId,
    schemaId,
    relationType,
    limit = 50,
    cursor,
  } = {}) {
    const params = new URLSearchParams();
    params.set('graphId', graphId);
    params.set('schemaId', schemaId);
    params.set('limit', limit);
    if (relationType) params.set('relationType', relationType);
    if (cursor) params.set('cursor', cursor);
    return request('GET', `/api/graph/relations?${params}`);
  }

  function deleteGraphNode({ graphId, schemaId, nodeId, detach = false }) {
    const params = new URLSearchParams({ graphId, schemaId });
    if (detach) params.set('mode', 'DETACH');
    return request(
      'DELETE',
      `/api/graph/nodes/${encodeURIComponent(nodeId)}?${params}`
    );
  }

  function deleteGraphRelation({ graphId, schemaId, relationId }) {
    const params = new URLSearchParams({ graphId, schemaId });
    return request(
      'DELETE',
      `/api/graph/relations/${encodeURIComponent(relationId)}?${params}`
    );
  }

  function queryGraph(payload) {
    return request('POST', '/api/graph/query', payload);
  }

  function buildGraph(payload) {
    return request('POST', '/api/graph/build', payload);
  }

  function previewNaturalLanguageGraph(payload) {
    return request('POST', '/api/graph/build/preview', payload);
  }

  // ── Artifacts ──
  function getArtifactUrl(id) {
    return `/api/artifacts/${id}`;
  }

  function getArtifactPreviewUrl(id) {
    return `/api/artifacts/${id}/preview`;
  }

  function listSessionArtifacts(sessionId) {
    return request('GET', `/api/artifacts/session/${sessionId}`);
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
    chat, cancelChat, approveConfirmation, rejectConfirmation, uploadFile,
    getModelConfiguration, updateModelConfiguration,
    createSession, listSessions, getSession, getMessages, getSessionStats, closeSession,
    listCollections, uploadKnowledge, listKnowledge, getDocument, updateDocument, deleteCollection, deleteDocument,
    getGraphStatus, listGraphSchemas, getGraphSchema,
    listGraphSchemaConfigs, getGraphSchemaConfig, createGraphSchemaConfig, updateGraphSchemaConfig,
    enableGraphSchemaConfig, disableGraphSchemaConfig, deleteGraphSchemaConfig,
    listGraphSpaces, deleteGraphSpace, listGraphNodes, listGraphRelations,
    deleteGraphNode, deleteGraphRelation, queryGraph, buildGraph, previewNaturalLanguageGraph,
    getArtifactUrl, getArtifactPreviewUrl, listSessionArtifacts,
    getTrace, listTraces, getTraceStats, cleanupTraces, deleteTrace,
    scanProject, generateConfig, getConfig, updateConfig, reloadConfig,
    health,
  };
})();
