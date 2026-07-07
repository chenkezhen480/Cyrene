/**
 * Cyrene Web UI — Vue 3 SPA
 * 在时间的涟漪中，记录与被记住
 */
const { createApp, ref, reactive, computed, watch, onMounted, onUnmounted, nextTick, provide, inject } = Vue;

// ── Markdown Renderer ──
marked.setOptions({
  breaks: true,
  gfm: true,
});

function renderMarkdown(text) {
  if (!text) return '';
  try {
    // Sanitize: escape raw HTML tags that aren't markdown
    const html = marked.parse(text);
    return html;
  } catch (e) {
    return text;
  }
}

// ── SVG Icons ──
const Icons = {
  chat: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`,
  knowledge: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>`,
  audit: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>`,
  config: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`,
  send: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>`,
  upload: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>`,
  mic: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>`,
  plus: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>`,
  trash: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
  edit: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>`,
  refresh: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>`,
  menu: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>`,
  close: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`,
  save: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>`,
  search: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>`,
};

// ── i18n (data in i18n.js) ──

// ── Ripple Effect Helper — Crystal Drop (Global) ──
function createRipple(event) {
  const size = 36;
  const x = event.clientX - size / 2;
  const y = event.clientY - size / 2;
  for (let i = 0; i < 2; i++) {
    const ring = document.createElement('span');
    ring.className = 'global-ripple';
    ring.style.width = ring.style.height = `${size}px`;
    ring.style.left = `${x}px`;
    ring.style.top = `${y}px`;
    if (i === 1) ring.classList.add('global-ripple-delayed');
    document.body.appendChild(ring);
    ring.addEventListener('animationend', () => ring.remove());
  }
}

// ── Toast System ──
const toastState = reactive({ items: [] });
let toastId = 0;

function showToast(message, type = 'info', duration = 3000) {
  const id = ++toastId;
  toastState.items.push({ id, message, type });
  setTimeout(() => {
    const idx = toastState.items.findIndex(t => t.id === id);
    if (idx !== -1) toastState.items.splice(idx, 1);
  }, duration);
}

// ── Components ──

// Toast Container
const ToastContainer = {
  setup() {
    return { toasts: toastState };
  },
  template: `
    <div class="toast-container">
      <div v-for="t in toasts.items" :key="t.id" :class="['toast', 'toast-' + t.type]">
        {{ t.message }}
      </div>
    </div>
  `
};

// Stars + Memory Fragments Background
const StarsBackground = {
  setup() {
    const stars = ref([]);
    const fragments = ref([]);
    onMounted(() => {
      const starArr = [];
      for (let i = 0; i < 20; i++) {
        starArr.push({
          id: i,
          left: Math.random() * 100 + '%',
          top: Math.random() * 100 + '%',
          delay: Math.random() * 5 + 's',
          duration: (3 + Math.random() * 4) + 's',
          dim: Math.random() > 0.6,
        });
      }
      stars.value = starArr;

      const fragArr = [];
      for (let i = 0; i < 12; i++) {
        const size = 4 + Math.random() * 10;
        fragArr.push({
          id: i,
          left: Math.random() * 100 + '%',
          top: Math.random() * 100 + '%',
          size: size + 'px',
          delay: Math.random() * 8 + 's',
          duration: (8 + Math.random() * 12) + 's',
          rotate: Math.random() * 360 + 'deg',
          variant: Math.floor(Math.random() * 3), // 0=rose, 1=iris, 2=gold
          drift: (Math.random() - 0.5) * 60 + 'px',
        });
      }
      fragments.value = fragArr;
    });
    return { stars, fragments };
  },
  template: `
    <div>
      <div v-for="s in stars" :key="'s'+s.id"
           :class="['star', s.dim ? 'star-dim' : '']"
           :style="{ left: s.left, top: s.top, animationDelay: s.delay, animationDuration: s.duration }">
      </div>
      <div v-for="f in fragments" :key="'f'+f.id"
           :class="['fragment', 'fragment-' + f.variant]"
           :style="{
             left: f.left, top: f.top,
             width: f.size, height: f.size,
             animationDelay: f.delay, animationDuration: f.duration,
             '--rotate': f.rotate, '--drift': f.drift
           }">
      </div>
    </div>
  `
};

// Empty State
const EmptyState = {
  props: ['icon', 'title', 'hint'],
  template: `
    <div class="empty-state">
      <div class="petal" v-for="i in 5" :key="i"></div>
      <div class="empty-state-icon breathing-glow" v-html="icon"></div>
      <div class="empty-state-title">{{ title }}</div>
      <div class="empty-state-hint" v-if="hint">{{ hint }}</div>
    </div>
  `
};

