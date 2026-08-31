/** Stable, idempotent frontend state for streamed tool calls. */
(function exposeToolCallState(root) {
  const transitions = {
    CREATED: new Set(['RUNNING', 'AWAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED', 'CANCELLED']),
    RUNNING: new Set(['AWAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED', 'CANCELLED']),
    AWAITING_CONFIRMATION: new Set(['RUNNING', 'FAILED', 'CANCELLED']),
    SUCCEEDED: new Set(),
    FAILED: new Set(),
    CANCELLED: new Set(),
  };

  function canTransition(currentStatus, nextStatus) {
    if (!Object.hasOwn(transitions, nextStatus)) {
      throw new Error(`Unknown tool status: ${nextStatus}`);
    }
    if (!currentStatus || currentStatus === nextStatus) return true;
    const allowed = transitions[currentStatus];
    if (!allowed) throw new Error(`Unknown tool status: ${currentStatus}`);
    return allowed.has(nextStatus);
  }

  function upsert(message, payload) {
    if (!payload || typeof payload.toolCallId !== 'string' || !payload.toolCallId) {
      throw new Error('Tool event is missing toolCallId');
    }
    if (!(message.toolCallsById instanceof Map)) {
      message.toolCallsById = new Map();
      (message.toolCalls || []).forEach(toolCall => {
        if (toolCall.id) message.toolCallsById.set(toolCall.id, toolCall);
      });
    }

    let toolCall = message.toolCallsById.get(payload.toolCallId);
    if (!toolCall) {
      canTransition(null, payload.status);
      toolCall = {
        id: payload.toolCallId,
        name: payload.toolName,
        arguments: payload.arguments,
        status: payload.status,
        durationMs: payload.durationMs,
        errorSummary: payload.errorSummary,
        outputText: payload.text,
        outputTextLength: payload.textLength,
        outputTruncated: payload.truncated,
      };
      message.toolCallsById.set(payload.toolCallId, toolCall);
      message.toolCalls.push(toolCall);
      return toolCall;
    }

    if (payload.toolName !== undefined) toolCall.name = payload.toolName;
    if (payload.arguments !== undefined) toolCall.arguments = payload.arguments;
    if (payload.status !== undefined && canTransition(toolCall.status, payload.status)) {
      toolCall.status = payload.status;
    }
    if (payload.durationMs !== undefined) toolCall.durationMs = payload.durationMs;
    if (payload.errorSummary !== undefined) toolCall.errorSummary = payload.errorSummary;
    if (payload.text !== undefined) toolCall.outputText = payload.text;
    if (payload.textLength !== undefined) toolCall.outputTextLength = payload.textLength;
    if (payload.truncated !== undefined) toolCall.outputTruncated = payload.truncated;
    return toolCall;
  }

  function formatArguments(argumentsValue) {
    if (argumentsValue === undefined || argumentsValue === null) return '';
    return JSON.stringify(argumentsValue, null, 2);
  }

  const api = { upsert, formatArguments };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.CyreneToolCalls = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
