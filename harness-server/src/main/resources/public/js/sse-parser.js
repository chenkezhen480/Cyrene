/**
 * Incremental Server-Sent Events parser.
 * Dispatches one normalized event only after its terminating blank line.
 */
(function exposeSseParser(root) {
  function createParser(onEvent) {
    if (typeof onEvent !== 'function') {
      throw new TypeError('onEvent must be a function');
    }

    let buffer = '';
    let eventType = '';
    let eventId = '';
    let dataLines = [];

    function dispatch() {
      if (dataLines.length === 0) {
        eventType = '';
        return;
      }
      onEvent({
        type: eventType || 'message',
        data: dataLines.join('\n'),
        id: eventId,
      });
      eventType = '';
      dataLines = [];
    }

    function processLine(rawLine) {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
      if (line === '') {
        dispatch();
        return;
      }
      if (line.startsWith(':')) return;

      const separator = line.indexOf(':');
      const field = separator === -1 ? line : line.slice(0, separator);
      let value = separator === -1 ? '' : line.slice(separator + 1);
      if (value.startsWith(' ')) value = value.slice(1);

      switch (field) {
        case 'event':
          eventType = value;
          break;
        case 'data':
          dataLines.push(value);
          break;
        case 'id':
          if (!value.includes('\0')) eventId = value;
          break;
        default:
          break;
      }
    }

    return {
      feed(chunk) {
        buffer += chunk || '';
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        lines.forEach(processLine);
      },
      finish() {
        if (buffer !== '') processLine(buffer);
        buffer = '';
        dispatch();
      },
    };
  }

  const api = { createParser };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.CyreneSSE = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