// Pre-config Modal
const PreConfigModal = {
  props: ['visible'],
  emits: ['close', 'complete'],
  setup(props, { emit }) {
    const Icons = inject('Icons');
    const t = inject('t');
    const step = ref('input'); // input | scanning | result | done
    const sourceRoot = ref('');
    const baseUrl = ref('');
    const scanResult = ref(null);
    const error = ref('');
    const scanning = ref(false);

    async function startScan() {
      if (!sourceRoot.value.trim()) {
        error.value = t('enterProjectPath');
        return;
      }
      error.value = '';
      step.value = 'scanning';
      scanning.value = true;
      try {
        const result = await CyreneAPI.scanProject(sourceRoot.value.trim(), baseUrl.value.trim());
        scanResult.value = result;
        step.value = 'result';
      } catch (e) {
        error.value = e.message;
        step.value = 'input';
      } finally {
        scanning.value = false;
      }
    }

    async function confirmGenerate() {
      try {
        // Pass full config (discoveredAt, sourceRoot, baseUrl, endpoints)
        const config = {
          discoveredAt: scanResult.value.discoveredAt || new Date().toISOString(),
          sourceRoot: scanResult.value.sourceRoot || sourceRoot.value.trim(),
          baseUrl: scanResult.value.baseUrl || baseUrl.value.trim() || '',
          endpoints: (scanResult.value.endpoints || []).map(ep => ({ ...ep, confirmed: true })),
        };
        await CyreneAPI.generateConfig(config);
        step.value = 'done';
        showToast(t('configGenerated'), 'success');
        setTimeout(() => emit('complete'), 1500);
      } catch (e) {
        error.value = e.message;
      }
    }

    function skip() {
      emit('close');
    }

    return { Icons, t, step, sourceRoot, baseUrl, scanResult, error, scanning, startScan, confirmGenerate, skip };
  },
  template: `
    <div class="modal-overlay" v-if="visible" @click.self="skip">
      <div class="modal">
        <!-- Step: Input -->
        <template v-if="step === 'input'">
          <div class="modal-header">
            <div class="modal-title">{{ t('projectApiSetup') }}</div>
            <div class="modal-subtitle">{{ t('projectSubtitle') }}</div>
          </div>
          <div class="modal-body">
            <div class="input-group">
              <label class="input-label">{{ t('projectPath') }}</label>
              <input class="input" v-model="sourceRoot"
                     placeholder="/path/to/your/project"
                     @keydown.enter="startScan" />
            </div>
            <div class="input-group mt-4">
              <label class="input-label">{{ t('serviceBaseUrl') }} <span class="text-xs text-ash">{{ t('optional') }}</span></label>
              <input class="input" v-model="baseUrl"
                     placeholder="http://localhost:8081"
                     @keydown.enter="startScan" />
            </div>
            <div v-if="error" class="text-sm" style="color: var(--error);">{{ error }}</div>
            <p class="text-sm text-ash mt-4">
              {{ t('scanInstructions1') }}
              {{ t('scanInstructions2') }}
            </p>
          </div>
          <div class="modal-footer">
            <button class="btn btn-ghost" @click="skip">{{ t('later') }}</button>
            <button class="btn btn-primary" @click="startScan">{{ t('startScan') }}</button>
          </div>
        </template>

        <!-- Step: Scanning -->
        <template v-if="step === 'scanning'">
          <div class="modal-header">
            <div class="modal-title">{{ t('scanning') }}</div>
            <div class="modal-subtitle">{{ t('scanningSubtitle') }}</div>
          </div>
          <div class="modal-body" style="text-align: center; padding: 3rem;">
            <div class="loading-dots" style="justify-content: center;">
              <span></span><span></span><span></span>
            </div>
            <p class="text-sm text-ash mt-4">{{ t('scanningHint') }}</p>
          </div>
        </template>

        <!-- Step: Result -->
        <template v-if="step === 'result'">
          <div class="modal-header">
            <div class="modal-title">{{ t('scanComplete') }}</div>
            <div class="modal-subtitle">
              {{ scanResult?.endpoints?.length || 0 }} {{ t('foundNEndpoints') }}
              <span v-if="scanResult?.source === 'code_scan'" class="tag tag-dusk" style="margin-left: 8px;">{{ t('aiGenerated') }}</span>
              <span v-else class="tag tag-gold" style="margin-left: 8px;">OpenAPI</span>
            </div>
          </div>
          <div class="modal-body">
            <div class="card" style="max-height: 300px; overflow-y: auto;">
              <div v-for="ep in (scanResult?.endpoints || [])" :key="ep.id" class="scan-result-row">
                <span class="tag tag-iris" style="font-size: 11px;">{{ ep.method }}</span>
                <span class="text-sm" style="color: var(--mist);">{{ ep.path }}</span>
                <span class="text-xs text-ash">{{ ep.name }}</span>
                <span class="text-xs text-dusk truncate">{{ ep.description }}</span>
              </div>
              <div v-if="!scanResult?.endpoints?.length" class="p-6 text-center text-ash text-sm">
                {{ t('noEndpoints') }}
              </div>
            </div>
            <div v-if="error" class="text-sm mt-4" style="color: var(--error);">{{ error }}</div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-ghost" @click="step = 'input'">{{ t('rescan') }}</button>
            <button class="btn btn-ghost" @click="skip">{{ t('cancel') }}</button>
            <button class="btn btn-primary" @click="confirmGenerate">{{ t('confirmGenerate') }}</button>
          </div>
        </template>

        <!-- Step: Done -->
        <template v-if="step === 'done'">
          <div class="modal-header">
            <div class="modal-title">{{ t('configDone') }}</div>
            <div class="modal-subtitle">{{ t('configDoneSubtitle') }}</div>
          </div>
          <div class="modal-body" style="text-align: center; padding: 2rem;">
            <p class="text-sm text-ash">{{ t('configDoneHint') }}</p>
          </div>
        </template>
      </div>
    </div>
  `
};

