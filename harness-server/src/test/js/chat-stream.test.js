const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');
const CyreneSSE = require('../../main/resources/public/js/sse-parser.js');
const { upsert: upsertToolCall } = require('../../main/resources/public/js/tool-call-state.js');

const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
const sendMessage = source.slice(source.indexOf('    async function sendMessage()'),
  source.indexOf('    function scrollToBottom()', source.indexOf('    async function sendMessage()')));
const appendText = source.slice(source.indexOf('function appendAssistantText('),
  source.indexOf('function appendStructuredData('));

async function runChat(events, closes = false) {
  const timers = new Set();
  const state = {
    inputText: { value: 'question' }, attachedFiles: { value: [] },
    isStreaming: { value: false }, userId: { value: 'alice' },
    messages: { value: [] }, currentSessionId: { value: '' }, thinkingLevelIndex: { value: 0 },
    pendingConfirmation: { value: { requestId: 'pending' } }, confirmationAcknowledged: { value: true },
    toasts: [], reads: 0, cancelled: false, scheduledFrames: 0,
  };
  const reader = {
    async read() {
      state.reads++;
      if (state.reads === 1) return { done: false, value: new TextEncoder().encode(events) };
      if (closes) return { done: true };
      throw new Error('read continued after terminal event');
    },
    async cancel() { state.cancelled = true; },
  };
  const send = runInNewContext(appendText + sendMessage + '\nsendMessage;', {
    ...state, Map, TextDecoder, CyreneSSE, upsertToolCall,
    CyreneAPI: { async chat() { return { body: { getReader: () => reader } }; } },
    t: key => key, showToast: text => state.toasts.push(text), scrollToBottom() {}, loadSessions() {},
    SSE_LIVENESS_TIMEOUT_MS: 90_000, STREAM_CHARS_PER_FRAME: 8,
    setTimeout(callback, delay) {
      const timer = { callback };
      if (delay === 0) queueMicrotask(callback);
      else timers.add(timer);
      return timer;
    },
    clearTimeout: timer => timers.delete(timer),
    requestAnimationFrame(callback) {
      state.scheduledFrames++;
      const frame = { callback };
      timers.add(frame);
      queueMicrotask(() => {
        if (timers.delete(frame)) callback();
      });
      return frame;
    },
    cancelAnimationFrame: timer => clearTimeout(timer),
  });
  await send();
  assert.equal(state.isStreaming.value, false);
  assert.equal(state.pendingConfirmation.value, null);
  assert.equal(state.cancelled, true);
  assert.equal(timers.size, 0);
  return state;
}

for (const type of ['done', 'error', 'cancelled']) {
  test(`${type} ends the request without waiting for the connection to close`, async () => {
    const state = await runChat(`event: ${type}\ndata: {"output":"final answer","error":"model failed"}\n\n`);
    assert.equal(state.reads, 1);
    assert.equal(state.messages.value[1].content,
      type === 'done' ? 'final answer' : type === 'error' ? '⚠️ Error: model failed' : '');
  });
}

test('streamed final text is not duplicated by done or overwritten by later events', async () => {
  const state = await runChat('event: token\ndata: {"text":"answer rendered "}\n\n'
    + 'event: token\ndata: {"text":"over frames"}\n\n'
    + 'event: done\ndata: {"output":"answer rendered over frames"}\n\n'
    + 'event: error\ndata: {"error":"late error"}\n\n');
  assert.equal(state.messages.value[1].content, 'answer rendered over frames');
  assert.ok(state.scheduledFrames > 1);
  assert.deepEqual(state.toasts, []);
});

test('EOF without a terminal event reports interruption and finishes running tool cards', async () => {
  const state = await runChat('event: tool_call_start\n'
    + 'data: {"toolCallId":"call-1","toolName":"knowledge_read","status":"RUNNING"}\n\n', true);
  assert.match(state.messages.value[1].content, /streamInterrupted/);
  assert.equal(state.messages.value[1].toolCalls[0].status, 'FAILED');
});

test('session disappears immediately and is restored when deletion fails', async () => {
  const deleteSessionSource = source.slice(source.indexOf('    async function deleteSession(sid)'),
    source.indexOf('    async function cancelOutput()', source.indexOf('    async function deleteSession(sid)')));
  let rejectDelete;
  const deletion = new Promise((resolve, reject) => { rejectDelete = reject; });
  const state = {
    sessions: { value: [{ id: 'session-1' }, { id: 'session-2' }] },
    currentSessionId: { value: 'session-1' },
    messages: { value: [{ id: 1, content: 'message' }] },
    userId: { value: 'alice' },
    toasts: [],
  };
  const deleteSession = runInNewContext(deleteSessionSource + '\ndeleteSession;', {
    ...state,
    confirm: () => true,
    t: key => key,
    showToast: text => state.toasts.push(text),
    CyreneAPI: { closeSession: () => deletion },
  });

  const pending = deleteSession('session-1');
  assert.deepEqual(state.sessions.value, [{ id: 'session-2' }]);
  assert.equal(state.currentSessionId.value, null);
  assert.equal(state.messages.value.length, 0);

  rejectDelete(new Error('failed'));
  await pending;
  assert.deepEqual(state.sessions.value, [{ id: 'session-1' }, { id: 'session-2' }]);
  assert.equal(state.currentSessionId.value, 'session-1');
  assert.deepEqual(state.messages.value, [{ id: 1, content: 'message' }]);
});
