const test = require('node:test');
const assert = require('node:assert/strict');
const { upsert } = require('../../main/resources/public/js/tool-call-state.js');

test('tracks same-name calls by id and applies duplicate events idempotently', () => {
  const message = { toolCalls: [], toolCallsById: new Map() };

  upsert(message, { toolCallId: 'call-1', toolName: 'search', status: 'CREATED' });
  upsert(message, { toolCallId: 'call-2', toolName: 'search', status: 'CREATED' });
  upsert(message, { toolCallId: 'call-1', toolName: 'search', status: 'CREATED' });
  upsert(message, { toolCallId: 'call-2', toolName: 'search', status: 'RUNNING' });
  upsert(message, { toolCallId: 'call-2', toolName: 'search', status: 'SUCCEEDED' });
  upsert(message, {
    toolCallId: 'call-2',
    toolName: 'search',
    text: 'bounded output',
    textLength: 120,
    truncated: true,
  });
  upsert(message, { toolCallId: 'call-2', toolName: 'search', status: 'RUNNING' });

  assert.equal(message.toolCalls.length, 2);
  assert.equal(message.toolCallsById.get('call-1').status, 'CREATED');
  assert.equal(message.toolCallsById.get('call-2').status, 'SUCCEEDED');
  assert.equal(message.toolCallsById.get('call-2').outputText, 'bounded output');
  assert.equal(message.toolCallsById.get('call-2').outputTextLength, 120);
  assert.equal(message.toolCallsById.get('call-2').outputTruncated, true);
});