// ── Chat Page ──
const ChatPage = {
  components: { EmptyState },
  setup() {
    const Icons = inject('Icons');
    const userId = inject('userId');
    const t = inject('t');
    const sessions = ref([]);
    const currentSessionId = ref(null);
    const messages = ref([]);
    const inputText = ref('');
    const isStreaming = ref(false);
    const messagesEl = ref(null);

    async function loadSessions() {
      if (!userId.value) return;
      try {
        const data = await CyreneAPI.listSessions(userId.value, { limit: 20 });
        sessions.value = data.sessions || data || [];
      } catch (e) {
        console.error('Failed to load sessions:', e);
      }
    }

    async function selectSession(sid) {
      currentSessionId.value = sid;
      try {
        const data = await CyreneAPI.getMessages(sid, { limit: 50, direction: 'asc' });
        messages.value = data.messages || data || [];
        scrollToBottom();
      } catch (e) {
        console.error('Failed to load messages:', e);
      }
    }

    async function newSession() {
      currentSessionId.value = null;
      messages.value = [];
    }

    async function sendMessage() {
      const text = inputText.value.trim();
      if (!text || isStreaming.value) return;

      if (!userId.value) {
        showToast(t('setUserIdFirst'), 'error');
        return;
      }

      // Add user message to UI
      messages.value.push({ role: 'user', content: text });
      inputText.value = '';
      scrollToBottom();

      isStreaming.value = true;
      messages.value.push({ role: 'assistant', content: '' });
      const msgIdx = messages.value.length - 1;

      try {
        const resp = await CyreneAPI.chat(currentSessionId.value, text, {
          userId: userId.value,
          outputMode: 'streaming',
        });

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let sseBuffer = '';
        let currentEventType = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          sseBuffer += decoder.decode(value, { stream: true });
          const lines = sseBuffer.split('\n');
          sseBuffer = lines.pop() || '';

          for (const line of lines) {
            if (line.startsWith('event: ')) {
              currentEventType = line.slice(7).trim();
              continue;
            }
            if (line.startsWith('data: ')) {
              const data = line.slice(6);
              try {
                const parsed = JSON.parse(data);
                switch (currentEventType) {
                  case 'start':
                    if (parsed.sessionId) {
                      currentSessionId.value = parsed.sessionId;
                    }
                    break;
                  case 'token':
                    if (parsed.text) messages.value[msgIdx].content += parsed.text;
                    break;
                  case 'step':
                    if (parsed.toolCalls && parsed.toolCalls.length) {
                      const tools = parsed.toolCalls.join(', ');
                      const crystal = '<svg width="15" height="15" viewBox="0 0 16 16" fill="none" style="vertical-align:-2px;margin-right:3px"><defs><radialGradient id="cg"><stop offset="0%" stop-color="rgba(232,160,191,0.6)"/><stop offset="100%" stop-color="rgba(139,126,200,0.15)"/></radialGradient></defs><path d="M8 0.5L9.5 5 14 3.5 11 7.5 15.5 8 11 8.5 14 12.5 9.5 11 8 15.5 6.5 11 2 12.5 5 8.5 0.5 8 5 7.5 2 3.5 6.5 5z" fill="url(#cg)" stroke="var(--iris)" stroke-width="0.5" stroke-linejoin="round"/><circle cx="8" cy="8" r="1.8" fill="rgba(232,160,191,0.7)"/><circle cx="8" cy="8" r="0.8" fill="white" opacity="0.6"/></svg>';
                      const stepHtml = `\n\n<div class="tool-call-block"><div class="tool-call-header">${crystal} Step ${parsed.stepNumber || ''}: ${tools}</div>${parsed.action ? '<div class="tool-call-action">' + parsed.action + '</div>' : ''}</div>\n\n`;
                      messages.value[msgIdx].content += stepHtml;
                    }
                    break;
                  case 'compress':
                    const modeLabel = parsed.mode === 'major' ? t('majorCompress') : t('minorCompress');
                    const compressHtml = `\n\n<div class="compress-block"><span class="compress-icon">🗜️</span> <span class="compress-label">${modeLabel}</span> <span class="compress-detail">${parsed.detail || ''}</span></div>\n\n`;
                    messages.value[msgIdx].content += compressHtml;
                    break;
                  case 'done':
                    if (parsed.output != null) messages.value[msgIdx].content = parsed.output;
                    if (parsed.sessionId) {
                      currentSessionId.value = parsed.sessionId;
                    }
                    break;
                  case 'error':
                    messages.value[msgIdx].content = `Error: ${parsed.error || t('unknownError')}`;
                    showToast(parsed.error || t('requestFailed'), 'error');
                    break;
                  default:
                    if (parsed.text) messages.value[msgIdx].content += parsed.text;
                    else if (parsed.output != null) messages.value[msgIdx].content = parsed.output;
                }
              } catch (e) {
                messages.value[msgIdx].content += data;
              }
              currentEventType = '';
            }
          }

          // Force yield to macrotask queue so browser can repaint
          scrollToBottom();
          await new Promise(r => setTimeout(r, 0));
        }

        // Reload sessions list
        loadSessions();
      } catch (e) {
        messages.value[msgIdx].content = `Error: ${e.message}`;
      } finally {
        isStreaming.value = false;
        scrollToBottom();
      }
    }

    function scrollToBottom() {
      nextTick(() => {
        if (messagesEl.value) {
          messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
        }
      });
    }

    function handleKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    }

    // Watch for userId changes (set globally) to load sessions
    watch(userId, (val) => { if (val) loadSessions(); }, { immediate: true });

    onMounted(() => {
      if (userId.value) loadSessions();
    });

    return {
      Icons, t, sessions, currentSessionId, messages, inputText, isStreaming,
      messagesEl, userId, renderMarkdown,
      loadSessions, selectSession, newSession, sendMessage,
      handleKeydown,
    };
  },
  template: `
    <div class="chat-container">
      <!-- Session list (side) + Messages (main) -->
      <div style="display: flex; flex: 1; overflow: hidden;">
        <!-- Sessions sidebar -->
        <div style="width: 240px; border-right: 1px solid var(--gold-line); display: flex; flex-direction: column; flex-shrink: 0;">
          <div style="padding: var(--space-3); border-bottom: 1px solid var(--gold-line);">
            <button class="btn btn-secondary w-full" @click="newSession">
              <span v-html="Icons.plus" style="width:14px;height:14px;"></span>
              {{ t('newChat') }}
            </button>
          </div>
          <div style="flex: 1; overflow-y: auto; padding: var(--space-2);">
            <div v-for="s in sessions" :key="s.id"
                 :class="['nav-item', currentSessionId === s.id ? 'active' : '']"
                 @click="selectSession(s.id)"
                 style="padding: var(--space-2) var(--space-3); font-size: var(--text-sm);">
              <span class="truncate">{{ s.title || s.id || t('unnamedChat') }}</span>
            </div>
            <div v-if="!sessions.length" class="p-4 text-center text-xs text-ash">
              {{ t('noChats') }}
            </div>
          </div>
        </div>

        <!-- Messages area -->
        <div style="flex: 1; display: flex; flex-direction: column; min-width: 0;">
          <div ref="messagesEl" class="chat-messages">
            <template v-if="messages.length">
              <div v-for="(msg, i) in messages" :key="i"
                   :class="['message', msg.role === 'user' ? 'message-user' : 'message-assistant']">
                <div class="message-avatar">
                  {{ msg.role === 'user' ? 'U' : 'C' }}
                </div>
                <div v-if="msg.role === 'user'" class="message-content">{{ msg.content }}</div>
                <div v-else-if="msg.content" class="message-content md-body" v-html="renderMarkdown(msg.content)"></div>
                <div v-else class="message-content"><div class="loading-dots" v-meteor><span></span><span></span><span></span></div></div>
              </div>
            </template>
            <empty-state v-else
              :icon="Icons.chat"
              :title="t('chatEmptyTitle')"
              :hint="t('chatEmptyHint')" />
          </div>

          <!-- Input area -->
          <div class="chat-input-area">
            <div class="chat-input-wrapper">
              <textarea class="chat-input" v-model="inputText"
                        :placeholder="t('chatPlaceholder')"
                        @keydown="handleKeydown"
                        rows="1"></textarea>
              <div class="chat-actions">
                <button class="chat-action-btn" :title="t('uploadFile')">
                  <span v-html="Icons.upload" style="width:18px;height:18px;"></span>
                </button>
                <button class="chat-action-btn" :title="t('voiceInput')">
                  <span v-html="Icons.mic" style="width:18px;height:18px;"></span>
                </button>
                <button class="chat-send-btn" @click="sendMessage"
                        :disabled="!inputText.trim() || isStreaming" :title="t('send')">
                  <span v-html="Icons.send" style="width:16px;height:16px;"></span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  `
};

