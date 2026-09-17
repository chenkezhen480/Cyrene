const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');

const source = readFileSync(join(__dirname, '../../main/resources/public/js/app.js'), 'utf8');

// The WAV encoder decides whether the upstream ASR accepts the recording at all, and its
// output is raw bytes nothing else in the app validates. Slice it out and check the bytes.
const encoderSource = source.slice(source.indexOf('    function encodeWav('),
  source.indexOf('    async function toggleVoiceInput()'))
  + '\n({ encodeWav });';

function encoder() {
  return runInNewContext(encoderSource, { Blob });
}

function fakeAudioBuffer(channels) {
  return {
    length: channels[0].length,
    numberOfChannels: channels.length,
    getChannelData: index => Float32Array.from(channels[index]),
  };
}

function readHeader(blobBytes) {
  const view = new DataView(blobBytes.buffer, blobBytes.byteOffset, blobBytes.byteLength);
  const ascii = (offset, length) => String.fromCharCode(
    ...Array.from({ length }, (_, i) => view.getUint8(offset + i)));
  return {
    riff: ascii(0, 4),
    wave: ascii(8, 4),
    fmt: ascii(12, 4),
    audioFormat: view.getUint16(20, true),
    channels: view.getUint16(22, true),
    sampleRate: view.getUint32(24, true),
    byteRate: view.getUint32(28, true),
    blockAlign: view.getUint16(32, true),
    bitsPerSample: view.getUint16(34, true),
    dataTag: ascii(36, 4),
    dataBytes: view.getUint32(40, true),
    riffSize: view.getUint32(4, true),
  };
}

test('writes a 16 kHz mono PCM header the ASR endpoint accepts', async () => {
  const blob = encoder().encodeWav(fakeAudioBuffer([[0, 0.5, -0.5]]), 16000);
  const bytes = new Uint8Array(await blob.arrayBuffer());
  const header = readHeader(bytes);

  assert.equal(blob.type, 'audio/wav');
  assert.equal(header.riff, 'RIFF');
  assert.equal(header.wave, 'WAVE');
  assert.equal(header.fmt, 'fmt ');
  assert.equal(header.dataTag, 'data');
  assert.equal(header.audioFormat, 1, 'must be uncompressed PCM');
  assert.equal(header.channels, 1, 'mono keeps the upload small');
  assert.equal(header.sampleRate, 16000);
  assert.equal(header.bitsPerSample, 16);
  assert.equal(header.blockAlign, 2);
  assert.equal(header.byteRate, 32000);
  assert.equal(bytes.length, 44 + 3 * 2, 'header plus one 16-bit sample per frame');
  assert.equal(header.dataBytes, 6);
  assert.equal(header.riffSize, bytes.length - 8, 'RIFF size excludes the first 8 bytes');
});

test('clamps samples instead of letting them wrap around', async () => {
  const blob = encoder().encodeWav(fakeAudioBuffer([[2, -2, 1, -1]]), 8000);
  const view = new DataView(await blob.arrayBuffer());
  const first = view.getInt16(44, true);
  const second = view.getInt16(46, true);

  // A Float32 out of range must saturate, not wrap: wrapping turns a loud passage into noise.
  assert.equal(first, 32767);
  assert.equal(second, -32768);
  assert.equal(view.getInt16(48, true), 32767);
  assert.equal(view.getInt16(50, true), -32768);
});

test('downmixes stereo by averaging, so both speakers survive', async () => {
  const blob = encoder().encodeWav(fakeAudioBuffer([[1, 0], [-1, 0]]), 8000);
  const view = new DataView(await blob.arrayBuffer());

  assert.equal(view.getUint16(22, true), 1, 'output is always mono');
  assert.equal(view.getInt16(44, true), 0, 'a hard-left + hard-right pair cancels to silence');
  assert.equal(view.getInt16(46, true), 0);
});

test('honours the sample rate the browser actually gave us', async () => {
  // Browsers may ignore the requested rate; the header must describe what is really inside.
  const blob = encoder().encodeWav(fakeAudioBuffer([[0, 0]]), 48000);
  const view = new DataView(await blob.arrayBuffer());

  assert.equal(view.getUint32(24, true), 48000);
  assert.equal(view.getUint32(28, true), 96000);
});
