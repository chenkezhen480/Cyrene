const test = require('node:test');
const assert = require('node:assert/strict');
const { createParser } = require('../../main/resources/public/js/sse-parser.js');

test('parses fragmented CRLF events and joins multiline data', () => {
  const events = [];
  const parser = createParser(event => events.push(event));

  parser.feed('event: token\r\nda');
  parser.feed('ta: {"text":"hello"}\r\n\r\nevent: detail\r\n');
  parser.feed('data: first\r\ndata: second\r\n\r\n');

  assert.deepEqual(events, [
    { type: 'token', data: '{"text":"hello"}', id: '' },
    { type: 'detail', data: 'first\nsecond', id: '' },
  ]);
});

test('dispatches the final event at stream end and ignores comments', () => {
  const events = [];
  const parser = createParser(event => events.push(event));

  parser.feed(': keep-alive\ndata: final');
  parser.finish();

  assert.deepEqual(events, [{ type: 'message', data: 'final', id: '' }]);
});