// ── Knowledge Page ──
const KnowledgePage = {
  components: { EmptyState },
  setup() {
    const Icons = inject('Icons');
    const t = inject('t');
    const collections = ref([]);
    const selectedCollection = ref('');
    const documents = ref([]);
    const uploading = ref(false);
    const uploadCollection = ref('');
    const fileInput = ref(null);
    const editingDoc = ref(null);
    const editingContent = ref('');
    const saving = ref(false);

    async function loadCollections() {
      try {
        const data = await CyreneAPI.listCollections();
        collections.value = data.collections || [];
      } catch (e) {
        collections.value = [];
      }
    }

    async function loadDocuments() {
      if (!selectedCollection.value) return;
      try {
        const data = await CyreneAPI.listKnowledge(selectedCollection.value);
        documents.value = data.documents || data || [];
      } catch (e) {
        documents.value = [];
      }
    }

    async function uploadFile() {
      const file = fileInput.value?.files?.[0];
      if (!file) return;
      if (!uploadCollection.value.trim()) {
        showToast(t('enterCollectionName'), 'error');
        return;
      }
      uploading.value = true;
      try {
        await CyreneAPI.uploadKnowledge(file, uploadCollection.value.trim());
        showToast(t('uploadSuccess'), 'success');
        selectedCollection.value = uploadCollection.value.trim();
        loadCollections();
        loadDocuments();
      } catch (e) {
        showToast(t('uploadFailed') + e.message, 'error');
      } finally {
        uploading.value = false;
      }
    }

    async function deleteDoc(docId) {
      try {
        await CyreneAPI.deleteDocument(selectedCollection.value, docId);
        showToast(t('deleted'), 'success');
        loadDocuments();
      } catch (e) {
        showToast(t('deleteFailed') + e.message, 'error');
      }
    }

    async function deleteCol() {
      if (!selectedCollection.value) return;
      try {
        await CyreneAPI.deleteCollection(selectedCollection.value);
        showToast(t('collectionDeleted'), 'success');
        selectedCollection.value = '';
        documents.value = [];
      } catch (e) {
        showToast(t('deleteFailed') + e.message, 'error');
      }
    }

    async function openEdit(docId) {
      try {
        const doc = await CyreneAPI.getDocument(selectedCollection.value, docId);
        editingDoc.value = doc;
        editingContent.value = doc.content || '';
      } catch (e) {
        showToast(t('loadFailed') + e.message, 'error');
      }
    }

    async function saveEdit() {
      if (!editingDoc.value) return;
      saving.value = true;
      try {
        await CyreneAPI.updateDocument(selectedCollection.value, editingDoc.value.id, editingContent.value);
        showToast(t('saved'), 'success');
        editingDoc.value = null;
        editingContent.value = '';
        loadDocuments();
      } catch (e) {
        showToast(t('saveFailed') + e.message, 'error');
      } finally {
        saving.value = false;
      }
    }

    function closeEdit() {
      editingDoc.value = null;
      editingContent.value = '';
    }

    watch(selectedCollection, loadDocuments);
    onMounted(loadCollections);

    return { Icons, t, collections, selectedCollection, documents, uploading, uploadCollection, fileInput, editingDoc, editingContent, saving, loadCollections, loadDocuments, uploadFile, deleteDoc, deleteCol, openEdit, saveEdit, closeEdit };
  },
  template: `
    <div>
      <!-- Upload section -->
      <div class="card card-gold mb-4">
        <div class="card-header">
          <div class="card-title">{{ t('uploadKnowledge') }}</div>
        </div>
        <div class="card-body">
          <div style="display: flex; gap: var(--space-3); align-items: flex-end;">
            <div class="input-group" style="flex: 1;">
              <label class="input-label">{{ t('collectionName') }}</label>
              <input class="input" v-model="uploadCollection" placeholder="my-knowledge" />
            </div>
            <div class="input-group" style="flex: 1;">
              <label class="input-label">{{ t('chooseFile') }}</label>
              <input type="file" ref="fileInput" class="input" style="padding: 8px;" />
            </div>
            <button class="btn btn-primary" @click="uploadFile" :disabled="uploading" style="white-space: nowrap;">
              {{ uploading ? t('uploading') : t('upload') }}
            </button>
          </div>
        </div>
      </div>

      <!-- Browse section -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">{{ t('browseKnowledge') }}</div>
          <div style="display: flex; gap: var(--space-2); align-items: center;">
            <button class="btn btn-ghost btn-sm" @click="loadCollections">
              <span v-html="Icons.refresh" style="width:14px;height:14px;"></span>
            </button>
            <button class="btn btn-danger btn-sm" v-if="selectedCollection" @click="deleteCol">
              <span v-html="Icons.trash" style="width:14px;height:14px;"></span>
            </button>
          </div>
        </div>
        <div class="card-body">
          <!-- Collection list -->
          <div v-if="collections.length && !selectedCollection" style="display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-4);">
            <div v-for="col in collections" :key="col"
                 class="nav-item" style="cursor: pointer; padding: var(--space-2) var(--space-3);"
                 @click="selectedCollection = col">
              <span class="text-sm">📁 {{ col }}</span>
            </div>
          </div>

          <!-- Selected collection -->
          <div v-if="selectedCollection" style="margin-bottom: var(--space-3);">
            <button class="btn btn-ghost btn-sm" @click="selectedCollection = ''; documents = []">
              {{ t('back') }}
            </button>
            <span class="text-sm text-ash" style="margin-left: var(--space-2);">{{ t('current') }}{{ selectedCollection }}</span>
          </div>

          <template v-if="documents.length">
            <table>
              <thead>
                <tr>
                  <th>{{ t('chunksSource') }}</th>
                  <th>{{ t('chunksCount') }}</th>
                  <th>{{ t('operation') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="doc in documents" :key="doc.id">
                  <td class="text-sm">{{ doc.source || doc.id }}</td>
                  <td class="text-ash text-xs">#{{ doc.metadata?.chunk_index ?? '-' }}</td>
                  <td>
                    <button class="btn btn-ghost btn-sm" @click="openEdit(doc.id)" :title="t('edit')">
                      <span v-html="Icons.edit" style="width:14px;height:14px;"></span>
                    </button>
                    <button class="btn btn-ghost btn-sm" @click="deleteDoc(doc.id)" :title="t('deleteDoc')">
                      <span v-html="Icons.trash" style="width:14px;height:14px;"></span>
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
          <empty-state v-else-if="!selectedCollection && !collections.length"
            :icon="Icons.knowledge"
            :title="t('seedsNotSown')"
            :hint="t('uploadToBuild')" />
          <div v-else-if="selectedCollection && !documents.length" class="text-sm text-ash" style="padding: var(--space-4); text-align: center;">
            {{ t('noDocsInCollection') }}
          </div>
        </div>
      </div>

      <!-- Edit modal -->
      <div v-if="editingDoc" class="modal-overlay" @click.self="closeEdit">
        <div class="modal" style="max-width: 700px;">
          <div class="modal-header">
            <div class="modal-title">{{ t('editChunk') }}</div>
            <button class="btn btn-ghost btn-sm" @click="closeEdit">✕</button>
          </div>
          <div class="modal-body">
            <div class="text-xs text-ash" style="margin-bottom: var(--space-2);">
              {{ t('source') }}：{{ editingDoc.source }} | {{ t('id') }}{{ editingDoc.id }}
            </div>
            <textarea class="input" v-model="editingContent" rows="15"
                      style="width: 100%; font-family: var(--font-mono); font-size: var(--text-sm); resize: vertical;"></textarea>
          </div>
          <div class="modal-footer">
            <button class="btn btn-ghost" @click="closeEdit">{{ t('cancel') }}</button>
            <button class="btn btn-primary" @click="saveEdit" :disabled="saving">
              {{ saving ? t('saving') : t('save') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  `
};

