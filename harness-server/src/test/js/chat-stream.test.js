const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');
const CyreneSSE = require('../../main/resources/public/js/sse-parser.js');
const { upsert: upsertToolCall } = require('../../main/resources/public/js/tool-call-state.js');

const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');
// Matched without the parameter list: the slice must survive the signature being extended.
const SEND_START = '    async function sendMessage(';
const sendMessage = source.slice(source.indexOf(SEND_START),
  source.indexOf('    function scrollToBottom()', source.indexOf(SEND_START)));
const appendText = source.slice(source.indexOf('function appendAssistantText('),
  source.indexOf('function appendStructuredData('));
// The playback queue lives above sendMessage, so it has to be sliced in alongside it —
// sendMessage references it by name and the VM has no other way to see it.
// Matched without the value: tuning the buffer must not silently empty this slice.
const voicePlayback = source.slice(
  source.indexOf('    const VOICE_START_BUFFER_SECONDS ='),
  source.indexOf('    function preferredRecordingMimeType()'));

async function runChat(events, closes = false, overrides = {}, stopOnRead = 0, sendOptions = {}) {
  const timers = new Set();
  const state = {
    inputText: { value: 'question' }, attachedFiles: { value: [] },
    isStreaming: { value: false }, userId: { value: 'alice' },
    messages: { value: [] }, currentSessionId: { value: '' }, thinkingLevelIndex: { value: 0 },
    pendingConfirmation: { value: { requestId: 'pending' } }, confirmationAcknowledged: { value: true },
    currentRunId: { value: null },
    toasts: [], reads: 0, cancelled: false, scheduledFrames: 0,
    chatCalls: [], artifacts: [], playedAudio: [], scheduledAt: [],
    ...overrides,
  };
  const reader = {
    async read() {
      state.reads++;
      if (state.reads === 1) {
        return { done: false, value: new TextEncoder().encode(events) };
      }
      if (stopOnRead === state.reads && stopFn) {
        stopFn();
        return { done: true };
      }
      if (closes) return { done: true };
      throw new Error('read continued after terminal event');
    },
    async cancel() { state.cancelled = true; },
  };
  let stopFn = null;
  const api = runInNewContext(appendText + voicePlayback + sendMessage
    + '\n({ sendMessage, currentRun: () => activeRun });', {
    ...state, Map, TextDecoder, CyreneSSE, upsertToolCall,
    // ChatPage setup state the send loop reads and writes across invocations.
    activeRun: null,
    CyreneAPI: {
      async chat(...args) {
        state.chatCalls.push(args);
        return { body: { getReader: () => reader } };
      },
    },
    // The playback queue traces its own path now, and a browser VM has no console.
    console: { log() {}, warn() {}, error() {} },
    performance: { now: () => 0 },
    // Node has neither, and the playback queue now references both.
    fetch: async url => {
      state.playedAudio.push(url);
      return { arrayBuffer: async () => new ArrayBuffer(8) };
    },
    AudioContext: class {
      constructor() {
        this.currentTime = 0;
        this.destination = {};
        this.state = 'running';
      }
      resume() { return Promise.resolve(); }
      decodeAudioData() { return Promise.resolve({ duration: 1, sampleRate: 48000 }); }
      createBufferSource() {
        return {
          buffer: null,
          connect() {},
          stop() {},
          start(at) { state.scheduledAt.push(at); },
        };
      }
    },
    appendArtifact(msg, artifact) { state.artifacts.push(artifact); },
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
  stopFn = () => api.currentRun()?.stop();
  await api.sendMessage(sendOptions);
  // The playback queue fetches and decodes on its own promise chain, off the send loop.
  // Drain it before anything asserts on what was played.
  for (let i = 0; i < 64; i++) await Promise.resolve();
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

test('frames carrying another run id are dropped instead of rendered', async () => {
  const state = await runChat('event: start\ndata: {"sessionId":"s1","runId":"run-B"}\n\n'
    + 'event: token\ndata: {"runId":"run-A","text":"stale image result "}\n\n'
    + 'event: tool_call_done\ndata: {"runId":"run-A","toolCallId":"call-1","toolName":"image_generation","status":"SUCCEEDED","durationMs":20000}\n\n'
    + 'event: token\ndata: {"runId":"run-B","text":"fresh answer"}\n\n'
    + 'event: done\ndata: {"runId":"run-B","output":"fresh answer"}\n\n');
  assert.equal(state.currentRunId.value, 'run-B');
  assert.equal(state.messages.value[1].content, 'fresh answer');
  assert.equal(state.messages.value[1].toolCalls.length, 0);
});

test('a run adopts its own id rather than one left over from the previous run', async () => {
  const state = await runChat('event: start\ndata: {"sessionId":"s1","runId":"run-B"}\n\n'
    + 'event: token\ndata: {"runId":"run-B","text":"second answer"}\n\n'
    + 'event: done\ndata: {"runId":"run-B","output":"second answer"}\n\n',
    false, { currentRunId: { value: 'run-A' } });
  // The previous run's id must not make this run's own START look like a stale frame.
  assert.equal(state.currentRunId.value, 'run-B');
  assert.equal(state.messages.value[1].content, 'second answer');
});

test('Stop reads as a cancel, not as a broken stream', async () => {
  const state = await runChat('event: start\ndata: {"runId":"run-A"}\n\n'
    + 'event: tool_call_start\ndata: {"runId":"run-A","toolCallId":"call-1","toolName":"knowledge_read","status":"RUNNING"}\n\n',
    false, {}, 2);
  assert.deepEqual(state.toasts, []);
  assert.doesNotMatch(String(state.messages.value[1].content), /streamInterrupted/);
  assert.equal(state.messages.value[1].toolCalls[0].status, 'CANCELLED');
});

test('EOF without a terminal event reports interruption and finishes running tool cards', async () => {
  const state = await runChat('event: tool_call_start\n'
    + 'data: {"toolCallId":"call-1","toolName":"knowledge_read","status":"RUNNING"}\n\n', true);
  assert.match(state.messages.value[1].content, /streamInterrupted/);
  assert.equal(state.messages.value[1].toolCalls[0].status, 'FAILED');
});

// Fresh per test: the send loop drains attachedFiles, so a shared fixture would leave every
// test after the first with nothing to send.
const voiceState = () => ({
  attachedFiles: {
    value: [{ file: { name: 'voice-input.webm' }, url: '/files/input/voice.webm', voice: true }],
  },
  inputText: { value: '' },
});

test('a recording is sent as a voice turn under its own key, never as a file', async () => {
  const state = await runChat(
    'event: voice_transcript\ndata: {"text":"今天星期几？"}\n\n'
    + 'event: done\ndata: {"output":"今天星期三。"}\n\n',
    false, voiceState(), 0, { voice: true });

  const [, text, context, , interactionMode] = state.chatCalls[0];
  assert.equal(interactionMode, 'VOICE');
  assert.equal(context.VoiceInput, '/files/input/voice.webm');
  // Leaving it in File too would hand the model the audio it has already been told about.
  assert.equal(context.File, undefined);
  // The transcript is the message, so the request itself carries no text.
  assert.equal(text, '');
  assert.equal(state.messages.value[0].content, '今天星期几？');
});

test('a chosen audio file stays an ordinary attachment on a text turn', async () => {
  const state = await runChat('event: done\ndata: {"output":"ok"}\n\n', false, {
    attachedFiles: { value: [{ file: { name: 'meeting.mp3' }, url: '/files/input/meeting.mp3' }] },
  });

  const [, , context, , interactionMode] = state.chatCalls[0];
  // The spec's whole point: an attached mp3 must not be mistaken for a microphone turn.
  assert.equal(interactionMode, 'TEXT');
  assert.equal(context.VoiceInput, undefined);
  assert.equal(context.File, '/files/input/meeting.mp3');
});

test('a synthesized answer is played and the text answer is kept', async () => {
  const state = await runChat(
    'event: voice_output\ndata: {"downloadUrl":"/api/artifacts/a-1","type":"AUDIO","mimeType":"audio/mpeg"}\n\n'
    + 'event: done\ndata: {"output":"今天星期三。"}\n\n',
    false, voiceState(), 0, { voice: true });

  assert.deepEqual(state.playedAudio, ['/api/artifacts/a-1']);
  assert.equal(state.artifacts.length, 1);
  assert.equal(state.messages.value[1].content, '今天星期三。');
});

test('a failed synthesis reports itself and still keeps the text answer', async () => {
  const state = await runChat(
    'event: voice_error\ndata: {"message":"语音合成失败"}\n\n'
    + 'event: done\ndata: {"output":"今天星期三。"}\n\n',
    false, voiceState(), 0, { voice: true });

  assert.deepEqual(state.toasts, ['语音合成失败']);
  // Not terminal: the turn's answer survives the missing audio.
  assert.equal(state.messages.value[1].content, '今天星期三。');
});

test('streamed speech segments play in the order they were generated', async () => {
  const state = await runChat(
    'event: voice_segment\ndata: {"seq":0,"downloadUrl":"/api/artifacts/s0"}\n\n'
    + 'event: voice_segment\ndata: {"seq":1,"downloadUrl":"/api/artifacts/s1"}\n\n'
    + 'event: voice_segment\ndata: {"seq":2,"downloadUrl":"/api/artifacts/s2"}\n\n'
    + 'event: voice_output\ndata: {"downloadUrl":"/api/artifacts/full","streamedSegments":3,"type":"AUDIO"}\n\n'
    + 'event: done\ndata: {"output":"回答"}\n\n',
    false, voiceState(), 0, { voice: true });

  // Back to back, not all at once: overlapping playback was the reason for the queue.
  assert.deepEqual(state.playedAudio, [
    '/api/artifacts/s0', '/api/artifacts/s1', '/api/artifacts/s2',
  ]);
  // Only the merged recording becomes a block, so the session keeps one player per answer.
  assert.deepEqual(state.artifacts.map(a => a.downloadUrl), ['/api/artifacts/full']);
});

test('playback waits for a buffer instead of starting on the first crumb', async () => {
  const state = await runChat(
    'event: voice_segment\ndata: {"seq":0,"downloadUrl":"/api/artifacts/s0"}\n\n'
    + 'event: voice_segment\ndata: {"seq":1,"downloadUrl":"/api/artifacts/s1"}\n\n',
    // No terminal frame: the stream is still open and only two seconds are buffered, well
    // under the start threshold. Starting here is what leaves the audio waiting mid-answer.
    true, voiceState(), 0, { voice: true });

  assert.deepEqual(state.playedAudio, ['/api/artifacts/s0', '/api/artifacts/s1']);
  assert.deepEqual(state.scheduledAt, []);
});

test('segments are scheduled back to back, leaving no silence between sentences', async () => {
  const state = await runChat(
    'event: voice_segment\ndata: {"seq":0,"downloadUrl":"/api/artifacts/s0"}\n\n'
    + 'event: voice_segment\ndata: {"seq":1,"downloadUrl":"/api/artifacts/s1"}\n\n'
    + 'event: voice_segment\ndata: {"seq":2,"downloadUrl":"/api/artifacts/s2"}\n\n'
    + 'event: done\ndata: {"output":"回答"}\n\n',
    false, voiceState(), 0, { voice: true });

  assert.equal(state.scheduledAt.length, 3);
  // Each one starts exactly when the previous ends. Chaining <audio> elements instead left a
  // decode-and-startup gap at every boundary, which is what made the speech stutter.
  for (let i = 1; i < state.scheduledAt.length; i++) {
    const gap = state.scheduledAt[i] - state.scheduledAt[i - 1];
    assert.ok(Math.abs(gap - 1) < 1e-9, `segment ${i} left a ${gap}s silence`);
  }
});

test('the merged recording is offered for replay without being read aloud again', async () => {
  const state = await runChat(
    'event: voice_segment\ndata: {"seq":0,"downloadUrl":"/api/artifacts/s0"}\n\n'
    + 'event: voice_output\ndata: {"downloadUrl":"/api/artifacts/full","streamedSegments":1,"type":"AUDIO"}\n\n'
    + 'event: done\ndata: {"output":"回答"}\n\n',
    false, voiceState(), 0, { voice: true });

  // The words were already spoken as they were written; replaying the whole thing would
  // make the user hear the answer twice.
  assert.deepEqual(state.playedAudio, ['/api/artifacts/s0']);
  assert.equal(state.artifacts.length, 1);
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
  // Sliced in for the same reason as sendMessage: deleteSession silences playback on its way out.
  const deleteSession = runInNewContext(voicePlayback + deleteSessionSource + '\ndeleteSession;', {
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