// ── Audit Page ──
const AuditPage = {
  components: { EmptyState },
  setup() {
    const Icons = inject('Icons');
    const t = inject('t');
    const traces = ref([]);
    const stats = ref(null);
    const loading = ref(false);

    async function loadTraces() {
      loading.value = true;
      try {
        const [traceData, statsData] = await Promise.all([
          CyreneAPI.listTraces(50),
          CyreneAPI.getTraceStats(),
        ]);
        traces.value = traceData.traces || traceData || [];
        stats.value = statsData;
      } catch (e) {
        console.error('Failed to load traces:', e);
      } finally {
        loading.value = false;
      }
    }

    async function deleteTrace(traceId) {
      try {
        await CyreneAPI.deleteTrace(traceId);
        showToast(t('deleted'), 'success');
        loadTraces();
      } catch (e) {
        showToast(t('deleteFailed') + e.message, 'error');
      }
    }

    async function cleanupTraces() {
      try {
        const result = await CyreneAPI.cleanupTraces();
        showToast(`${result.deleted || 0} ${t('cleanedNRecords')}`, 'success');
        loadTraces();
      } catch (e) {
        showToast(t('deleteFailed') + e.message, 'error');
      }
    }

    function formatDuration(ms) {
      if (!ms) return '-';
      if (ms < 1000) return ms + 'ms';
      return (ms / 1000).toFixed(1) + 's';
    }

    function formatTime(ts) {
      if (!ts) return '-';
      return new Date(ts).toLocaleString(CyreneI18n.localeString());
    }

    onMounted(loadTraces);

    return { Icons, t, traces, stats, loading, loadTraces, deleteTrace, cleanupTraces, formatDuration, formatTime };
  },
  template: `
    <div>
      <!-- Stats -->
      <div v-if="stats" style="display: flex; gap: var(--space-4); margin-bottom: var(--space-6);">
        <div class="card" style="flex: 1;">
          <div class="card-body" style="text-align: center;">
            <div style="font-family: var(--font-display); font-size: var(--text-2xl); color: var(--rose);">{{ stats.count || 0 }}</div>
            <div class="text-sm text-ash">{{ t('totalRecords') }}</div>
          </div>
        </div>
        <div class="card" style="flex: 1;">
          <div class="card-body" style="text-align: center;">
            <div style="font-family: var(--font-display); font-size: var(--text-2xl); color: var(--gold);">{{ stats.retentionDays || 30 }}</div>
            <div class="text-sm text-ash">{{ t('retentionDays') }}</div>
          </div>
        </div>
      </div>

      <!-- Traces table -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">{{ t('auditRecords') }}</div>
          <div style="display: flex; gap: var(--space-2);">
            <button class="btn btn-ghost btn-sm" @click="loadTraces">
              <span v-html="Icons.refresh" style="width:14px;height:14px;"></span>
            </button>
            <button class="btn btn-danger btn-sm" @click="cleanupTraces">{{ t('cleanupExpired') }}</button>
          </div>
        </div>
        <div class="card-body">
          <template v-if="traces.length">
            <div class="table-container">
              <table>
                <thead>
                  <tr>
                    <th>{{ t('traceId') }}</th>
                    <th>{{ t('user') }}</th>
                    <th>{{ t('risk') }}</th>
                    <th>{{ t('duration') }}</th>
                    <th>{{ t('time') }}</th>
                    <th>{{ t('operation') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="tr in traces" :key="tr.traceId">
                    <td class="text-xs" style="font-family: monospace; color: var(--iris);">{{ (tr.traceId || '').slice(0, 8) }}...</td>
                    <td>{{ tr.userId || '-' }}</td>
                    <td>
                      <span :class="['tag', tr.riskLevel === 'HIGH' ? 'tag-rose' : tr.riskLevel === 'MEDIUM' ? 'tag-gold' : 'tag-iris']">
                        {{ tr.riskLevel || '-' }}
                      </span>
                    </td>
                    <td class="text-ash">{{ formatDuration(tr.totalDurationMs) }}</td>
                    <td class="text-xs text-dusk">{{ formatTime(tr.timestamp) }}</td>
                    <td>
                      <button class="btn btn-ghost btn-sm" @click="deleteTrace(tr.traceId)">
                        <span v-html="Icons.trash" style="width:14px;height:14px;"></span>
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
          <empty-state v-else
            :icon="Icons.audit"
            :title="t('journeyNotStarted')"
            :hint="t('auditHint')" />
        </div>
      </div>
    </div>
  `
};

// ── Config Page ──
const ConfigPage = {
  components: { EmptyState },
  setup() {
    const Icons = inject('Icons');
    const t = inject('t');
    const configText = ref('');
    const configObj = ref(null);
    const loading = ref(false);
    const saving = ref(false);
    const error = ref('');

    async function loadConfig() {
      loading.value = true;
      error.value = '';
      try {
        const data = await CyreneAPI.getConfig();
        // Server returns { path, config } — extract the config object
        const config = data.config || data;
        configObj.value = config;
        configText.value = JSON.stringify(config, null, 2);
      } catch (e) {
        if (e.message.includes('not found') || e.message.includes('404')) {
          configText.value = '{\n  "discoveredAt": "",\n  "sourceRoot": "",\n  "baseUrl": "",\n  "endpoints": []\n}';
        } else {
          error.value = e.message;
        }
      } finally {
        loading.value = false;
      }
    }

    async function saveConfig() {
      saving.value = true;
      error.value = '';
      try {
        const parsed = JSON.parse(configText.value);
        await CyreneAPI.updateConfig(parsed);
        showToast(t('configSaved'), 'success');
        configObj.value = parsed;
      } catch (e) {
        if (e instanceof SyntaxError) {
          error.value = t('jsonError') + e.message;
        } else {
          error.value = e.message;
        }
      } finally {
        saving.value = false;
      }
    }

    async function reloadConfig() {
      try {
        await CyreneAPI.reloadConfig();
        showToast(t('configReloaded'), 'success');
      } catch (e) {
        showToast(t('reloadFailed') + e.message, 'error');
      }
    }

    function handleKeydown(e) {
      // Tab support in textarea
      if (e.key === 'Tab') {
        e.preventDefault();
        const start = e.target.selectionStart;
        const end = e.target.selectionEnd;
        configText.value = configText.value.substring(0, start) + '  ' + configText.value.substring(end);
        nextTick(() => {
          e.target.selectionStart = e.target.selectionEnd = start + 2;
        });
      }
    }

    onMounted(loadConfig);

    return { Icons, t, configText, configObj, loading, saving, error, loadConfig, saveConfig, reloadConfig, handleKeydown };
  },
  template: `
    <div>
      <!-- Config editor -->
      <div class="card card-gold">
        <div class="card-header">
          <div class="card-title">project-apis.json</div>
          <div style="display: flex; gap: var(--space-2);">
            <button class="btn btn-ghost btn-sm" @click="loadConfig">
              <span v-html="Icons.refresh" style="width:14px;height:14px;"></span>
              {{ t('reload') }}
            </button>
            <button class="btn btn-ghost btn-sm" @click="reloadConfig">
              {{ t('hotReload') }}
            </button>
            <button class="btn btn-primary btn-sm" @click="saveConfig" :disabled="saving">
              <span v-html="Icons.save" style="width:14px;height:14px;"></span>
              {{ saving ? t('saving') : t('save') }}
            </button>
          </div>
        </div>
        <div class="card-body">
          <div v-if="loading" style="text-align: center; padding: 2rem;">
            <div class="loading-dots"><span></span><span></span><span></span></div>
          </div>
          <template v-else>
            <textarea class="config-editor" v-model="configText"
                      @keydown="handleKeydown"
                      spellcheck="false"></textarea>
            <div v-if="error" class="text-sm mt-2" style="color: var(--error);">{{ error }}</div>
            <div class="text-xs text-ash mt-2">
              {{ t('configHint') }}
            </div>
          </template>
        </div>
      </div>

      <!-- Endpoint summary -->
      <div class="card mt-4" v-if="configObj?.endpoints?.length">
        <div class="card-header">
          <div class="card-title">{{ t('configuredEndpoints') }} ({{ configObj.endpoints.length }})</div>
        </div>
        <div class="card-body">
          <table>
            <thead>
              <tr>
                <th>{{ t('method') }}</th>
                <th>{{ t('path') }}</th>
                <th>{{ t('name') }}</th>
                <th>{{ t('auth') }}</th>
                <th>{{ t('status') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="ep in configObj.endpoints" :key="ep.id">
                <td><span class="tag tag-iris" style="font-size: 11px;">{{ ep.method }}</span></td>
                <td class="text-sm" style="font-family: monospace;">{{ ep.path }}</td>
                <td>{{ ep.name }}</td>
                <td class="text-xs text-ash">{{ ep.authMode || '-' }}</td>
                <td>
                  <span :class="['tag', ep.confirmed ? 'tag-gold' : 'tag-dusk']">
                    {{ ep.confirmed ? t('enabled') : t('disabled') }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <empty-state v-if="!loading && !configObj?.endpoints?.length"
        :icon="Icons.config"
        :title="t('waitingForYou')"
        :hint="t('scanOrCreateHint')" />
    </div>
  `
};

// ── Main App ──
const app = createApp({
  components: { ToastContainer, StarsBackground, PreConfigModal, ChatPage, KnowledgePage, AuditPage, ConfigPage },
  setup() {
    const Icons = inject('Icons');
    const currentPage = ref('chat');
    const sidebarOpen = ref(false);
    const sidebarCollapsed = ref(false);
    const showPreConfig = ref(false);
    const configExists = ref(true);

    // ── Global userId (persisted in localStorage) ──
    const userId = ref(localStorage.getItem('cyrene_user') || '');
    const showWelcome = ref(!userId.value);
    const editingUser = ref(false);
    const editUserId = ref('');

    provide('userId', userId);
    const locale = CyreneI18n.init(ref, watch);
    const t = CyreneI18n.t.bind(CyreneI18n);
    provide('t', t);
    provide('locale', locale);

    function confirmUserId() {
      const val = editUserId.value.trim();
      if (!val) return;
      userId.value = val;
      localStorage.setItem('cyrene_user', val);
      showWelcome.value = false;
      editingUser.value = false;
      showToast(t('userIdSet') + val, 'success');
    }

    function startEditUser() {
      editUserId.value = userId.value;
      editingUser.value = true;
    }

    function cancelEditUser() {
      editingUser.value = false;
    }

    const navItems = computed(() => [
      { id: 'chat', label: t('chat'), icon: Icons.chat },
      { id: 'knowledge', label: t('knowledge'), icon: Icons.knowledge },
      { id: 'audit', label: t('audit'), icon: Icons.audit },
      { id: 'config', label: t('config'), icon: Icons.config },
    ]);

    const pageTitle = computed(() => {
      const item = navItems.value.find(n => n.id === currentPage.value);
      return item ? item.label : 'Cyrene';
    });

    function navigate(page) {
      if (page === currentPage.value) return;
      currentPage.value = page;
      window.location.hash = page;
    }

    function toggleSidebar() {
      sidebarOpen.value = !sidebarOpen.value;
    }

    async function checkConfig() {
      try {
        await CyreneAPI.getConfig();
        configExists.value = true;
      } catch (e) {
        configExists.value = false;
        showPreConfig.value = true;
      }
    }

    function onPreConfigComplete() {
      showPreConfig.value = false;
      configExists.value = true;
    }

    function onPreConfigClose() {
      showPreConfig.value = false;
    }

    // Handle hash routing
    function handleHash() {
      const hash = window.location.hash.slice(1) || 'chat';
      if (navItems.value.find(n => n.id === hash)) {
        currentPage.value = hash;
      }
    }

    onMounted(() => {
      handleHash();
      window.addEventListener('hashchange', handleHash);
      checkConfig();
    });

    onUnmounted(() => {
      window.removeEventListener('hashchange', handleHash);
    });

    return {
      Icons, currentPage, sidebarOpen, sidebarCollapsed, showPreConfig, configExists,
      navItems, pageTitle, t, locale,
      userId, showWelcome, editingUser, editUserId,
      confirmUserId, startEditUser, cancelEditUser,
      navigate, toggleSidebar, onPreConfigComplete, onPreConfigClose,
    };
  },
  template: `
    <div>
      <stars-background />
      <toast-container />

      <!-- Welcome modal (first visit only) -->
      <div v-if="showWelcome" class="modal-overlay">
        <div class="modal" style="max-width: 420px;">
          <div class="modal-header">
            <div class="modal-title">{{ t('welcomeTitle') }}</div>
            <div class="modal-subtitle">{{ t('welcomeSubtitle') }}</div>
          </div>
          <div class="modal-body">
            <div class="input-group">
              <label class="input-label">{{ t('userId') }}</label>
              <input class="input" v-model="editUserId"
                     :placeholder="t('enterUserId')"
                     @keydown.enter="confirmUserId" autofocus />
              <div class="text-xs text-ash mt-2">
                {{ t('userIdHint') }}
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-primary" @click="confirmUserId" :disabled="!editUserId.trim()">
              {{ t('enterCyrene') }}
            </button>
          </div>
        </div>
      </div>

      <!-- Pre-config modal -->
      <pre-config-modal :visible="showPreConfig" @complete="onPreConfigComplete" @close="onPreConfigClose" />

      <div style="display: flex; height: 100vh; position: relative; z-index: 1;">
        <!-- Mobile header -->
        <div class="mobile-header" style="position: fixed; top: 0; left: 0; right: 0; z-index: 110;">
          <button class="btn btn-ghost" @click="toggleSidebar">
            <span v-html="Icons.menu" style="width:20px;height:20px;"></span>
          </button>
          <span style="font-family: var(--font-display); font-weight: 600;">Cyrene</span>
        </div>

        <!-- Sidebar backdrop (mobile) -->
        <div :class="['sidebar-backdrop', sidebarOpen ? '' : 'hidden']" @click="sidebarOpen = false"></div>

        <!-- Sidebar -->
        <aside :class="['sidebar', sidebarOpen ? 'open' : '', sidebarCollapsed ? 'collapsed' : '']">
          <div class="sidebar-header">
            <div class="sidebar-logo">
              <span v-show="!sidebarCollapsed">Cyrene</span>
              <span v-show="!sidebarCollapsed" class="logo-accent">♪</span>
              <button class="sidebar-toggle" @click="sidebarCollapsed = !sidebarCollapsed" :title="sidebarCollapsed ? t('expandSidebar') : t('collapseSidebar')">
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <path :d="sidebarCollapsed ? 'M6 3l5 5-5 5' : 'M10 3l-5 5 5 5'" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
            </div>
          </div>
          <nav class="sidebar-nav">
            <button v-for="item in navItems" :key="item.id"
                    :class="['nav-item', currentPage === item.id ? 'active' : '']"
                    :title="sidebarCollapsed ? item.label : ''"
                    @click="navigate(item.id); sidebarOpen = false">
              <span class="nav-icon" v-html="item.icon"></span>
              <span class="nav-label" v-show="!sidebarCollapsed">{{ item.label }}</span>
            </button>
          </nav>
          <div class="sidebar-footer" v-show="!sidebarCollapsed">
            {{ t('inTimeRipples') }}
          </div>
        </aside>

        <!-- Main content -->
        <main class="main-content">
          <header class="header">
            <h2 class="header-title">{{ pageTitle }}</h2>
            <div class="header-actions">
              <!-- Language toggle -->
              <button class="lang-toggle" @click="locale = locale === 'zh' ? 'en' : 'zh'" :title="locale === 'zh' ? 'Switch to English' : '切换到中文'">
                {{ locale === 'zh' ? 'EN' : '中' }}
              </button>
              <!-- User ID badge (click to edit) -->
              <div class="user-badge" @click="startEditUser" style="cursor: pointer;" :title="t('clickToEditUserId')">
                <span class="user-dot"></span>
                <span>{{ userId || t('unset') }}</span>
              </div>
            </div>
          </header>

          <!-- Inline user ID editor -->
          <div v-if="editingUser" style="padding: 0 var(--space-6); background: var(--surface); border-bottom: 1px solid var(--gold-line);">
            <div style="display: flex; gap: var(--space-3); align-items: center; padding: var(--space-3) 0;">
              <input class="input" v-model="editUserId" :placeholder="t('userId')"
                     style="flex: 1; max-width: 300px;"
                     @keydown.enter="confirmUserId" @keydown.escape="cancelEditUser" />
              <button class="btn btn-primary btn-sm" @click="confirmUserId" :disabled="!editUserId.trim()">{{ t('confirm') }}</button>
              <button class="btn btn-ghost btn-sm" @click="cancelEditUser">{{ t('cancel') }}</button>
            </div>
          </div>

          <div class="page-container">
            <keep-alive>
              <component :is="currentPage + '-page'" />
            </keep-alive>
          </div>
        </main>
      </div>
    </div>
  `
});

// 全局涟漪 — 界面任意位置点击都有
document.addEventListener('click', (e) => {
  createRipple(e);
});

// Randomize meteor start position for each loading-dots element
app.directive('meteor', {
  mounted(el) {
    const sy = Math.floor(Math.random() * 16) + 2;   // 2–17px
    const ey = Math.floor(Math.random() * 4);          // 0–3px
    el.style.setProperty('--meteor-sy', sy);
    el.style.setProperty('--meteor-ey', ey);
  }
});

// Register Icons as global property so all components can access it
app.config.globalProperties.Icons = Icons;
app.provide('Icons', Icons);
app.mount('#app');
