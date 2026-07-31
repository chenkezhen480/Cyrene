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

// Strip artifact markdown links from text to prevent double-rendering
// when both TEXT and ARTIFACT blocks are present
const ARTIFACT_LINK_RE = /!\[.*?\]\(\/api\/artifacts\/[^)]+\)/g;
const CRYSTAL_SVG = '<svg width="15" height="15" viewBox="0 0 16 16" fill="none" style="vertical-align:-2px;margin-right:3px"><defs><radialGradient id="cg"><stop offset="0%" stop-color="rgba(232,160,191,0.6)"/><stop offset="100%" stop-color="rgba(139,126,200,0.15)"/></radialGradient></defs><path d="M8 0.5L9.5 5 14 3.5 11 7.5 15.5 8 11 8.5 14 12.5 9.5 11 8 15.5 6.5 11 2 12.5 5 8.5 0.5 8 5 7.5 2 3.5 6.5 5z" fill="url(#cg)" stroke="var(--iris)" stroke-width="0.5" stroke-linejoin="round"/><circle cx="8" cy="8" r="1.8" fill="rgba(232,160,191,0.7)"/><circle cx="8" cy="8" r="0.8" fill="white" opacity="0.6"/></svg>';
function stripArtifactLinks(text) {
  if (!text) return '';
  return text.replace(ARTIFACT_LINK_RE, '').trim();
}

// ── SVG Icons ──
const Icons = {
  chat: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`,
  knowledge: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>`,
  graph: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2.5"/><circle cx="18" cy="7" r="2.5"/><circle cx="12" cy="18" r="2.5"/><path d="M8.4 6.2l7.1.5M7.4 8.1l3.4 7.6M16.7 9.1l-3.5 6.7"/></svg>`,
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
  tool: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(45,12,12)"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(90,12,12)"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(135,12,12)"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(180,12,12)"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(225,12,12)"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(270,12,12)"/><path d="M12 12 C9.5 9 7.5 5.5 12 2 C16.5 5.5 14.5 9 12 12" fill="currentColor" opacity="0.15" stroke-width="0.8" transform="rotate(315,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(22.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(67.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(112.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(157.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(202.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(247.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(292.5,12,12)"/><path d="M12 12 C10.5 10 9 7.5 12 5 C15 7.5 13.5 10 12 12" fill="currentColor" opacity="0.08" stroke-width="0.5" transform="rotate(337.5,12,12)"/><circle cx="12" cy="12" r="2" fill="currentColor" opacity="0.2"/><circle cx="12" cy="12" r="0.8" fill="currentColor" opacity="0.45"/></svg>`,
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
        // Pass full config (discoveredAt, projectDescription, baseUrl, projectRoot, endpoints)
        const config = {
          discoveredAt: scanResult.value.discoveredAt || new Date().toISOString(),
          projectDescription: scanResult.value.projectDescription || '',
          projectRoot: scanResult.value.projectRoot || sourceRoot.value.trim(),
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
    const attachedFiles = ref([]);
    const chatFileInput = ref(null);
    const pendingConfirmation = ref(null);
    const confirmationAcknowledged = ref(false);
    const confirmationSubmitting = ref(false);

    // Known file extensions for URL auto-detection on paste (office docs, images, video, audio)
    const FILE_URL_REGEX = /https?:\/\/[^\s<>"'`]+?\.(?:pdf|docx?|xlsx?|pptx?|csv|json|rtf|odt|ods|txt|md|png|jpe?g|gif|webp|svg|bmp|tiff?|mp[34]|wav|ogg|webm|avi|mov|mkv|flv|wmv)(?:\?[^\s]*)?/gi;

    function triggerFileUpload() {
      chatFileInput.value?.click();
    }

    async function handleFileSelect(e) {
      const files = Array.from(e.target.files || []);
      for (const file of files) {
        if (attachedFiles.value.some(f => f.name === file.name && f.size === file.size)) continue;
        // 添加到列表，状态为上传中
        const item = reactive({ file, url: null, uploading: true, error: null });
        attachedFiles.value.push(item);
        // 立即上传
        try {
          const result = await CyreneAPI.uploadFile(file);
          item.url = result.url;
          item.uploading = false;
          console.log('[Chat] File uploaded:', result.url);
        } catch (err) {
          item.uploading = false;
          item.error = err.message;
          console.error('[Chat] File upload failed:', err);
          showToast(t('uploadFailed') + err.message, 'error');
        }
      }
      e.target.value = '';
    }

    async function handlePaste(e) {
      const items = Array.from(e.clipboardData?.items || []);
      for (const item of items) {
        if (item.type.startsWith('image/')) {
          e.preventDefault(); // 阻止默认粘贴行为
          const file = item.getAsFile();
          if (!file) continue;

          // 生成一个有意义的文件名
          const ext = file.type.split('/')[1] || 'png';
          const fileName = `pasted-${Date.now()}.${ext}`;
          const renamedFile = new File([file], fileName, { type: file.type });

          // 添加到列表并上传
          const itemObj = reactive({ file: renamedFile, url: null, uploading: true, error: null });
          attachedFiles.value.push(itemObj);
          try {
            const result = await CyreneAPI.uploadFile(renamedFile);
            itemObj.url = result.url;
            itemObj.uploading = false;
            console.log('[Chat] Pasted image uploaded:', result.url);
          } catch (err) {
            itemObj.uploading = false;
            itemObj.error = err.message;
            console.error('[Chat] Pasted image upload failed:', err);
            showToast(t('uploadFailed') + err.message, 'error');
          }
          break; // 只处理第一个图片
        }
      }

      // Auto-detect file URLs in pasted text → download → upload to server → get relative path
      const pastedText = e.clipboardData?.getData('text/plain') || '';
      if (pastedText) {
        FILE_URL_REGEX.lastIndex = 0;
        const matches = pastedText.match(FILE_URL_REGEX);
        if (matches && matches.length > 0) {
          e.preventDefault();
          for (const url of matches) {
            if (attachedFiles.value.some(f => f.uploading && f.file.name === url)) continue;
            const urlPath = new URL(url).pathname;
            const name = urlPath.substring(urlPath.lastIndexOf('/') + 1) || 'download';
            // Add placeholder with uploading state
            const itemObj = reactive({ file: { name, size: 0 }, url: null, uploading: true, error: null });
            attachedFiles.value.push(itemObj);
            console.log('[Chat] Detected file URL, downloading:', url);
            // Download from URL → upload to server → get relative path
            try {
              const resp = await fetch(url);
              if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
              const blob = await resp.blob();
              const file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
              const result = await CyreneAPI.uploadFile(file);
              itemObj.file = file;
              itemObj.url = result.url;
              itemObj.uploading = false;
              console.log('[Chat] URL file uploaded to server:', result.url);
            } catch (err) {
              itemObj.uploading = false;
              itemObj.error = err.message;
              console.error('[Chat] URL file download/upload failed:', err);
              showToast(t('uploadFailed') + err.message, 'error');
            }
          }
        }
      }
    }

    function removeFile(index) {
      attachedFiles.value.splice(index, 1);
    }

    async function loadSessions() {
      if (!userId.value) return;
      try {
        const page = requirePageResponse(
          await CyreneAPI.listSessions(userId.value, { limit: 20 }),
          session => typeof session?.id === 'string',
          t('invalidSessionPageResponse')
        );
        sessions.value = page.items;
      } catch (e) {
        console.error('Failed to load sessions:', e);
      }
    }

    async function selectSession(sid) {
      currentSessionId.value = sid;
      try {
        const page = requirePageResponse(
          await CyreneAPI.getMessages(sid, { limit: 50, direction: 'asc' }),
          message => typeof message?.id === 'number'
            && typeof message?.role === 'string',
          t('invalidMessagePageResponse')
        );
        messages.value = page.items;
        scrollToBottom();
      } catch (e) {
        console.error('Failed to load messages:', e);
      }
    }

    async function newSession() {
      currentSessionId.value = null;
      messages.value = [];
    }

    async function deleteSession(sid) {
      if (!confirm(t('deleteSessionConfirm'))) return;
      try {
        await CyreneAPI.closeSession(sid);
        showToast(t('sessionDeleted'), 'success');
        if (currentSessionId.value === sid) {
          currentSessionId.value = null;
          messages.value = [];
        }
        loadSessions();
      } catch (e) {
        showToast(t('deleteFailed') + e.message, 'error');
      }
    }

    async function cancelOutput() {
      if (!currentSessionId.value) return;
      try {
        await CyreneAPI.cancelChat(currentSessionId.value);
      } catch (e) {
        // Ignore — stream may have already ended
      }
    }

    async function approvePendingConfirmation() {
      const pending = pendingConfirmation.value;
      if (!pending || !confirmationAcknowledged.value || confirmationSubmitting.value) return;
      confirmationSubmitting.value = true;
      try {
        await CyreneAPI.approveConfirmation(
          pending.requestId, userId.value, currentSessionId.value);
      } catch (e) {
        showToast(e.message, 'error');
      } finally {
        confirmationSubmitting.value = false;
      }
    }

    async function rejectPendingConfirmation() {
      const pending = pendingConfirmation.value;
      if (!pending || confirmationSubmitting.value) return;
      confirmationSubmitting.value = true;
      try {
        await CyreneAPI.rejectConfirmation(
          pending.requestId, userId.value, currentSessionId.value);
      } catch (e) {
        showToast(e.message, 'error');
      } finally {
        confirmationSubmitting.value = false;
      }
    }

    function formatConfirmationArguments(argumentsValue) {
      try {
        return JSON.stringify(argumentsValue || {}, null, 2);
      } catch (e) {
        return String(argumentsValue || '');
      }
    }

    async function sendMessage() {
      const text = inputText.value.trim();
      if ((!text && attachedFiles.value.length === 0) || isStreaming.value) return;

      if (!userId.value) {
        showToast(t('setUserIdFirst'), 'error');
        return;
      }

      // 获取已上传的文件 URL
      const files = [...attachedFiles.value];
      attachedFiles.value = [];

      // 检查是否有上传失败的文件
      const failedFiles = files.filter(f => f.error);
      if (failedFiles.length > 0) {
        showToast(t('uploadFailed') + failedFiles.map(f => f.name).join(', '), 'error');
        return;
      }

      // 检查是否有还在上传中的文件
      const uploadingFiles = files.filter(f => f.uploading);
      if (uploadingFiles.length > 0) {
        showToast('文件正在上传中，请稍候...', 'error');
        // 把文件放回去
        attachedFiles.value = files;
        return;
      }

      // 获取已上传的文件相对路径
      const fileUrls = files.filter(f => f.url).map(f => ({ url: f.url, name: f.file.name }));

      // Add user message to UI (with file indicator)
      let displayContent = text;
      if (files.length > 0) {
        const fileList = files.map(f => `📎 ${f.file.name}`).join('\n');
        displayContent = text ? `${fileList}\n\n${text}` : fileList;
      }
      messages.value.push({ role: 'user', content: displayContent });
      inputText.value = '';
      scrollToBottom();

      isStreaming.value = true;
      messages.value.push({ role: 'assistant', content: '', toolCalls: [] });
      const msgIdx = messages.value.length - 1;

      try {
        // Build context with file URLs
        const context = {
          userId: userId.value,
          outputMode: 'streaming',
        };
        // If there are uploaded files, add them to context.File (backend will resolve and extract)
        if (fileUrls.length > 0) {
          context.File = fileUrls.length === 1 ? fileUrls[0].url : fileUrls.map(f => f.url);
        }

        const resp = await CyreneAPI.chat(currentSessionId.value, text, context);

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
                    // Remove thinking placeholder on first token (text response, not tool call)
                    if (typeof messages.value[msgIdx].content === 'string' && messages.value[msgIdx].content.includes('thinking-placeholder')) {
                      messages.value[msgIdx].content = '';
                    }
                    if (parsed.text) messages.value[msgIdx].content += parsed.text;
                    break;
                  case 'tool_call_created':
                    messages.value[msgIdx].toolCalls.push({ name: parsed.toolName, status: 'queued' });
                    break;
                  case 'tool_call_start':
                    {
                      const tc = messages.value[msgIdx].toolCalls;
                      const queued = tc.findLast(t => t.name === parsed.toolName && t.status === 'queued');
                      if (queued) queued.status = 'running';
                    }
                    break;
                  case 'tool_call_done':
                    {
                      const tc = messages.value[msgIdx].toolCalls;
                      const last = tc.findLast(t => t.name === parsed.toolName
                        && (t.status === 'pending' || t.status === 'running'
                          || t.status === 'queued' || t.status === 'awaiting_approval'));
                      if (last) last.status = parsed.success ? 'success' : 'error';
                    }
                    break;
                  case 'confirmation_required':
                    pendingConfirmation.value = parsed;
                    confirmationAcknowledged.value = false;
                    {
                      const tc = messages.value[msgIdx].toolCalls;
                      const waiting = tc.findLast(t => t.name === parsed.toolName
                        && (t.status === 'queued' || t.status === 'running'));
                      if (waiting) waiting.status = 'awaiting_approval';
                    }
                    break;
                  case 'confirmation_resolved':
                    {
                      const tc = messages.value[msgIdx].toolCalls;
                      const waiting = tc.findLast(t => t.name === parsed.toolName
                        && t.status === 'awaiting_approval');
                      if (waiting) {
                        waiting.status = parsed.decision === 'APPROVED' ? 'queued' : 'error';
                      }
                      if (pendingConfirmation.value?.requestId === parsed.requestId) {
                        pendingConfirmation.value = null;
                        confirmationAcknowledged.value = false;
                      }
                    }
                    break;
                  case 'compress':
                    const modeLabel = parsed.mode === 'major' ? t('majorCompress') : t('minorCompress');
                    const compressHtml = `\n\n<div class="compress-block"><span class="compress-icon">🗜️</span> <span class="compress-label">${modeLabel}</span> <span class="compress-detail">${parsed.detail || ''}</span></div>\n\n`;
                    messages.value[msgIdx].content += compressHtml;
                    break;
                  case 'artifact':
                    {
                      // Remove inline LOADING placeholder
                      messages.value[msgIdx].content = messages.value[msgIdx].content.replace(
                        /\n?<div class="artifact-loading-canvas"[\s\S]*?<\/div>\n?/, ''
                      );
                      // Embed artifact inline into message content
                      if (parsed.type === 'IMAGE') {
                        const url = getArtifactPreviewUrl(parsed.id || parsed.artifactId);
                        messages.value[msgIdx].content += `\n\n![${parsed.name || 'image'}](${url})\n\n`;
                      } else if (parsed.type === 'VIDEO') {
                        const url = getArtifactPreviewUrl(parsed.id || parsed.artifactId);
                        messages.value[msgIdx].content += `\n\n<video controls src="${url}" style="max-width:100%;border-radius:8px"></video>\n\n`;
                      } else {
                        const url = getArtifactUrl(parsed.id || parsed.artifactId);
                        messages.value[msgIdx].content += `\n\n📎 [${parsed.name || 'file'}](${url})\n\n`;
                      }
                    }
                    break;
                  case 'done':
                    // Don't replace streamed content — tool call blocks + tokens already in place
                    if (parsed.sessionId) {
                      currentSessionId.value = parsed.sessionId;
                    }
                    break;
                  case 'cancelled':
                    // Keep streamed content as-is (tool call blocks + tokens already in place)
                    break;
                  case 'error':
                    messages.value[msgIdx].content = `⚠️ Error: ${parsed.error || t('unknownError')}`;
                    showToast(parsed.error || t('requestFailed'), 'error');
                    break;
                  default:
                    if (parsed.text) messages.value[msgIdx].content += parsed.text;
                    else if (parsed.output != null) messages.value[msgIdx].content = [{ type: 'TEXT', text: parsed.output }];
                }
              } catch (e) {
                if (typeof messages.value[msgIdx].content === 'string') {
                  messages.value[msgIdx].content += data;
                }
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
        messages.value[msgIdx].content = `⚠️ Error: ${e.message}`;
        pendingConfirmation.value = null;
        confirmationAcknowledged.value = false;
        showToast(e.message, 'error');
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

    // Artifact helpers
    function getArtifactUrl(id) { return CyreneAPI.getArtifactUrl(id); }
    function getArtifactPreviewUrl(id) { return CyreneAPI.getArtifactPreviewUrl(id); }
    function formatSize(bytes) {
      if (!bytes || bytes === 0) return '0 B';
      const units = ['B', 'KB', 'MB', 'GB'];
      const i = Math.floor(Math.log(bytes) / Math.log(1024));
      return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
    }

    return {
      Icons, t, sessions, currentSessionId, messages, inputText, isStreaming,
      messagesEl, userId, renderMarkdown, stripArtifactLinks,
      pendingConfirmation, confirmationAcknowledged, confirmationSubmitting,
      attachedFiles, chatFileInput, triggerFileUpload, handleFileSelect, handlePaste, removeFile,
      loadSessions, selectSession, newSession, sendMessage,
      deleteSession, cancelOutput, handleKeydown,
      approvePendingConfirmation, rejectPendingConfirmation, formatConfirmationArguments,
      getArtifactUrl, getArtifactPreviewUrl, formatSize,
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
                 :class="['session-item', currentSessionId === s.id ? 'active' : '']"
                 @click="selectSession(s.id)">
              <span class="session-title truncate">{{ s.title || s.id || t('unnamedChat') }}</span>
              <button class="session-delete-btn" @click.stop="deleteSession(s.id)" :title="t('deleteSession')">
                <span v-html="Icons.trash" style="width:12px;height:12px;"></span>
              </button>
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
                   :data-msg-idx="i"
                   :class="['message', msg.role === 'user' ? 'message-user' : 'message-assistant']">
                <div class="message-avatar">
                  {{ msg.role === 'user' ? 'U' : 'C' }}
                </div>
                <!-- User message: single block -->
                <div v-if="msg.role === 'user'" class="message-content">{{ typeof msg.content === 'string' ? msg.content : (msg.content && msg.content[0] && msg.content[0].text || '') }}</div>
                <!-- Assistant message: single merged bubble -->
                <div v-else class="message-content">
                  <!-- Tool calls inside the bubble -->
                  <div v-if="msg.toolCalls && msg.toolCalls.length" class="tool-calls-section">
                    <div v-for="(tc, ti) in msg.toolCalls" :key="ti"
                         :class="['tool-call-block',
                           tc.status === 'queued' || tc.status === 'running' ? 'tool-call-running' : '',
                           tc.status === 'awaiting_approval' ? 'tool-call-awaiting' : '']">
                      <div class="tool-call-header">
                        <span class="tool-call-name"><span v-html="Icons.tool" class="tool-call-icon"></span>{{ tc.name }}</span>
                        <span v-if="tc.status === 'queued'" class="tool-call-status tool-call-pending">⏳</span>
                        <span v-else-if="tc.status === 'running'" class="tool-call-status tool-call-pending">⏳</span>
                        <span v-else-if="tc.status === 'awaiting_approval'"
                              class="tool-call-status tool-call-awaiting-status">!</span>
                        <span v-else-if="tc.status === 'success'" class="tool-call-status tool-call-success">✅</span>
                        <span v-else class="tool-call-status tool-call-error">❌</span>
                      </div>
                    </div>
                  </div>
                  <!-- Text / artifact content -->
                  <div v-if="Array.isArray(msg.content) && msg.content.length" class="md-body">
                    <template v-for="(block, bi) in msg.content" :key="bi">
                      <span v-if="block.type === 'TEXT'" v-html="renderMarkdown(stripArtifactLinks(block.text))"></span>
                      <span v-else-if="block.type === 'ARTIFACT'">
                        <img v-if="(block.metadata && block.metadata.type === 'IMAGE') || (!block.metadata?.type && block.metadata?.mimeType && block.metadata.mimeType.startsWith('image/'))"
                             :src="getArtifactPreviewUrl(block.artifactId)"
                             :alt="(block.metadata && block.metadata.name) || 'image'"
                             style="max-width:100%;border-radius:8px;margin:8px 0;" />
                        <video v-else-if="(block.metadata && block.metadata.type === 'VIDEO') || (!block.metadata?.type && block.metadata?.mimeType && block.metadata.mimeType.startsWith('video/'))"
                               controls :src="getArtifactPreviewUrl(block.artifactId)"
                               style="max-width:100%;border-radius:8px;margin:8px 0;"></video>
                        <a v-else :href="getArtifactUrl(block.artifactId)">📎 {{ (block.metadata && block.metadata.name) || 'file' }}</a>
                      </span>
                    </template>
                  </div>
                  <div v-else-if="typeof msg.content === 'string' && msg.content" class="md-body" v-html="renderMarkdown(msg.content)"></div>
                  <div v-else class="loading-dots" v-meteor><span></span><span></span><span></span></div>
                </div>
              </div>
            </template>
            <empty-state v-else
              :icon="Icons.chat"
              :title="t('chatEmptyTitle')"
              :hint="t('chatEmptyHint')" />
          </div>

          <!-- Input area -->
          <div class="chat-input-area">
            <!-- Attached files -->
            <div v-if="attachedFiles.length > 0" class="attached-files">
              <div v-for="(item, idx) in attachedFiles" :key="idx" class="attached-file-chip">
                <span class="attached-file-icon">
                  <span v-if="item.uploading" class="loading-dots" style="display:inline-flex;"><span></span><span></span><span></span></span>
                  <span v-else-if="item.error" style="color: var(--error);">❌</span>
                  <span v-else>✅</span>
                </span>
                <span class="attached-file-name">{{ item.file.name }}</span>
                <span class="attached-file-size">{{ formatSize(item.file.size) }}</span>
                <button class="attached-file-remove" @click="removeFile(idx)">×</button>
              </div>
            </div>
            <input type="file" ref="chatFileInput" multiple style="display:none"
                   @change="handleFileSelect" />
            <div class="chat-input-wrapper">
              <textarea class="chat-input" v-model="inputText"
                        :placeholder="t('chatPlaceholder')"
                        @keydown="handleKeydown"
                        @paste="handlePaste"
                        rows="1"></textarea>
              <div class="chat-actions">
                <button class="chat-action-btn" :title="t('uploadFile')" @click="triggerFileUpload">
                  <span v-html="Icons.upload" style="width:18px;height:18px;"></span>
                </button>
                <button class="chat-action-btn" :title="t('voiceInput')">
                  <span v-html="Icons.mic" style="width:18px;height:18px;"></span>
                </button>
                <button v-if="isStreaming" class="chat-cancel-btn" @click="cancelOutput" :title="t('cancelOutput')">
                  <svg viewBox="0 0 24 24" fill="currentColor" style="width:16px;height:16px;">
                    <rect x="6" y="6" width="12" height="12" rx="2"/>
                  </svg>
                </button>
                <button v-else class="chat-send-btn" @click="sendMessage"
                        :disabled="!inputText.trim() && attachedFiles.length === 0" :title="t('send')">
                  <span v-html="Icons.send" style="width:16px;height:16px;"></span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="pendingConfirmation" class="modal-overlay">
        <form class="modal confirmation-modal" @submit.prevent="approvePendingConfirmation">
          <div class="modal-header">
            <div class="modal-title">{{ t('confirmationTitle') }}</div>
            <div class="modal-subtitle">{{ t('confirmationSubtitle') }}</div>
          </div>
          <div class="modal-body">
            <div class="confirmation-risk-row">
              <span class="tag tag-error">{{ pendingConfirmation.riskLevel }}</span>
              <span class="confirmation-tool-name">{{ pendingConfirmation.toolName }}</span>
            </div>
            <p class="confirmation-summary">
              {{ pendingConfirmation.summary || t('confirmationDefaultSummary') }}
            </p>
            <label class="input-label">{{ t('confirmationArguments') }}</label>
            <pre class="confirmation-arguments">{{ formatConfirmationArguments(pendingConfirmation.arguments) }}</pre>
            <div class="confirmation-expiry">
              {{ t('confirmationExpiresAt') }} {{ pendingConfirmation.expiresAt }}
            </div>
            <label class="confirmation-acknowledgement">
              <input type="checkbox" v-model="confirmationAcknowledged" />
              <span>{{ t('confirmationAcknowledgement') }}</span>
            </label>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-secondary"
                    :disabled="confirmationSubmitting"
                    @click="rejectPendingConfirmation">
              {{ t('rejectOperation') }}
            </button>
            <button type="submit" class="btn btn-primary"
                    :disabled="!confirmationAcknowledged || confirmationSubmitting">
              {{ confirmationSubmitting ? t('submittingConfirmation') : t('approveOperation') }}
            </button>
          </div>
        </form>
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
        collections.value = requireArrayField(
          data,
          'collections',
          item => typeof item === 'string',
          t('invalidCollectionListResponse')
        );
      } catch (e) {
        collections.value = [];
        showToast(t('loadFailed') + e.message, 'error');
      }
    }

    async function loadDocuments() {
      if (!selectedCollection.value) return;
      try {
        const data = await CyreneAPI.listKnowledge(selectedCollection.value);
        documents.value = requireArrayField(
          data,
          'documents',
          item => item && typeof item === 'object',
          t('invalidKnowledgeListResponse')
        );
      } catch (e) {
        documents.value = [];
        showToast(t('loadFailed') + e.message, 'error');
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

function requirePageResponse(page, itemValidator, errorMessage) {
  const pageInfo = page?.pageInfo;
  const valid = Array.isArray(page?.items)
    && page.items.every(itemValidator)
    && Number.isInteger(pageInfo?.limit)
    && pageInfo.limit > 0
    && typeof pageInfo?.nextCursor === 'string'
    && typeof pageInfo?.hasMore === 'boolean'
    && (!pageInfo.hasMore || pageInfo.nextCursor.length > 0);
  if (!valid) throw new Error(errorMessage);
  return page;
}

function requireArrayResponse(value, itemValidator, errorMessage) {
  if (!Array.isArray(value) || !value.every(itemValidator)) {
    throw new Error(errorMessage);
  }
  return value;
}

function requireArrayField(response, fieldName, itemValidator, errorMessage) {
  if (!response || typeof response !== 'object') {
    throw new Error(errorMessage);
  }
  return requireArrayResponse(response[fieldName], itemValidator, errorMessage);
}

function requireGraphNodePage(page, t) {
  return requirePageResponse(
    page,
    node => typeof node?.nodeId === 'string' && Array.isArray(node.labels),
    t('graphInvalidNodePageResponse')
  );
}

function requireGraphRelationPage(page, t) {
  return requirePageResponse(
    page,
    relation =>
      typeof relation?.relationId === 'string'
      && typeof relation.sourceNodeId === 'string'
      && typeof relation.targetNodeId === 'string'
      && typeof relation.relationType === 'string',
    t('graphInvalidRelationPageResponse')
  );
}

// ── Graph Page ──
const GraphBuildPage = {
  setup() {
    const Icons = inject('Icons');
    const t = inject('t');
    const status = ref({ provider: 'none', enabled: false, schemaCount: 0 });
    const schemas = ref([]);
    const schemaPageInfo = ref({ limit: 20, nextCursor: '', hasMore: false });
    const graphSpaces = ref([]);
    const graphSpacePageInfo = ref({ limit: 20, nextCursor: '', hasMore: false });
    const existingGraphNodes = ref([]);
    const existingNodePageInfo = ref({ limit: 50, nextCursor: '', hasMore: false });
    const existingGraphRelations = ref([]);
    const existingRelationPageInfo = ref({ limit: 50, nextCursor: '', hasMore: false });
    const selectedSchemaId = ref('');
    const graphSpaceMode = ref('new');
    const selectedExistingGraphId = ref('');
    const graphId = ref('');
    const requestId = ref('');
    const buildEditorView = ref('visual');
    const sourceText = ref('{\n  "nodes": [],\n  "relations": []\n}');
    const sourceDirty = ref(false);
    const dataDesignerModel = ref(createEmptyGraphDataDesigner());
    const focusMode = ref(false);
    const buildResult = ref(null);
    const loading = ref(false);
    const loadingMore = ref(false);
    const loadingMoreSpaces = ref(false);
    const loadingExistingGraphData = ref(false);
    const loadingMoreExistingGraphData = ref(false);
    const existingDataOperationPending = ref(false);
    const submitting = ref(false);
    const error = ref('');
    let existingGraphQueryVersion = 0;

    const selectedSchema = computed(() =>
      schemas.value.find(schema => schema.schemaId === selectedSchemaId.value) || null
    );
    const nodeTypeNames = computed(() => Object.keys(selectedSchema.value?.nodeTypes || {}));
    const relationTypeNames = computed(() => Object.keys(selectedSchema.value?.relationTypes || {}));
    const compatibleGraphSpaces = computed(() => graphSpaces.value.filter(
      space => space.schemaId === selectedSchemaId.value
    ));
    const selectedExistingGraphSpace = computed(() => compatibleGraphSpaces.value.find(
      space => space.graphId === selectedExistingGraphId.value
    ) || null);
    const dataDesignerIssues = computed(() => selectedSchema.value
      ? collectGraphDataDesignerIssues(dataDesignerModel.value, selectedSchema.value, t)
      : []);
    const canvasSourceText = computed(() => JSON.stringify(
      graphDataDesignerToCanvasSource(
        dataDesignerModel.value,
        selectedSchema.value,
        existingGraphNodes.value,
        existingGraphRelations.value
      ),
      null,
      2
    ));
    const displayedSourceText = computed(() =>
      sourceDirty.value ? sourceText.value : canvasSourceText.value
    );
    const canSubmit = computed(() =>
      status.value.enabled
      && Boolean(selectedSchemaId.value)
      && Boolean(graphId.value.trim())
      && Boolean(requestId.value.trim())
      && (buildEditorView.value === 'source' && sourceDirty.value
        ? Boolean(sourceText.value.trim())
        : dataDesignerIssues.value.length === 0)
      && !existingDataOperationPending.value
      && !submitting.value
    );

    function newRequestId() {
      requestId.value = `graph-${Date.now()}`;
    }

    async function loadSchemas({ append = false, cursor = '' } = {}) {
      const page = requirePageResponse(
        await CyreneAPI.listGraphSchemas({
          limit: schemaPageInfo.value.limit,
          cursor,
        }),
        schema => typeof schema?.schemaId === 'string',
        t('graphInvalidSchemaPageResponse')
      );
      schemas.value = append ? [...schemas.value, ...page.items] : page.items;
      schemaPageInfo.value = page.pageInfo;
      if (!selectedSchemaId.value && schemas.value.length > 0) {
        selectedSchemaId.value = schemas.value[0].schemaId;
      }
    }

    async function loadGraphSpaces({ append = false, cursor = '' } = {}) {
      const page = requirePageResponse(
        await CyreneAPI.listGraphSpaces({
          limit: graphSpacePageInfo.value.limit,
          cursor,
        }),
        space => typeof space?.graphId === 'string'
          && typeof space?.schemaId === 'string',
        t('graphInvalidPageResponse')
      );
      graphSpaces.value = append ? [...graphSpaces.value, ...page.items] : page.items;
      graphSpacePageInfo.value = page.pageInfo;
      syncExistingGraphSpace();
    }

    function syncExistingGraphSpace() {
      if (graphSpaceMode.value !== 'existing') return;
      const stillAvailable = compatibleGraphSpaces.value.some(
        space => space.graphId === selectedExistingGraphId.value
      );
      if (!stillAvailable) {
        selectedExistingGraphId.value = compatibleGraphSpaces.value[0]?.graphId || '';
      }
      graphId.value = selectedExistingGraphId.value;
    }

    function clearExistingGraphData() {
      existingGraphQueryVersion += 1;
      existingGraphNodes.value = [];
      existingNodePageInfo.value = { limit: 50, nextCursor: '', hasMore: false };
      existingGraphRelations.value = [];
      existingRelationPageInfo.value = { limit: 50, nextCursor: '', hasMore: false };
      loadingExistingGraphData.value = false;
      loadingMoreExistingGraphData.value = false;
    }

    async function loadExistingGraphNodes({
      append = false,
      cursor = '',
      queryVersion = existingGraphQueryVersion,
    } = {}) {
      const requestedGraphId = selectedExistingGraphId.value;
      const requestedSchemaId = selectedSchemaId.value;
      if (!append) {
        existingGraphNodes.value = [];
        existingNodePageInfo.value = { limit: 50, nextCursor: '', hasMore: false };
      }
      const page = await CyreneAPI.listGraphNodes({
        graphId: requestedGraphId,
        schemaId: requestedSchemaId,
        limit: existingNodePageInfo.value.limit,
        cursor,
      });
      if (queryVersion !== existingGraphQueryVersion
          || requestedGraphId !== selectedExistingGraphId.value
          || requestedSchemaId !== selectedSchemaId.value) {
        return;
      }
      requireGraphNodePage(page, t);
      const nodesById = new Map(
        (append ? existingGraphNodes.value : []).map(node => [node.nodeId, node])
      );
      page.items.forEach(node => nodesById.set(node.nodeId, node));
      existingGraphNodes.value = Array.from(nodesById.values());
      existingNodePageInfo.value = page.pageInfo;
    }

    async function loadExistingGraphRelations({
      append = false,
      cursor = '',
      queryVersion = existingGraphQueryVersion,
    } = {}) {
      const requestedGraphId = selectedExistingGraphId.value;
      const requestedSchemaId = selectedSchemaId.value;
      if (!append) {
        existingGraphRelations.value = [];
        existingRelationPageInfo.value = { limit: 50, nextCursor: '', hasMore: false };
      }
      const page = await CyreneAPI.listGraphRelations({
        graphId: requestedGraphId,
        schemaId: requestedSchemaId,
        limit: existingRelationPageInfo.value.limit,
        cursor,
      });
      if (queryVersion !== existingGraphQueryVersion
          || requestedGraphId !== selectedExistingGraphId.value
          || requestedSchemaId !== selectedSchemaId.value) {
        return;
      }
      requireGraphRelationPage(page, t);
      const relationsById = new Map(
        (append ? existingGraphRelations.value : [])
          .map(relation => [relation.relationId, relation])
      );
      page.items.forEach(relation => relationsById.set(relation.relationId, relation));
      existingGraphRelations.value = Array.from(relationsById.values());
      existingRelationPageInfo.value = page.pageInfo;
    }

    async function refreshExistingGraphData() {
      const requestedGraphId = selectedExistingGraphId.value;
      const requestedSchemaId = selectedSchemaId.value;
      if (graphSpaceMode.value !== 'existing' || !requestedGraphId || !requestedSchemaId) {
        clearExistingGraphData();
        return;
      }
      const queryVersion = ++existingGraphQueryVersion;
      loadingExistingGraphData.value = true;
      error.value = '';
      try {
        await Promise.all([
          loadExistingGraphNodes({ queryVersion }),
          loadExistingGraphRelations({ queryVersion }),
        ]);
      } catch (e) {
        error.value = e.message;
      } finally {
        if (queryVersion === existingGraphQueryVersion) {
          loadingExistingGraphData.value = false;
        }
      }
    }

    function selectGraphSpaceMode(mode) {
      graphSpaceMode.value = mode;
      if (mode === 'new') {
        selectedExistingGraphId.value = '';
        graphId.value = '';
        clearExistingGraphData();
        return;
      }
      syncExistingGraphSpace();
      refreshExistingGraphData();
    }

    function useExistingGraphSpace() {
      graphId.value = selectedExistingGraphId.value;
      refreshExistingGraphData();
    }

    function clearDataDraft() {
      buildEditorView.value = 'visual';
      dataDesignerModel.value = createEmptyGraphDataDesigner();
      sourceText.value = '{\n  "nodes": [],\n  "relations": []\n}';
      sourceDirty.value = false;
    }

    function resetDataDesigner() {
      clearDataDraft();
      buildResult.value = null;
    }

    function setBuildEditorView(view) {
      if (buildEditorView.value === view || !selectedSchema.value) return;
      error.value = '';
      try {
        if (view === 'source') {
          sourceText.value = canvasSourceText.value;
          sourceDirty.value = false;
        } else if (sourceDirty.value) {
          const source = JSON.parse(sourceText.value);
          validateExistingGraphDraftStructure(
            source,
            existingGraphNodes.value,
            t
          );
          dataDesignerModel.value = markPersistedGraphDrafts(
            graphSourceToDataDesigner(source, selectedSchema.value, t),
            existingGraphNodes.value,
            existingGraphRelations.value
          );
          sourceDirty.value = false;
        }
        buildEditorView.value = view;
      } catch (e) {
        error.value = e instanceof SyntaxError
          ? t('jsonError') + e.message
          : e.message;
      }
    }

    function updateSourceText(value) {
      sourceText.value = value;
      sourceDirty.value = true;
    }

    async function refreshGraph() {
      loading.value = true;
      error.value = '';
      try {
        const graphStatus = await CyreneAPI.getGraphStatus();
        status.value = {
          provider: graphStatus.provider,
          enabled: graphStatus.enabled,
          schemaCount: graphStatus.schemaCount,
        };
        await Promise.all([loadSchemas(), loadGraphSpaces()]);
        if (graphSpaceMode.value === 'existing') {
          await refreshExistingGraphData();
        }
      } catch (e) {
        error.value = e.message;
      } finally {
        loading.value = false;
      }
    }

    async function loadMoreSchemas() {
      if (!schemaPageInfo.value.hasMore || loadingMore.value) return;
      loadingMore.value = true;
      error.value = '';
      try {
        await loadSchemas({
          append: true,
          cursor: schemaPageInfo.value.nextCursor,
        });
      } catch (e) {
        error.value = e.message;
      } finally {
        loadingMore.value = false;
      }
    }

    async function loadMoreGraphSpaces() {
      if (!graphSpacePageInfo.value.hasMore || loadingMoreSpaces.value) return;
      loadingMoreSpaces.value = true;
      error.value = '';
      try {
        await loadGraphSpaces({
          append: true,
          cursor: graphSpacePageInfo.value.nextCursor,
        });
      } catch (e) {
        error.value = e.message;
      } finally {
        loadingMoreSpaces.value = false;
      }
    }

    async function loadMoreExistingGraphData() {
      const hasMoreNodes = existingNodePageInfo.value.hasMore;
      const hasMoreRelations = existingRelationPageInfo.value.hasMore;
      if ((!hasMoreNodes && !hasMoreRelations) || loadingMoreExistingGraphData.value) return;
      const queryVersion = existingGraphQueryVersion;
      loadingMoreExistingGraphData.value = true;
      error.value = '';
      try {
        const requests = [];
        if (hasMoreNodes) {
          requests.push(loadExistingGraphNodes({
            append: true,
            cursor: existingNodePageInfo.value.nextCursor,
            queryVersion,
          }));
        }
        if (hasMoreRelations) {
          requests.push(loadExistingGraphRelations({
            append: true,
            cursor: existingRelationPageInfo.value.nextCursor,
            queryVersion,
          }));
        }
        await Promise.all(requests);
      } catch (e) {
        error.value = e.message;
      } finally {
        if (queryVersion === existingGraphQueryVersion) {
          loadingMoreExistingGraphData.value = false;
        }
      }
    }

    async function deleteExistingNode(nodeId) {
      if (!selectedExistingGraphSpace.value || existingDataOperationPending.value) return;
      if (!window.confirm(t('graphDataDeleteExistingNodeConfirm'))) return;
      existingDataOperationPending.value = true;
      error.value = '';
      try {
        await CyreneAPI.deleteGraphNode({
          graphId: selectedExistingGraphSpace.value.graphId,
          schemaId: selectedExistingGraphSpace.value.schemaId,
          nodeId,
          detach: true,
        });
        await Promise.all([loadGraphSpaces(), refreshExistingGraphData()]);
        showToast(t('graphDataExistingNodeDeleted'), 'success');
      } catch (e) {
        error.value = e.message;
      } finally {
        existingDataOperationPending.value = false;
      }
    }

    async function deleteExistingRelation(relationId) {
      if (!selectedExistingGraphSpace.value || existingDataOperationPending.value) return;
      if (!window.confirm(t('graphDataDeleteExistingRelationConfirm'))) return;
      existingDataOperationPending.value = true;
      error.value = '';
      try {
        await CyreneAPI.deleteGraphRelation({
          graphId: selectedExistingGraphSpace.value.graphId,
          schemaId: selectedExistingGraphSpace.value.schemaId,
          relationId,
        });
        await Promise.all([loadGraphSpaces(), refreshExistingGraphData()]);
        showToast(t('graphDataExistingRelationDeleted'), 'success');
      } catch (e) {
        error.value = e.message;
      } finally {
        existingDataOperationPending.value = false;
      }
    }

    async function submitGraph() {
      if (!canSubmit.value) return;
      submitting.value = true;
      error.value = '';
      buildResult.value = null;
      try {
        let source;
        if (buildEditorView.value === 'visual' || !sourceDirty.value) {
          if (dataDesignerIssues.value.length) {
            throw new Error(dataDesignerIssues.value[0]);
          }
          source = graphDataDesignerToSource(dataDesignerModel.value, selectedSchema.value);
          sourceText.value = JSON.stringify(source, null, 2);
        } else {
          source = JSON.parse(sourceText.value);
        }
        if (!source || Array.isArray(source) || typeof source !== 'object') {
          throw new Error(t('graphSourceObjectRequired'));
        }
        if (source.nodes !== undefined && !Array.isArray(source.nodes)) {
          throw new Error(t('graphNodesArrayRequired'));
        }
        if (source.relations !== undefined && !Array.isArray(source.relations)) {
          throw new Error(t('graphRelationsArrayRequired'));
        }
        validateExistingGraphDraftStructure(
          source,
          existingGraphNodes.value,
          t
        );
        buildResult.value = await CyreneAPI.buildGraph({
          requestId: requestId.value.trim(),
          graphId: graphId.value.trim(),
          schemaId: selectedSchemaId.value,
          sourceType: 'structured',
          converterId: 'canonical-json',
          source,
        });
        await loadGraphSpaces();
        if (graphSpaceMode.value === 'existing') {
          await refreshExistingGraphData();
        }
        clearDataDraft();
        showToast(t('graphBuildSuccess'), 'success');
        newRequestId();
      } catch (e) {
        error.value = e instanceof SyntaxError
          ? t('jsonError') + e.message
          : e.message;
      } finally {
        submitting.value = false;
      }
    }

    newRequestId();
    watch(selectedSchemaId, () => {
      syncExistingGraphSpace();
      resetDataDesigner();
      clearExistingGraphData();
      if (graphSpaceMode.value === 'existing') {
        refreshExistingGraphData();
      }
    });
    onMounted(refreshGraph);

    return {
      Icons, t, status, schemas, schemaPageInfo, graphSpaces, graphSpacePageInfo,
      existingGraphNodes, existingNodePageInfo,
      existingGraphRelations, existingRelationPageInfo,
      selectedSchemaId, selectedSchema, graphSpaceMode, selectedExistingGraphId,
      compatibleGraphSpaces, selectedExistingGraphSpace,
      nodeTypeNames, relationTypeNames, graphId, requestId,
      displayedSourceText, buildResult, focusMode,
      buildEditorView, dataDesignerModel, dataDesignerIssues,
      loading, loadingMore, loadingMoreSpaces, loadingExistingGraphData,
      loadingMoreExistingGraphData, existingDataOperationPending,
      submitting, error, canSubmit,
      newRequestId, refreshGraph, loadMoreSchemas, loadMoreGraphSpaces,
      loadMoreExistingGraphData,
      deleteExistingNode, deleteExistingRelation,
      selectGraphSpaceMode, useExistingGraphSpace,
      setBuildEditorView, updateSourceText, submitGraph,
    };
  },
  template: `
    <div class="graph-page">
      <div class="card card-gold mb-4">
        <div class="card-header graph-card-header">
          <div>
            <div class="card-title">{{ t('graphRuntimeStatus') }}</div>
            <div class="text-xs text-ash mt-2">{{ t('graphStatusHint') }}</div>
          </div>
          <button class="btn btn-ghost btn-sm" @click="refreshGraph" :disabled="loading">
            <span v-html="Icons.refresh" style="width:14px;height:14px;"></span>
            {{ t('reload') }}
          </button>
        </div>
        <div class="card-body">
          <div v-if="loading" style="text-align:center;padding:1rem;">
            <div class="loading-dots"><span></span><span></span><span></span></div>
          </div>
          <div v-else class="graph-status-grid">
            <div class="graph-status-item">
              <span class="text-xs text-ash">{{ t('graphProvider') }}</span>
              <strong>{{ status.provider }}</strong>
            </div>
            <div class="graph-status-item">
              <span class="text-xs text-ash">{{ t('status') }}</span>
              <span :class="['tag', status.enabled ? 'tag-gold' : 'tag-dusk']">
                {{ status.enabled ? t('enabled') : t('disabled') }}
              </span>
            </div>
            <div class="graph-status-item">
              <span class="text-xs text-ash">{{ t('graphSchemaCount') }}</span>
              <strong>{{ status.schemaCount }}</strong>
            </div>
          </div>
        </div>
      </div>

      <div :class="['graph-layout', { 'focus-mode': focusMode }]">
        <div v-show="!focusMode" class="card">
          <div class="card-header">
            <div class="card-title">{{ t('graphSchema') }}</div>
          </div>
          <div class="card-body">
            <div class="input-group">
              <label class="input-label">{{ t('graphSelectSchema') }}</label>
              <select class="input" v-model="selectedSchemaId" :disabled="schemas.length === 0">
                <option value="" disabled>{{ t('graphSelectSchemaPlaceholder') }}</option>
                <option v-for="schema in schemas" :key="schema.schemaId" :value="schema.schemaId">
                  {{ schema.schemaId }} · v{{ schema.version }}
                </option>
              </select>
            </div>

            <div v-if="selectedSchema" class="graph-schema-detail mt-4">
              <div class="flex justify-between items-center">
                <span class="text-sm">{{ selectedSchema.schemaId }}</span>
                <span class="tag tag-iris">{{ selectedSchema.mode }}</span>
              </div>
              <div class="mt-4">
                <div class="text-xs text-ash mb-2">{{ t('graphNodeTypes') }}</div>
                <div class="graph-chip-list">
                  <span v-for="name in nodeTypeNames" :key="name" class="tag tag-gold">{{ name }}</span>
                </div>
              </div>
              <div class="mt-4">
                <div class="text-xs text-ash mb-2">{{ t('graphRelationTypes') }}</div>
                <div class="graph-chip-list">
                  <span v-for="name in relationTypeNames" :key="name" class="tag tag-rose">{{ name }}</span>
                  <span v-if="relationTypeNames.length === 0" class="text-xs text-ash">-</span>
                </div>
              </div>
            </div>

            <div v-if="schemas.length === 0 && !loading" class="graph-empty-hint mt-4">
              {{ t('graphNoSchema') }}
            </div>
            <button v-if="schemaPageInfo.hasMore" class="btn btn-ghost btn-sm w-full mt-4"
                    @click="loadMoreSchemas" :disabled="loadingMore">
              {{ loadingMore ? t('graphLoadingMore') : t('graphLoadMore') }}
            </button>
          </div>
        </div>

        <div class="card">
          <div class="card-header graph-card-header">
            <div>
              <div class="card-title">{{ t('graphStructuredBuild') }}</div>
              <div class="text-xs text-ash mt-2">{{ t('graphCanonicalHint') }}</div>
            </div>
            <div class="graph-schema-header-actions">
              <span class="tag tag-iris">canonical-json</span>
              <button class="btn btn-ghost btn-sm" @click="focusMode = !focusMode">
                {{ focusMode ? t('graphShowSchemaList') : t('graphFocusEditor') }}
              </button>
            </div>
          </div>
          <div class="card-body">
            <div class="graph-build-meta-grid">
              <div class="graph-space-builder">
                <label class="input-label">{{ t('graphSpaceTarget') }}</label>
                <div class="graph-space-mode-switch" role="group" :aria-label="t('graphSpaceTarget')">
                  <button type="button"
                          :class="['graph-space-mode-button', { active: graphSpaceMode === 'new' }]"
                          @click="selectGraphSpaceMode('new')">
                    {{ t('graphCreateSpace') }}
                  </button>
                  <button type="button"
                          :class="['graph-space-mode-button', { active: graphSpaceMode === 'existing' }]"
                          @click="selectGraphSpaceMode('existing')">
                    {{ t('graphUseExistingSpace') }}
                  </button>
                </div>

                <template v-if="graphSpaceMode === 'new'">
                  <input class="input"
                         v-model="graphId"
                         :placeholder="t('graphIdPlaceholder')"
                         :aria-label="t('graphId')" />
                  <div class="text-xs text-ash graph-field-hint">{{ t('graphIdBusinessHint') }}</div>
                </template>
                <template v-else>
                  <select class="input"
                          v-model="selectedExistingGraphId"
                          :disabled="compatibleGraphSpaces.length === 0"
                          @change="useExistingGraphSpace">
                    <option value="" disabled>{{ t('graphSelectExistingSpace') }}</option>
                    <option v-for="space in compatibleGraphSpaces"
                            :key="space.graphId + ':' + space.schemaId"
                            :value="space.graphId">
                      {{ space.graphId }} · {{ space.nodeCount }} {{ t('graphNodes') }} · {{ space.relationCount }} {{ t('graphRelations') }}
                    </option>
                  </select>
                  <div v-if="selectedExistingGraphSpace" class="graph-space-selection-summary">
                    <span>{{ t('graphSchema') }}: {{ selectedExistingGraphSpace.schemaId }}</span>
                    <span>{{ t('graphNodes') }}: {{ selectedExistingGraphSpace.nodeCount }}</span>
                    <span>{{ t('graphRelations') }}: {{ selectedExistingGraphSpace.relationCount }}</span>
                  </div>
                  <div v-else class="text-xs text-ash graph-field-hint">
                    {{ t('graphNoCompatibleSpaces') }}
                  </div>
                  <button v-if="graphSpacePageInfo.hasMore"
                          type="button"
                          class="btn btn-ghost btn-sm graph-space-load-more"
                          :disabled="loadingMoreSpaces"
                          @click="loadMoreGraphSpaces">
                    {{ loadingMoreSpaces ? t('graphLoadingMore') : t('graphLoadMoreSpaces') }}
                  </button>
                </template>
              </div>
              <div class="input-group">
                <label class="input-label">{{ t('graphRequestId') }}</label>
                <div class="flex gap-2">
                  <input class="input" v-model="requestId" />
                  <button class="btn btn-ghost btn-sm" @click="newRequestId">{{ t('graphRegenerate') }}</button>
                </div>
                <div class="text-xs text-ash graph-field-hint">{{ t('graphRequestIdHint') }}</div>
              </div>
            </div>

            <div class="graph-data-editor-shell mt-4">
              <div class="graph-data-editor-header">
                <div>
                  <label class="input-label">{{ t('graphSourceData') }}</label>
                  <div class="text-xs text-ash">{{ t('graphSourceHint') }}</div>
                </div>
                <div class="graph-schema-view-switch" role="tablist">
                  <button :class="['graph-schema-view-button', { active: buildEditorView === 'visual' }]"
                          role="tab"
                          :aria-selected="buildEditorView === 'visual'"
                          @click="setBuildEditorView('visual')">
                    {{ t('graphDataVisualMode') }}
                  </button>
                  <button :class="['graph-schema-view-button', { active: buildEditorView === 'source' }]"
                          role="tab"
                          :aria-selected="buildEditorView === 'source'"
                          @click="setBuildEditorView('source')">
                    {{ t('graphDataJsonMode') }}
                  </button>
                </div>
              </div>

              <template v-if="buildEditorView === 'visual'">
                <div v-if="dataDesignerIssues.length" class="alert alert-error graph-schema-validation mb-4">
                  <div>
                    <strong>{{ t('graphDataValidationTitle') }}</strong>
                    <ul>
                      <li v-for="issue in dataDesignerIssues.slice(0, 4)" :key="issue">{{ issue }}</li>
                    </ul>
                  </div>
                </div>
                <GraphDataDesigner
                  v-if="selectedSchema"
                  v-model="dataDesignerModel"
                  :schema="selectedSchema"
                  :existing-nodes="existingGraphNodes"
                  :existing-relations="existingGraphRelations"
                  :existing-data-loading="loadingExistingGraphData || loadingMoreExistingGraphData"
                  :existing-data-has-more="existingNodePageInfo.hasMore || existingRelationPageInfo.hasMore"
                  :existing-operation-pending="existingDataOperationPending"
                  @load-more-existing-data="loadMoreExistingGraphData"
                  @delete-existing-node="deleteExistingNode"
                  @delete-existing-relation="deleteExistingRelation"
                />
                <div v-else class="graph-designer-inline-empty">
                  {{ t('graphSelectSchemaFirst') }}
                </div>
              </template>
              <template v-else>
                <textarea class="input graph-source-editor"
                          :value="displayedSourceText"
                          spellcheck="false"
                          @input="updateSourceText($event.target.value)"></textarea>
                <div class="text-xs text-ash">{{ t('graphDataSourceModeHint') }}</div>
              </template>
            </div>

            <div v-if="!status.enabled" class="graph-empty-hint mt-4">
              {{ t('graphProviderDisabledHint') }}
            </div>
            <div v-if="error" class="text-sm mt-4" style="color:var(--error);">{{ error }}</div>
            <div class="flex justify-between items-center mt-4 graph-submit-row">
              <span class="text-xs text-ash">{{ t('graphTransactionHint') }}</span>
              <button class="btn btn-primary" @click="submitGraph" :disabled="!canSubmit">
                {{ submitting ? t('graphBuilding') : t('graphBuild') }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="buildResult" class="card mt-4">
        <div class="card-header">
          <div class="card-title">{{ t('graphLastBuild') }}</div>
          <span :class="['tag', buildResult.committed ? 'tag-gold' : 'tag-dusk']">
            {{ buildResult.committed ? t('graphCommitted') : t('graphNotCommitted') }}
          </span>
        </div>
        <div class="card-body graph-build-result">
          <div><span>{{ t('graphId') }}</span><strong>{{ buildResult.graphId }}</strong></div>
          <div><span>{{ t('graphSchema') }}</span><strong>{{ buildResult.schemaId }}</strong></div>
          <div><span>{{ t('graphNodes') }}</span><strong>{{ buildResult.nodeCount }}</strong></div>
          <div><span>{{ t('graphRelations') }}</span><strong>{{ buildResult.relationCount }}</strong></div>
        </div>
      </div>
    </div>
  `
};

// ── Audit Page ──
const GraphTopology = {
  props: {
    nodes: { type: Array, required: true },
    relations: { type: Array, required: true },
    loading: { type: Boolean, default: false },
    connectionMode: { type: Boolean, default: false },
  },
  emits: ['select', 'connect', 'rendered'],
  setup(props, { emit }) {
    const t = inject('t');
    const canvas = ref(null);
    const available = typeof window.cytoscape === 'function';
    let graph = null;
    let resizeObserver = null;
    let lastStructureSignature = '';
    let connectionSource = null;
    let connectionTarget = null;
    let connectionSourceWasLocked = false;

    const nodeColors = ['#8b7ec8', '#d79ab7', '#b49a62', '#5f9ea0', '#887a9f', '#9a6f72'];
    const connectionCursorId = '__graph-connection-cursor';
    const connectionPreviewId = '__graph-connection-preview';

    function nodeDisplayName(node) {
      const properties = node.properties || {};
      const preferredValue = properties.name || properties.title || properties.displayName;
      if (typeof preferredValue === 'string' && preferredValue.trim()) {
        return preferredValue;
      }
      return node.nodeId;
    }

    function nodeColor(node) {
      const label = Array.from(node.labels || [])[0] || node.nodeId;
      const colorIndex = Array.from(label)
        .reduce((total, character) => total + character.codePointAt(0), 0) % nodeColors.length;
      return nodeColors[colorIndex];
    }

    function topologyElements() {
      const nodeElements = props.nodes.map(node => ({
        group: 'nodes',
        data: {
          id: `node:${node.nodeId}`,
          label: nodeDisplayName(node),
          color: nodeColor(node),
          item: node,
          ...(node.readOnly ? { readOnly: true } : {}),
        },
      }));
      const relationElements = props.relations.map(relation => ({
        group: 'edges',
        data: {
          id: `relation:${relation.relationId}`,
          source: `node:${relation.sourceNodeId}`,
          target: `node:${relation.targetNodeId}`,
          label: relation.relationType,
          item: relation,
          ...(relation.readOnly ? { readOnly: true } : {}),
        },
      }));
      return [...nodeElements, ...relationElements];
    }

    function fitGraph() {
      if (!graph || graph.elements().empty()) return;
      graph.resize();
      graph.fit(graph.elements(), 48);
    }

    function resizeGraph() {
      graph?.resize();
    }

    function structureSignature(elements) {
      return elements
        .map(element => element.group === 'nodes'
          ? `node:${element.data.id}`
          : `edge:${element.data.id}:${element.data.source}:${element.data.target}`)
        .sort()
        .join('|');
    }

    function updateElementData(elements) {
      graph.batch(() => {
        elements.forEach(element => {
          const current = graph.getElementById(element.data.id);
          if (current.empty()) return;
          current.data('label', element.data.label);
          current.data('item', element.data.item);
          if (element.group === 'nodes') {
            current.data('color', element.data.color);
          }
          if (element.data.readOnly) {
            current.data('readOnly', true);
          } else {
            current.removeData('readOnly');
          }
        });
      });
    }

    function averagePosition(nodes) {
      if (!nodes.length) return { x: 0, y: 0 };
      return nodes.reduce((position, node) => ({
        x: position.x + node.position('x') / nodes.length,
        y: position.y + node.position('y') / nodes.length,
      }), { x: 0, y: 0 });
    }

    function positionNewNodes(newNodes, retainedNodes) {
      const viewport = graph.extent();
      const viewportCenter = {
        x: (viewport.x1 + viewport.x2) / 2,
        y: (viewport.y1 + viewport.y2) / 2,
      };
      const fallbackCenter = Number.isFinite(viewportCenter.x) && Number.isFinite(viewportCenter.y)
        ? viewportCenter
        : averagePosition(retainedNodes);
      newNodes.forEach((node, index) => {
        const connectedRetainedNodes = node.connectedEdges()
          .connectedNodes()
          .filter(candidate => retainedNodes.some(retained => retained.id() === candidate.id()));
        const anchor = connectedRetainedNodes.length
          ? averagePosition(connectedRetainedNodes.toArray())
          : fallbackCenter;
        const angle = (index * 2.399963229728653) - (Math.PI / 4);
        const radius = 105 + (Math.floor(index / 6) * 42);
        node.position({
          x: anchor.x + Math.cos(angle) * radius,
          y: anchor.y + Math.sin(angle) * radius,
        });
      });
    }

    function setConnectionTarget(target) {
      connectionTarget?.removeClass('connection-target');
      connectionTarget = target || null;
      connectionTarget?.addClass('connection-target');
    }

    function resetConnectionGesture() {
      setConnectionTarget(null);
      graph?.getElementById(connectionPreviewId).remove();
      graph?.getElementById(connectionCursorId).remove();
      connectionSource?.removeClass('connection-source');
      if (connectionSource && !connectionSourceWasLocked) {
        connectionSource.unlock();
      }
      connectionSource = null;
      connectionSourceWasLocked = false;
    }

    function startConnectionGesture(event) {
      if (!props.connectionMode || event.target.data('id') === connectionCursorId) return;
      resetConnectionGesture();
      connectionSource = event.target;
      connectionSourceWasLocked = connectionSource.locked();
      connectionSource.lock();
      connectionSource.addClass('connection-source');
      graph.add([
        {
          group: 'nodes',
          data: { id: connectionCursorId },
          position: { ...event.position },
          classes: 'connection-cursor',
          grabbable: false,
          selectable: false,
        },
        {
          group: 'edges',
          data: {
            id: connectionPreviewId,
            source: connectionSource.id(),
            target: connectionCursorId,
          },
          classes: 'connection-preview',
          selectable: false,
        },
      ]);
    }

    function updateConnectionGesture(event) {
      if (!connectionSource || !event.position) return;
      const cursor = graph.getElementById(connectionCursorId);
      if (cursor.nonempty()) cursor.position(event.position);
      const pointer = event.renderedPosition;
      if (!pointer) return;
      let nearestTarget = null;
      let nearestDistance = 46;
      graph.nodes().forEach(node => {
        if (node.id() === connectionSource.id() || node.id() === connectionCursorId) return;
        const position = node.renderedPosition();
        const distance = Math.hypot(position.x - pointer.x, position.y - pointer.y);
        if (distance <= nearestDistance) {
          nearestTarget = node;
          nearestDistance = distance;
        }
      });
      setConnectionTarget(nearestTarget);
    }

    function finishConnectionGesture(event) {
      if (!connectionSource) return;
      updateConnectionGesture(event);
      const sourceItem = connectionSource.data('item');
      const targetItem = connectionTarget?.data('item');
      resetConnectionGesture();
      if (sourceItem && targetItem) {
        emit('connect', { source: sourceItem, target: targetItem });
      }
    }

    function renderGraph() {
      if (!available || !canvas.value) {
        emit('rendered');
        return;
      }
      if (!graph) {
        graph = window.cytoscape({
          container: canvas.value,
          elements: [],
          minZoom: 0.2,
          maxZoom: 2.5,
          style: [
            {
              selector: 'node',
              style: {
                'background-color': 'data(color)',
                'border-color': 'rgba(255, 255, 255, 0.72)',
                'border-width': 1,
                'color': '#f4eef8',
                'font-family': 'Inter, system-ui, sans-serif',
                'font-size': 11,
                'label': 'data(label)',
                'min-zoomed-font-size': 7,
                'text-background-color': '#171020',
                'text-background-opacity': 0.78,
                'text-background-padding': 3,
                'text-background-shape': 'roundrectangle',
                'text-margin-y': 7,
                'text-max-width': 96,
                'text-valign': 'bottom',
                'text-wrap': 'ellipsis',
                'height': 34,
                'width': 34,
              },
            },
            {
              selector: 'edge',
              style: {
                'curve-style': 'bezier',
                'color': '#fff7fb',
                'font-family': 'Inter, system-ui, sans-serif',
                'font-size': 10,
                'font-weight': 600,
                'label': 'data(label)',
                'line-color': 'rgba(192, 178, 215, 0.58)',
                'target-arrow-color': 'rgba(192, 178, 215, 0.72)',
                'target-arrow-shape': 'triangle',
                'text-background-color': '#120d19',
                'text-background-opacity': 0.96,
                'text-background-padding': 4,
                'text-background-shape': 'roundrectangle',
                'text-border-color': 'rgba(232, 160, 191, 0.42)',
                'text-border-opacity': 1,
                'text-border-width': 1,
                'text-outline-color': '#120d19',
                'text-outline-width': 2,
                'text-rotation': 'autorotate',
                'width': 1.4,
              },
            },
            {
              selector: 'node[readOnly]',
              style: {
                'border-style': 'dashed',
                'opacity': 0.84,
              },
            },
            {
              selector: 'edge[readOnly]',
              style: {
                'line-style': 'dashed',
                'opacity': 0.84,
              },
            },
            {
              selector: ':selected',
              style: {
                'border-color': '#e8a0bf',
                'border-width': 3,
                'line-color': '#e8a0bf',
                'target-arrow-color': '#e8a0bf',
              },
            },
            {
              selector: '.connection-source, .connection-target',
              style: {
                'border-color': '#e8a0bf',
                'border-width': 4,
              },
            },
            {
              selector: '.connection-target',
              style: {
                'overlay-color': '#e8a0bf',
                'overlay-opacity': 0.14,
                'overlay-padding': 9,
              },
            },
            {
              selector: '.connection-cursor',
              style: {
                'background-opacity': 0,
                'border-width': 0,
                'events': 'no',
                'height': 1,
                'label': '',
                'opacity': 0,
                'width': 1,
              },
            },
            {
              selector: '.connection-preview',
              style: {
                'curve-style': 'straight',
                'events': 'no',
                'label': '',
                'line-color': '#e8a0bf',
                'line-style': 'dashed',
                'opacity': 0.9,
                'target-arrow-color': '#e8a0bf',
                'target-arrow-shape': 'triangle',
                'width': 2,
              },
            },
          ],
        });
        graph.on('tap', 'node', event => {
          emit('select', { kind: 'node', item: event.target.data('item') });
        });
        graph.on('tap', 'edge', event => {
          emit('select', { kind: 'relation', item: event.target.data('item') });
        });
        graph.on('tap', event => {
          if (event.target === graph) emit('select', null);
        });
        graph.on('tapstart', 'node', startConnectionGesture);
        graph.on('tapdrag', updateConnectionGesture);
        graph.on('tapend', finishConnectionGesture);
      }

      const elements = topologyElements();
      const nextStructureSignature = structureSignature(elements);
      if (nextStructureSignature === lastStructureSignature) {
        updateElementData(elements);
        emit('rendered');
        return;
      }

      const previousPositions = new Map(
        graph.nodes()
          .filter(node => node.id() !== connectionCursorId)
          .map(node => [node.id(), { ...node.position() }])
      );
      const previousZoom = graph.zoom();
      const previousPan = { ...graph.pan() };
      resetConnectionGesture();
      graph.elements().remove();
      if (elements.length === 0) {
        lastStructureSignature = nextStructureSignature;
        emit('rendered');
        return;
      }
      graph.add(elements);
      const retainedNodes = [];
      const newNodes = [];
      graph.nodes().forEach(node => {
        const previousPosition = previousPositions.get(node.id());
        if (previousPosition) {
          node.position(previousPosition);
          retainedNodes.push(node);
        } else {
          newNodes.push(node);
        }
      });
      if (retainedNodes.length === 0) {
        graph.layout({
          name: props.relations.length > 0 ? 'cose' : 'grid',
          animate: false,
          fit: true,
          padding: 48,
          nodeRepulsion: 9000,
          idealEdgeLength: 110,
          edgeElasticity: 80,
        }).run();
      } else {
        positionNewNodes(newNodes, retainedNodes);
        graph.zoom(previousZoom);
        graph.pan(previousPan);
      }
      lastStructureSignature = nextStructureSignature;
      updateElementData(elements);
      emit('rendered');
    }

    watch(
      () => [props.nodes, props.relations],
      () => nextTick(renderGraph),
      { deep: true }
    );
    watch(
      () => props.connectionMode,
      enabled => {
        if (!enabled) resetConnectionGesture();
      }
    );

    onMounted(() => {
      renderGraph();
      if (available && typeof ResizeObserver === 'function') {
        resizeObserver = new ResizeObserver(resizeGraph);
        resizeObserver.observe(canvas.value);
      }
    });

    onUnmounted(() => {
      resizeObserver?.disconnect();
      graph?.destroy();
      graph = null;
    });

    return { t, canvas, available, fitGraph };
  },
  template: `
    <div class="graph-topology">
      <div ref="canvas"
           :class="['graph-topology-canvas', { 'is-connecting': connectionMode }]"></div>
      <div v-if="!available" class="graph-topology-overlay">
        {{ t('graphTopologyUnavailable') }}
      </div>
      <div v-else-if="loading" class="graph-topology-overlay">
        <div class="loading-dots"><span></span><span></span><span></span></div>
      </div>
      <div v-else-if="nodes.length === 0" class="graph-topology-overlay">
        {{ t('graphTopologyEmpty') }}
      </div>
      <button v-if="available && nodes.length > 0"
              class="btn btn-ghost btn-sm graph-fit-button"
              @click="fitGraph">
        {{ t('graphFitView') }}
      </button>
    </div>
  `,
};

const GRAPH_DATA_NUMBER_TYPES = new Set(['INTEGER', 'LONG', 'DOUBLE', 'NUMBER']);
const GRAPH_DATA_JSON_TYPES = new Set(['STRING_LIST', 'SCALAR_LIST', 'JSON']);
let graphDataDesignerIdSequence = 0;

function nextGraphDataDesignerId(prefix) {
  graphDataDesignerIdSequence += 1;
  return `${prefix}-${graphDataDesignerIdSequence}`;
}

function createEmptyGraphDataDesigner() {
  return { nodes: [], relations: [] };
}

function graphNodePropertyDefinitions(schema, labels = []) {
  return labels.reduce((definitions, label) => ({
    ...definitions,
    ...(schema?.nodeTypes?.[label]?.properties || {}),
  }), {});
}

function graphRelationPropertyDefinitions(schema, relationType) {
  return schema?.relationTypes?.[relationType]?.properties || {};
}

function graphDataDefaultRawValue(type) {
  if (type === 'BOOLEAN') return 'false';
  if (type === 'STRING_LIST' || type === 'SCALAR_LIST') return '[]';
  if (type === 'JSON') return '{}';
  return '';
}

function graphDataValueToRaw(value, type) {
  if (GRAPH_DATA_JSON_TYPES.has(type)) {
    return JSON.stringify(value, null, 2);
  }
  return String(value);
}

function createGraphDataPropertyValues(definitions, sourceValues = {}, previousValues = {}) {
  return Object.entries(definitions).reduce((result, [name, definition]) => {
    if (Object.prototype.hasOwnProperty.call(sourceValues, name)) {
      result[name] = {
        enabled: true,
        rawValue: graphDataValueToRaw(sourceValues[name], definition.type),
      };
      return result;
    }
    if (previousValues[name]) {
      result[name] = { ...previousValues[name] };
      if (definition.required) result[name].enabled = true;
      return result;
    }
    result[name] = {
      enabled: Boolean(definition.required),
      rawValue: graphDataDefaultRawValue(definition.type),
    };
    return result;
  }, {});
}

function parseGraphDataPropertyValue(rawValue, type) {
  if (type === 'STRING' || type === 'TEMPORAL') return rawValue;
  if (type === 'BOOLEAN') return rawValue === 'true';
  if (GRAPH_DATA_NUMBER_TYPES.has(type)) {
    const numberValue = Number(rawValue);
    if (!Number.isFinite(numberValue)) {
      throw new Error('number');
    }
    if (['INTEGER', 'LONG'].includes(type) && !Number.isInteger(numberValue)) {
      throw new Error('integer');
    }
    return numberValue;
  }
  if (GRAPH_DATA_JSON_TYPES.has(type)) {
    const parsedValue = JSON.parse(rawValue);
    if (type === 'STRING_LIST'
        && (!Array.isArray(parsedValue) || parsedValue.some(value => typeof value !== 'string'))) {
      throw new Error('string-list');
    }
    if (type === 'SCALAR_LIST'
        && (!Array.isArray(parsedValue)
          || parsedValue.some(value => value !== null && typeof value === 'object'))) {
      throw new Error('scalar-list');
    }
    if (type === 'JSON'
        && (!parsedValue || typeof parsedValue !== 'object')) {
      throw new Error('json');
    }
    return parsedValue;
  }
  return rawValue;
}

function graphDataPropertiesToSource(definitions, propertyValues) {
  return Object.entries(definitions).reduce((result, [name, definition]) => {
    const state = propertyValues[name];
    if (!state || (!definition.required && !state.enabled)) return result;
    result[name] = parseGraphDataPropertyValue(state.rawValue, definition.type);
    return result;
  }, {});
}

function validateGraphDataProperties(definitions, propertyValues, ownerName, t, issues) {
  Object.entries(definitions).forEach(([name, definition]) => {
    const state = propertyValues[name];
    if (definition.required && !state?.enabled) {
      issues.push(`${t('graphDataRequiredPropertyMissing')}: ${ownerName}/${name}`);
      return;
    }
    if (!state || (!definition.required && !state.enabled)) return;
    try {
      parseGraphDataPropertyValue(state.rawValue, definition.type);
    } catch (e) {
      issues.push(`${t('graphDataPropertyTypeInvalid')}: ${ownerName}/${name} → ${definition.type}`);
    }
  });
}

function collectGraphDataDesignerIssues(model, schema, t) {
  const issues = [];
  if (!model.nodes.length && !model.relations.length) {
    issues.push(t('graphDataContentRequired'));
    return issues;
  }

  const nodeIds = new Set();
  const nodeByBusinessId = new Map();
  model.nodes.forEach(node => {
    const nodeId = node.nodeId.trim();
    if (!nodeId) {
      issues.push(t('graphDataNodeIdRequired'));
    } else if (nodeIds.has(nodeId)) {
      issues.push(`${t('graphDataNodeIdDuplicate')}: ${nodeId}`);
    }
    nodeIds.add(nodeId);
    nodeByBusinessId.set(nodeId, node);

    if (node.labels.length !== 1) {
      issues.push(`${t('graphDataSingleNodeTypeRequired')}: ${nodeId || '-'}`);
    } else {
      node.labels.forEach(label => {
        if (!schema.nodeTypes?.[label]) {
          issues.push(`${t('graphDataUnknownNodeType')}: ${label}`);
        }
      });
    }
    const definitions = graphNodePropertyDefinitions(schema, node.labels);
    validateGraphDataProperties(definitions, node.propertyValues, nodeId || '-', t, issues);
  });

  const relationIds = new Set();
  model.relations.forEach(relation => {
    const relationId = relation.relationId.trim();
    if (!relationId) {
      issues.push(t('graphDataRelationIdRequired'));
    } else if (relationIds.has(relationId)) {
      issues.push(`${t('graphDataRelationIdDuplicate')}: ${relationId}`);
    }
    relationIds.add(relationId);

    const relationDefinition = schema.relationTypes?.[relation.relationType];
    if (!relationDefinition) {
      issues.push(`${t('graphDataUnknownRelationType')}: ${relation.relationType || '-'}`);
    }
    if (!relation.sourceNodeId.trim()) {
      issues.push(`${t('graphDataSourceNodeRequired')}: ${relationId || '-'}`);
    }
    if (!relation.targetNodeId.trim()) {
      issues.push(`${t('graphDataTargetNodeRequired')}: ${relationId || '-'}`);
    }

    const sourceNode = nodeByBusinessId.get(relation.sourceNodeId.trim());
    if (sourceNode && relationDefinition
        && sourceNode.labels.every(label => !relationDefinition.sourceLabels.includes(label))) {
      issues.push(`${t('graphDataSourceTypeInvalid')}: ${relationId || '-'}`);
    }
    const targetNode = nodeByBusinessId.get(relation.targetNodeId.trim());
    if (targetNode && relationDefinition
        && targetNode.labels.every(label => !relationDefinition.targetLabels.includes(label))) {
      issues.push(`${t('graphDataTargetTypeInvalid')}: ${relationId || '-'}`);
    }

    const definitions = graphRelationPropertyDefinitions(schema, relation.relationType);
    validateGraphDataProperties(definitions, relation.propertyValues, relationId || '-', t, issues);
  });
  return issues;
}

function graphDataDesignerToSource(model, schema) {
  return {
    nodes: model.nodes.map(node => ({
      nodeId: node.nodeId.trim(),
      labels: [...node.labels],
      properties: graphDataPropertiesToSource(
        graphNodePropertyDefinitions(schema, node.labels),
        node.propertyValues
      ),
    })),
    relations: model.relations.map(relation => ({
      relationId: relation.relationId.trim(),
      sourceNodeId: relation.sourceNodeId.trim(),
      targetNodeId: relation.targetNodeId.trim(),
      relationType: relation.relationType,
      properties: graphDataPropertiesToSource(
        graphRelationPropertyDefinitions(schema, relation.relationType),
        relation.propertyValues
      ),
    })),
  };
}

function graphDataDesignerToCanvasSource(
  model,
  schema,
  existingNodes = [],
  existingRelations = []
) {
  const pendingNodeIds = new Set(model.nodes.map(node => node.nodeId.trim()));
  const nodes = existingNodes
    .filter(node => !pendingNodeIds.has(node.nodeId))
    .map(node => ({
    nodeId: node.nodeId,
    labels: Array.from(node.labels || []),
    properties: { ...(node.properties || {}) },
  }));
  model.nodes.forEach(node => nodes.push({
    nodeId: node.nodeId.trim(),
    labels: [...node.labels],
    properties: graphDataPropertiesToPreview(
      graphNodePropertyDefinitions(schema, node.labels),
      node.propertyValues
    ),
  }));

  const pendingRelationIds = new Set(
    model.relations.map(relation => relation.relationId.trim())
  );
  const relations = existingRelations
    .filter(relation => !pendingRelationIds.has(relation.relationId))
    .map(relation => ({
    relationId: relation.relationId,
    sourceNodeId: relation.sourceNodeId,
    targetNodeId: relation.targetNodeId,
    relationType: relation.relationType,
    properties: { ...(relation.properties || {}) },
  }));
  model.relations.forEach(relation => relations.push({
    relationId: relation.relationId.trim(),
    sourceNodeId: relation.sourceNodeId.trim(),
    targetNodeId: relation.targetNodeId.trim(),
    relationType: relation.relationType,
    properties: graphDataPropertiesToPreview(
      graphRelationPropertyDefinitions(schema, relation.relationType),
      relation.propertyValues
    ),
  }));

  return {
    nodes,
    relations,
  };
}

function validateExistingGraphDraftStructure(source, existingNodes, t) {
  const existingNodesById = new Map(existingNodes.map(node => [node.nodeId, node]));
  (source.nodes || []).forEach(node => {
    const existingNode = existingNodesById.get(node.nodeId);
    if (!existingNode) return;
    const requestedLabels = Array.from(node.labels || []).sort();
    const existingLabels = Array.from(existingNode.labels || []).sort();
    if (JSON.stringify(requestedLabels) !== JSON.stringify(existingLabels)) {
      throw new Error(t('graphDataExistingIdentityLocked'));
    }
  });
}

function markPersistedGraphDrafts(model, existingNodes, existingRelations) {
  const existingNodeIds = new Set(existingNodes.map(node => node.nodeId));
  model.nodes.forEach(node => {
    if (existingNodeIds.has(node.nodeId)) node.persisted = true;
  });
  const existingRelationIds = new Set(
    existingRelations.map(relation => relation.relationId)
  );
  model.relations.forEach(relation => {
    if (existingRelationIds.has(relation.relationId)) relation.persisted = true;
  });
  return model;
}

function graphSourceToDataDesigner(source, schema, t) {
  if (!source || Array.isArray(source) || typeof source !== 'object') {
    throw new Error(t('graphSourceObjectRequired'));
  }
  const unknownFields = Object.keys(source)
    .filter(field => !['nodes', 'relations'].includes(field));
  if (unknownFields.length) {
    throw new Error(`${t('graphDataUnknownTopLevelField')}: ${unknownFields.join(', ')}`);
  }
  if (source.nodes !== undefined && !Array.isArray(source.nodes)) {
    throw new Error(t('graphNodesArrayRequired'));
  }
  if (source.relations !== undefined && !Array.isArray(source.relations)) {
    throw new Error(t('graphRelationsArrayRequired'));
  }

  const model = {
    nodes: (source.nodes || []).map(node => {
      const labels = Array.from(node.labels || []);
      const definitions = graphNodePropertyDefinitions(schema, labels);
      const unknownProperty = Object.keys(node.properties || {})
        .find(name => !definitions[name]);
      if (unknownProperty) {
        throw new Error(`${t('graphDataUnknownProperty')}: ${node.nodeId || '-'}/${unknownProperty}`);
      }
      return {
        id: nextGraphDataDesignerId('data-node'),
        nodeId: node.nodeId || '',
        labels,
        propertyValues: createGraphDataPropertyValues(definitions, node.properties || {}),
      };
    }),
    relations: (source.relations || []).map(relation => {
      const definitions = graphRelationPropertyDefinitions(schema, relation.relationType);
      const unknownProperty = Object.keys(relation.properties || {})
        .find(name => !definitions[name]);
      if (unknownProperty) {
        throw new Error(`${t('graphDataUnknownProperty')}: ${relation.relationId || '-'}/${unknownProperty}`);
      }
      return {
        id: nextGraphDataDesignerId('data-relation'),
        relationId: relation.relationId || '',
        relationType: relation.relationType || '',
        sourceNodeId: relation.sourceNodeId || '',
        targetNodeId: relation.targetNodeId || '',
        propertyValues: createGraphDataPropertyValues(definitions, relation.properties || {}),
      };
    }),
  };
  const issues = collectGraphDataDesignerIssues(model, schema, t);
  const blockingIssue = issues.find(issue => issue !== t('graphDataContentRequired'));
  if (blockingIssue) throw new Error(blockingIssue);
  return model;
}

const GraphDataPropertyEditor = {
  props: {
    definitions: { type: Object, required: true },
    values: { type: Object, required: true },
    editable: { type: Boolean, default: true },
  },
  emits: ['update:values'],
  setup(props, { emit }) {
    const t = inject('t');
    const propertyEntries = computed(() => Object.entries(props.definitions)
      .map(([name, definition]) => ({ name, definition })));

    function updateValue(name, field, value) {
      const nextValues = cloneGraphSchemaDesigner(props.values);
      nextValues[name] = {
        ...(nextValues[name] || { enabled: false, rawValue: '' }),
        [field]: value,
      };
      emit('update:values', nextValues);
    }

    function inputKind(type) {
      if (type === 'BOOLEAN') return 'boolean';
      if (GRAPH_DATA_JSON_TYPES.has(type)) return 'json';
      if (GRAPH_DATA_NUMBER_TYPES.has(type)) return 'number';
      return 'text';
    }

    function numberStep(type) {
      return ['INTEGER', 'LONG'].includes(type) ? '1' : 'any';
    }

    return { t, propertyEntries, updateValue, inputKind, numberStep };
  },
  template: `
    <div class="graph-data-property-editor">
      <div class="graph-designer-section-header">
        <div>
          <strong>{{ t('graphDesignerProperties') }}</strong>
          <span class="text-xs text-ash">{{ t('graphDataPropertiesHint') }}</span>
        </div>
      </div>
      <div v-if="propertyEntries.length === 0" class="graph-designer-inline-empty">
        {{ t('graphDesignerNoProperties') }}
      </div>
      <div v-else class="graph-data-property-list">
        <article v-for="entry in propertyEntries" :key="entry.name" class="graph-data-property-card">
          <div class="graph-data-property-title">
            <div>
              <strong>{{ entry.name }}</strong>
              <span class="tag tag-dusk">{{ entry.definition.type }}</span>
              <span v-if="entry.definition.required" class="tag tag-rose">{{ t('graphDesignerRequired') }}</span>
            </div>
            <label v-if="!entry.definition.required" class="graph-data-property-toggle">
              <input type="checkbox"
                     :checked="values[entry.name]?.enabled"
                     :disabled="!editable"
                     @change="updateValue(entry.name, 'enabled', $event.target.checked)" />
              <span>{{ t('graphDataSetProperty') }}</span>
            </label>
          </div>

          <template v-if="entry.definition.required || values[entry.name]?.enabled">
            <select v-if="inputKind(entry.definition.type) === 'boolean'"
                    class="input"
                    :value="values[entry.name]?.rawValue"
                    :disabled="!editable"
                    @change="updateValue(entry.name, 'rawValue', $event.target.value)">
              <option value="false">false</option>
              <option value="true">true</option>
            </select>
            <textarea v-else-if="inputKind(entry.definition.type) === 'json'"
                      class="input graph-data-json-value"
                      :value="values[entry.name]?.rawValue"
                      :disabled="!editable"
                      spellcheck="false"
                      @input="updateValue(entry.name, 'rawValue', $event.target.value)"></textarea>
            <input v-else
                   class="input"
                   :type="inputKind(entry.definition.type)"
                   :step="numberStep(entry.definition.type)"
                   :value="values[entry.name]?.rawValue"
                   :disabled="!editable"
                   @input="updateValue(entry.name, 'rawValue', $event.target.value)" />
          </template>
        </article>
      </div>
    </div>
  `,
};

const GraphDataDesigner = {
  components: { GraphTopology, GraphDataPropertyEditor },
  props: {
    schema: { type: Object, required: true },
    modelValue: { type: Object, required: true },
    existingNodes: { type: Array, default: () => [] },
    existingRelations: { type: Array, default: () => [] },
    existingDataLoading: { type: Boolean, default: false },
    existingDataHasMore: { type: Boolean, default: false },
    existingOperationPending: { type: Boolean, default: false },
  },
  emits: [
    'update:modelValue',
    'load-more-existing-data',
    'delete-existing-node',
    'delete-existing-relation',
  ],
  setup(props, { emit }) {
    const t = inject('t');
    const selectedKind = ref('node');
    const selectedId = ref('');
    const connectMode = ref(false);
    const pendingSourceId = ref('');
    const connectionError = ref('');

    const nodeTypeNames = computed(() => Object.keys(props.schema.nodeTypes || {}));
    const relationTypeNames = computed(() => Object.keys(props.schema.relationTypes || {}));
    const selectedNode = computed(() => selectedKind.value === 'node'
      ? props.modelValue.nodes.find(node => node.id === selectedId.value) || null
      : null);
    const selectedRelation = computed(() => selectedKind.value === 'relation'
      ? props.modelValue.relations.find(relation => relation.id === selectedId.value) || null
      : null);
    const selectedExistingNode = computed(() => selectedKind.value === 'existing-node'
      ? props.existingNodes.find(node => node.nodeId === selectedId.value) || null
      : null);
    const selectedExistingRelation = computed(() => selectedKind.value === 'existing-relation'
      ? props.existingRelations.find(relation => relation.relationId === selectedId.value) || null
      : null);
    const visibleExistingNodes = computed(() => {
      const pendingNodeIds = new Set(props.modelValue.nodes.map(node => node.nodeId));
      return props.existingNodes.filter(node => !pendingNodeIds.has(node.nodeId));
    });
    const visibleExistingRelations = computed(() => {
      const pendingRelationIds = new Set(
        props.modelValue.relations.map(relation => relation.relationId)
      );
      return props.existingRelations.filter(
        relation => !pendingRelationIds.has(relation.relationId)
      );
    });
    const selectedNodeDefinitions = computed(() => selectedNode.value
      ? graphNodePropertyDefinitions(props.schema, selectedNode.value.labels)
      : {});
    const selectedRelationDefinitions = computed(() => selectedRelation.value
      ? graphRelationPropertyDefinitions(props.schema, selectedRelation.value.relationType)
      : {});
    const selectedNodeType = computed(() => selectedNode.value?.labels[0] || '');
    const selectedRelationDefinition = computed(() =>
      props.schema.relationTypes?.[selectedRelation.value?.relationType] || null
    );
    const selectedRelationTypeOptions = computed(() => {
      if (!selectedRelation.value) return relationTypeNames.value;
      const sourceNode = findAvailableNode(
        props.modelValue,
        selectedRelation.value.sourceNodeId
      );
      const targetNode = findAvailableNode(
        props.modelValue,
        selectedRelation.value.targetNodeId
      );
      if (!sourceNode || !targetNode) return relationTypeNames.value;
      const compatibleTypes = compatibleRelationTypeNames(sourceNode, targetNode);
      return compatibleTypes.length ? compatibleTypes : relationTypeNames.value;
    });
    const sourceNodeOptions = computed(() => selectedRelationDefinition.value
      ? compatibleEndpoints(
          props.modelValue,
          selectedRelation.value.relationType,
          'source'
        )
      : []);
    const targetNodeOptions = computed(() => selectedRelationDefinition.value
      ? compatibleEndpoints(
          props.modelValue,
          selectedRelation.value.relationType,
          'target'
        )
      : []);
    const sourceUsesExternalId = computed(() => Boolean(selectedRelation.value?.sourceNodeId)
      && !sourceNodeOptions.value.some(node =>
        node.nodeId === selectedRelation.value.sourceNodeId));
    const targetUsesExternalId = computed(() => Boolean(selectedRelation.value?.targetNodeId)
      && !targetNodeOptions.value.some(node =>
        node.nodeId === selectedRelation.value.targetNodeId));
    const pendingSourceNode = computed(() =>
      availableNodes(props.modelValue)
        .find(node => node.nodeId === pendingSourceId.value) || null
    );
    const connectableNodeCount = computed(() => availableNodes(props.modelValue).length);

    const topologyData = computed(() => {
      const canvasNodeIdByBusinessId = new Map();
      const nodes = props.modelValue.nodes.map(node => {
        const businessNodeId = node.nodeId.trim();
        if (businessNodeId) {
          canvasNodeIdByBusinessId.set(businessNodeId, node.id);
        }
        const propertyPreview = graphDataPropertiesToPreview(
          graphNodePropertyDefinitions(props.schema, node.labels),
          node.propertyValues
        );
        return {
          nodeId: node.id,
          labels: node.labels,
          properties: {
            name: node.nodeId || t('graphDataUnnamedNode'),
            ...propertyPreview,
          },
          designerId: node.id,
          businessNodeId,
          readOnly: false,
        };
      });
      visibleExistingNodes.value.forEach(node => {
        const businessNodeId = node.nodeId.trim();
        if (!businessNodeId || canvasNodeIdByBusinessId.has(businessNodeId)) return;
        const canvasNodeId = `existing:${businessNodeId}`;
        canvasNodeIdByBusinessId.set(businessNodeId, canvasNodeId);
        nodes.push({
          nodeId: canvasNodeId,
          labels: node.labels,
          properties: { ...node.properties, name: node.properties?.name || businessNodeId },
          designerId: '',
          businessNodeId,
          readOnly: true,
        });
      });
      const supplementalNodes = new Map();
      const canvasNodeId = nodeId => {
        const businessNodeId = nodeId.trim();
        const knownCanvasNodeId = canvasNodeIdByBusinessId.get(businessNodeId);
        if (knownCanvasNodeId) return knownCanvasNodeId;
        const externalId = `external:${businessNodeId || '-'}`;
        if (!supplementalNodes.has(externalId)) {
          supplementalNodes.set(externalId, {
            nodeId: externalId,
            labels: ['External'],
            properties: { name: businessNodeId || t('graphDataExternalNode') },
            designerId: '',
            businessNodeId,
            readOnly: true,
          });
        }
        return externalId;
      };
      const existingRelations = visibleExistingRelations.value
        .map(relation => ({
          relationId: `existing:${relation.relationId}`,
          sourceNodeId: canvasNodeId(relation.sourceNodeId),
          targetNodeId: canvasNodeId(relation.targetNodeId),
          relationType: relation.relationType,
          properties: relation.properties || {},
          designerId: '',
          businessRelationId: relation.relationId,
          readOnly: true,
        }));
      const pendingRelations = props.modelValue.relations.map(relation => ({
        relationId: `pending:${relation.id}`,
        sourceNodeId: canvasNodeId(relation.sourceNodeId),
        targetNodeId: canvasNodeId(relation.targetNodeId),
        relationType: relation.relationType || t('graphDesignerUnnamedRelation'),
        properties: {},
        designerId: relation.id,
        businessRelationId: relation.relationId,
        readOnly: false,
      }));
      return {
        nodes: [...nodes, ...supplementalNodes.values()],
        relations: [...existingRelations, ...pendingRelations],
      };
    });

    function commit(mutator) {
      const nextModel = cloneGraphSchemaDesigner(props.modelValue);
      mutator(nextModel);
      emit('update:modelValue', nextModel);
    }

    function formatExistingProperties(properties) {
      return JSON.stringify(properties || {}, null, 2);
    }

    function uniqueBusinessId(items, field, prefix) {
      const existing = new Set(items.map(item => item[field]));
      let index = 1;
      let candidate = `${prefix}-${index}`;
      while (existing.has(candidate)) {
        index += 1;
        candidate = `${prefix}-${index}`;
      }
      return candidate;
    }

    function idPrefixForType(typeName, fallback) {
      const normalized = typeName
        .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
        .replace(/[^A-Za-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .toLowerCase();
      return normalized || fallback;
    }

    function nodeMatchesAllowedLabels(node, allowedLabels = []) {
      return node.labels.some(label => allowedLabels.includes(label));
    }

    function availableNodes(model) {
      const nodesById = new Map();
      model.nodes.forEach(node => nodesById.set(node.nodeId, {
        nodeId: node.nodeId,
        labels: node.labels,
        origin: 'batch',
      }));
      props.existingNodes.forEach(node => {
        if (!nodesById.has(node.nodeId)) {
          nodesById.set(node.nodeId, {
            nodeId: node.nodeId,
            labels: Array.from(node.labels || []),
            origin: 'existing',
          });
        }
      });
      return Array.from(nodesById.values());
    }

    function compatibleEndpoints(model, relationType, endpoint) {
      const definition = props.schema.relationTypes?.[relationType];
      if (!definition) return [];
      const allowedLabels = endpoint === 'source'
        ? definition.sourceLabels
        : definition.targetLabels;
      return availableNodes(model)
        .filter(node => nodeMatchesAllowedLabels(node, allowedLabels));
    }

    function compatibleRelationTypeNames(sourceNode, targetNode) {
      return relationTypeNames.value.filter(relationType => {
        const definition = props.schema.relationTypes[relationType];
        return nodeMatchesAllowedLabels(sourceNode, definition.sourceLabels)
          && nodeMatchesAllowedLabels(targetNode, definition.targetLabels);
      });
    }

    function compatibleEndpoint(model, relationType, endpoint) {
      return compatibleEndpoints(model, relationType, endpoint)[0] || null;
    }

    function findAvailableNode(model, nodeId) {
      return availableNodes(model).find(node => node.nodeId === nodeId) || null;
    }

    function selectedNodeIdForConnection() {
      if (selectedKind.value === 'node') {
        return selectedNode.value?.nodeId?.trim() || '';
      }
      if (selectedKind.value === 'existing-node') {
        return selectedExistingNode.value?.nodeId?.trim() || '';
      }
      return '';
    }

    function firstCompatibleTarget(model, relationType, sourceNodeId) {
      const targets = compatibleEndpoints(model, relationType, 'target');
      return targets.find(node => node.nodeId !== sourceNodeId) || targets[0] || null;
    }

    function appendRelation(model, relationType, sourceNodeId, targetNodeId) {
      const id = nextGraphDataDesignerId('data-relation');
      model.relations.push({
        id,
        relationId: uniqueBusinessId(
          model.relations,
          'relationId',
          idPrefixForType(relationType, 'relation')
        ),
        relationType,
        sourceNodeId,
        targetNodeId,
        propertyValues: createGraphDataPropertyValues(
          graphRelationPropertyDefinitions(props.schema, relationType)
        ),
      });
      return id;
    }

    function businessNodeId(topologyNode) {
      return topologyNode?.businessNodeId
        || props.modelValue.nodes.find(node => node.id === topologyNode?.designerId)?.nodeId
        || '';
    }

    function connectNodes(sourceNodeId, targetNodeId) {
      const sourceNode = findAvailableNode(props.modelValue, sourceNodeId);
      const targetNode = findAvailableNode(props.modelValue, targetNodeId);
      const relationTypes = sourceNode && targetNode
        ? compatibleRelationTypeNames(sourceNode, targetNode)
        : [];
      if (!sourceNode || !targetNode || !relationTypes.length) {
        connectionError.value = t('graphDataNoCompatibleRelation');
        pendingSourceId.value = '';
        return;
      }
      let createdId = '';
      commit(model => {
        createdId = appendRelation(
          model,
          relationTypes[0],
          sourceNode.nodeId,
          targetNode.nodeId
        );
      });
      selectedKind.value = 'relation';
      selectedId.value = createdId;
      connectMode.value = false;
      pendingSourceId.value = '';
      connectionError.value = '';
    }

    function connectTopologyNodes(connection) {
      connectNodes(
        businessNodeId(connection?.source),
        businessNodeId(connection?.target)
      );
    }

    function addNode() {
      const id = nextGraphDataDesignerId('data-node');
      const labels = nodeTypeNames.value.length ? [nodeTypeNames.value[0]] : [];
      const definitions = graphNodePropertyDefinitions(props.schema, labels);
      commit(model => model.nodes.push({
        id,
        nodeId: uniqueBusinessId(
          [...model.nodes, ...props.existingNodes],
          'nodeId',
          'node'
        ),
        labels,
        propertyValues: createGraphDataPropertyValues(definitions),
      }));
      selectedKind.value = 'node';
      selectedId.value = id;
    }

    function addRelation() {
      let createdId = '';
      const preferredSourceNodeId = selectedNodeIdForConnection();
      const preferredSourceNode = findAvailableNode(
        props.modelValue,
        preferredSourceNodeId
      );
      const preferredRelationType = preferredSourceNode
        ? relationTypeNames.value.find(type => {
            const definition = props.schema.relationTypes?.[type];
            return definition
              && nodeMatchesAllowedLabels(preferredSourceNode, definition.sourceLabels)
              && firstCompatibleTarget(
                props.modelValue,
                type,
                preferredSourceNode.nodeId
              );
          })
        : '';
      if (preferredSourceNode && !preferredRelationType) {
        connectionError.value = t('graphDataSelectedNodeCannotBeSource');
        return;
      }
      commit(model => {
        const relationType = preferredRelationType
          || relationTypeNames.value.find(type =>
            compatibleEndpoint(model, type, 'source')
            && compatibleEndpoint(model, type, 'target'))
          || relationTypeNames.value[0]
          || '';
        const definition = props.schema.relationTypes?.[relationType];
        const sourceNode = preferredSourceNode && definition
            && nodeMatchesAllowedLabels(preferredSourceNode, definition.sourceLabels)
          ? preferredSourceNode
          : compatibleEndpoint(model, relationType, 'source');
        const targetNode = firstCompatibleTarget(
          model,
          relationType,
          sourceNode?.nodeId || ''
        );
        createdId = appendRelation(
          model,
          relationType,
          sourceNode?.nodeId || '',
          targetNode?.nodeId || ''
        );
      });
      connectionError.value = '';
      selectedKind.value = 'relation';
      selectedId.value = createdId;
    }

    function selectNode(id) {
      selectedKind.value = 'node';
      selectedId.value = id;
    }

    function selectRelation(id) {
      selectedKind.value = 'relation';
      selectedId.value = id;
    }

    function selectExistingNode(nodeId) {
      selectedKind.value = 'existing-node';
      selectedId.value = nodeId;
    }

    function selectExistingRelation(relationId) {
      selectedKind.value = 'existing-relation';
      selectedId.value = relationId;
    }

    function editExistingNode() {
      const existingNode = selectedExistingNode.value;
      if (!existingNode) return;
      const existingDraft = props.modelValue.nodes.find(
        node => node.nodeId === existingNode.nodeId
      );
      if (existingDraft) {
        selectNode(existingDraft.id);
        return;
      }
      const labels = Array.from(existingNode.labels || []);
      if (labels.length !== 1 || !props.schema.nodeTypes?.[labels[0]]) {
        connectionError.value = t('graphDataExistingNodeVisualEditUnsupported');
        return;
      }
      const id = nextGraphDataDesignerId('data-node');
      commit(model => model.nodes.push({
        id,
        nodeId: existingNode.nodeId,
        labels,
        propertyValues: createGraphDataPropertyValues(
          graphNodePropertyDefinitions(props.schema, labels),
          existingNode.properties || {}
        ),
        persisted: true,
      }));
      connectionError.value = '';
      selectNode(id);
    }

    function editExistingRelation() {
      const existingRelation = selectedExistingRelation.value;
      if (!existingRelation) return;
      const existingDraft = props.modelValue.relations.find(
        relation => relation.relationId === existingRelation.relationId
      );
      if (existingDraft) {
        selectRelation(existingDraft.id);
        return;
      }
      if (!props.schema.relationTypes?.[existingRelation.relationType]) {
        connectionError.value = t('graphDataExistingRelationVisualEditUnsupported');
        return;
      }
      const id = nextGraphDataDesignerId('data-relation');
      commit(model => model.relations.push({
        id,
        relationId: existingRelation.relationId,
        relationType: existingRelation.relationType,
        sourceNodeId: existingRelation.sourceNodeId,
        targetNodeId: existingRelation.targetNodeId,
        propertyValues: createGraphDataPropertyValues(
          graphRelationPropertyDefinitions(props.schema, existingRelation.relationType),
          existingRelation.properties || {}
        ),
        persisted: true,
      }));
      connectionError.value = '';
      selectRelation(id);
    }

    function selectTopologyElement(element) {
      if (!element?.item) return;
      if (element.kind === 'node' && connectMode.value) {
        const selectedBusinessNodeId = businessNodeId(element.item);
        const node = availableNodes(props.modelValue)
          .find(item => item.nodeId === selectedBusinessNodeId);
        if (!node) return;
        connectionError.value = '';
        if (!pendingSourceId.value) {
          pendingSourceId.value = node.nodeId;
          if (element.item.designerId) selectNode(element.item.designerId);
          else selectExistingNode(node.nodeId);
          return;
        }

        const sourceNode = availableNodes(props.modelValue)
          .find(item => item.nodeId === pendingSourceId.value);
        connectNodes(sourceNode?.nodeId || '', node.nodeId);
        return;
      }
      if (element.item.designerId) {
        if (element.kind === 'node') selectNode(element.item.designerId);
        else selectRelation(element.item.designerId);
        return;
      }
      if (element.kind === 'node' && element.item.businessNodeId) {
        if (props.existingNodes.some(node =>
          node.nodeId === element.item.businessNodeId)) {
          selectExistingNode(element.item.businessNodeId);
        }
        return;
      }
      if (element.kind === 'relation' && element.item.businessRelationId) {
        selectExistingRelation(element.item.businessRelationId);
      }
    }

    function updateNodeId(value) {
      const oldNodeId = selectedNode.value?.nodeId || '';
      commit(model => {
        const node = model.nodes.find(item => item.id === selectedId.value);
        if (!node) return;
        node.nodeId = value;
        model.relations.forEach(relation => {
          if (relation.sourceNodeId === oldNodeId) relation.sourceNodeId = value;
          if (relation.targetNodeId === oldNodeId) relation.targetNodeId = value;
        });
      });
    }

    function setNodeType(label) {
      commit(model => {
        const node = model.nodes.find(item => item.id === selectedId.value);
        if (!node) return;
        node.labels = [label];
        node.propertyValues = createGraphDataPropertyValues(
          graphNodePropertyDefinitions(props.schema, node.labels),
          {},
          node.propertyValues
        );
        model.relations.forEach(relation => {
          const definition = props.schema.relationTypes?.[relation.relationType];
          if (!definition) return;
          if (relation.sourceNodeId === node.nodeId
              && !definition.sourceLabels.includes(label)) {
            relation.sourceNodeId = '';
          }
          if (relation.targetNodeId === node.nodeId
              && !definition.targetLabels.includes(label)) {
            relation.targetNodeId = '';
          }
        });
      });
    }

    function updateNodePropertyValues(values) {
      commit(model => {
        const node = model.nodes.find(item => item.id === selectedId.value);
        if (node) node.propertyValues = values;
      });
    }

    function updateRelationField(field, value) {
      commit(model => {
        const relation = model.relations.find(item => item.id === selectedId.value);
        if (!relation) return;
        relation[field] = value;
        if (field === 'relationType') {
          relation.propertyValues = createGraphDataPropertyValues(
            graphRelationPropertyDefinitions(props.schema, value),
            {},
            relation.propertyValues
          );
          const definition = props.schema.relationTypes?.[value];
          if (!definition) return;
          const currentSource = findAvailableNode(model, relation.sourceNodeId);
          if (!relation.sourceNodeId
              || (currentSource
                && !nodeMatchesAllowedLabels(currentSource, definition.sourceLabels))) {
            relation.sourceNodeId = compatibleEndpoint(model, value, 'source')?.nodeId || '';
          }
          const currentTarget = findAvailableNode(model, relation.targetNodeId);
          if (!relation.targetNodeId
              || (currentTarget
                && !nodeMatchesAllowedLabels(currentTarget, definition.targetLabels))) {
            relation.targetNodeId = compatibleEndpoint(model, value, 'target')?.nodeId || '';
          }
        }
      });
    }

    function updateRelationPropertyValues(values) {
      commit(model => {
        const relation = model.relations.find(item => item.id === selectedId.value);
        if (relation) relation.propertyValues = values;
      });
    }

    function deleteSelectedNode() {
      const nodeId = selectedNode.value?.nodeId || '';
      const nextModel = cloneGraphSchemaDesigner(props.modelValue);
      nextModel.nodes = nextModel.nodes.filter(node => node.id !== selectedId.value);
      nextModel.relations = nextModel.relations.filter(
        relation => relation.sourceNodeId !== nodeId && relation.targetNodeId !== nodeId
      );
      emit('update:modelValue', nextModel);
      if (pendingSourceId.value === nodeId) {
        pendingSourceId.value = '';
      }
      selectedKind.value = 'node';
      selectedId.value = nextModel.nodes[0]?.id || '';
    }

    function deleteSelectedRelation() {
      const nextModel = cloneGraphSchemaDesigner(props.modelValue);
      nextModel.relations = nextModel.relations
        .filter(relation => relation.id !== selectedId.value);
      emit('update:modelValue', nextModel);
      selectedKind.value = 'relation';
      selectedId.value = nextModel.relations[0]?.id || '';
      if (!selectedId.value) {
        selectedKind.value = 'node';
        selectedId.value = nextModel.nodes[0]?.id || '';
      }
    }

    function deleteSelectedExistingNode() {
      if (selectedExistingNode.value && !props.existingOperationPending) {
        emit('delete-existing-node', selectedExistingNode.value.nodeId);
      }
    }

    function deleteSelectedExistingRelation() {
      if (selectedExistingRelation.value && !props.existingOperationPending) {
        emit('delete-existing-relation', selectedExistingRelation.value.relationId);
      }
    }

    function toggleConnectMode() {
      const nextConnectMode = !connectMode.value;
      connectMode.value = nextConnectMode;
      pendingSourceId.value = nextConnectMode ? selectedNodeIdForConnection() : '';
      connectionError.value = '';
    }

    function handleDesignerKeydown(event) {
      const target = event.target;
      const isEditing = target instanceof HTMLElement
        && Boolean(target.closest('input, textarea, select, [contenteditable="true"]'));
      if (event.key === 'Escape' && connectMode.value) {
        connectMode.value = false;
        pendingSourceId.value = '';
        connectionError.value = '';
        return;
      }
      if (isEditing || !['Delete', 'Backspace'].includes(event.key)) return;
      if (!selectedNode.value
          && !selectedRelation.value
          && !selectedExistingNode.value
          && !selectedExistingRelation.value) {
        return;
      }
      event.preventDefault();
      if (selectedNode.value) deleteSelectedNode();
      else if (selectedRelation.value) deleteSelectedRelation();
      else if (selectedExistingNode.value) deleteSelectedExistingNode();
      else deleteSelectedExistingRelation();
    }

    watch(
      () => [
        props.modelValue.nodes,
        props.modelValue.relations,
        props.existingNodes,
        props.existingRelations,
      ],
      () => {
        if (pendingSourceId.value
            && !availableNodes(props.modelValue)
              .some(node => node.nodeId === pendingSourceId.value)) {
          pendingSourceId.value = '';
        }
        const selectionExists = {
          node: () => props.modelValue.nodes.some(node => node.id === selectedId.value),
          relation: () =>
            props.modelValue.relations.some(relation => relation.id === selectedId.value),
          'existing-node': () =>
            props.existingNodes.some(node => node.nodeId === selectedId.value),
          'existing-relation': () =>
            props.existingRelations.some(relation => relation.relationId === selectedId.value),
        }[selectedKind.value]?.() || false;
        if (!selectionExists) {
          selectedKind.value = 'node';
          selectedId.value = props.modelValue.nodes[0]?.id || '';
        }
      },
      { deep: true }
    );

    onMounted(() => window.addEventListener('keydown', handleDesignerKeydown));
    onUnmounted(() => window.removeEventListener('keydown', handleDesignerKeydown));

    return {
      t,
      nodeTypeNames,
      relationTypeNames,
      selectedKind,
      selectedId,
      selectedNode,
      selectedRelation,
      selectedRelationTypeOptions,
      selectedExistingNode,
      selectedExistingRelation,
      visibleExistingNodes,
      visibleExistingRelations,
      selectedNodeType,
      selectedNodeDefinitions,
      selectedRelationDefinitions,
      sourceNodeOptions,
      targetNodeOptions,
      sourceUsesExternalId,
      targetUsesExternalId,
      topologyData,
      connectMode,
      pendingSourceId,
      pendingSourceNode,
      connectableNodeCount,
      connectionError,
      formatExistingProperties,
      addNode,
      addRelation,
      selectNode,
      selectRelation,
      selectExistingNode,
      selectExistingRelation,
      selectTopologyElement,
      connectTopologyNodes,
      editExistingNode,
      editExistingRelation,
      updateNodeId,
      setNodeType,
      updateNodePropertyValues,
      updateRelationField,
      updateRelationPropertyValues,
      deleteSelectedNode,
      deleteSelectedRelation,
      deleteSelectedExistingNode,
      deleteSelectedExistingRelation,
      toggleConnectMode,
    };
  },
  template: `
    <div class="graph-data-designer">
      <div class="graph-data-workbench">
        <aside class="graph-data-outline">
          <div class="graph-designer-section-header">
            <strong>{{ t('graphDataOutline') }}</strong>
          </div>
          <div class="graph-outline-actions">
            <button class="btn btn-secondary btn-sm" @click="addNode">
              {{ t('graphDataAddNode') }}
            </button>
            <button class="btn btn-secondary btn-sm"
                    :disabled="relationTypeNames.length === 0"
                    @click="addRelation">
              {{ t('graphDataAddRelation') }}
            </button>
          </div>

          <div class="graph-outline-group">
            <div class="graph-outline-title">
              <span>{{ t('graphDataNodeInstances') }}</span>
              <span class="tag tag-gold">{{ modelValue.nodes.length }}</span>
            </div>
            <button v-for="node in modelValue.nodes"
                    :key="node.id"
                    :class="['graph-outline-item', { active: selectedKind === 'node' && selectedId === node.id }]"
                    @click="selectNode(node.id)">
              <span class="graph-outline-node-dot"></span>
              <span>{{ node.nodeId || t('graphDataUnnamedNode') }}</span>
            </button>
          </div>

          <div class="graph-outline-group">
            <div class="graph-outline-title">
              <span>{{ t('graphDataRelationInstances') }}</span>
              <span class="tag tag-rose">{{ modelValue.relations.length }}</span>
            </div>
            <button v-for="relation in modelValue.relations"
                    :key="relation.id"
                    :class="['graph-outline-item', { active: selectedKind === 'relation' && selectedId === relation.id }]"
                    @click="selectRelation(relation.id)">
              <span class="graph-outline-relation-line"></span>
              <span>{{ relation.relationId || t('graphDataUnnamedRelation') }}</span>
            </button>
          </div>

          <div v-if="existingNodes.length || existingRelations.length"
               class="graph-outline-existing-divider">
            {{ t('graphDataExistingBase') }}
          </div>
          <div v-if="visibleExistingNodes.length" class="graph-outline-group">
            <div class="graph-outline-title">
              <span>{{ t('graphDataExistingNodes') }}</span>
              <span class="tag tag-dusk">{{ visibleExistingNodes.length }}</span>
            </div>
            <button v-for="node in visibleExistingNodes"
                    :key="'existing-node:' + node.nodeId"
                    :class="['graph-outline-item', {
                      active: selectedKind === 'existing-node' && selectedId === node.nodeId
                    }]"
                    @click="selectExistingNode(node.nodeId)">
              <span class="graph-outline-node-dot graph-outline-existing-mark"></span>
              <span>{{ node.properties?.name || node.nodeId }}</span>
            </button>
          </div>
          <div v-if="visibleExistingRelations.length" class="graph-outline-group">
            <div class="graph-outline-title">
              <span>{{ t('graphDataExistingRelations') }}</span>
              <span class="tag tag-dusk">{{ visibleExistingRelations.length }}</span>
            </div>
            <button v-for="relation in visibleExistingRelations"
                    :key="'existing-relation:' + relation.relationId"
                    :class="['graph-outline-item', {
                      active: selectedKind === 'existing-relation'
                        && selectedId === relation.relationId
                    }]"
                    @click="selectExistingRelation(relation.relationId)">
              <span class="graph-outline-relation-line graph-outline-existing-mark"></span>
              <span>{{ relation.relationId }}</span>
            </button>
          </div>
        </aside>

        <section class="graph-data-preview">
          <div class="graph-designer-section-header">
            <div>
              <strong>{{ t('graphDataPreview') }}</strong>
              <span class="text-xs text-ash">{{ t('graphDataPreviewHint') }}</span>
            </div>
            <button :class="['btn', 'btn-sm', connectMode ? 'btn-primary' : 'btn-secondary']"
                    :disabled="connectableNodeCount < 2 || relationTypeNames.length === 0"
                    @click="toggleConnectMode">
              {{ connectMode ? t('graphDataCancelConnect') : t('graphDataCanvasConnect') }}
            </button>
          </div>
          <div v-if="connectMode" class="graph-data-connect-status">
            <template v-if="pendingSourceNode">
              {{ t('graphDataConnectTargetHint') }}：<strong>{{ pendingSourceNode.nodeId }}</strong>
            </template>
            <template v-else>{{ t('graphDataConnectSourceHint') }}</template>
          </div>
          <div v-if="connectionError" class="graph-data-connect-error">{{ connectionError }}</div>
          <div v-if="existingNodes.length || existingRelations.length"
               class="graph-data-canvas-legend">
            <span class="tag tag-dusk">
              {{ t('graphDataExistingBase') }}：
              {{ existingNodes.length }} {{ t('graphNodes') }} /
              {{ existingRelations.length }} {{ t('graphRelations') }}
            </span>
            <span class="tag tag-gold">
              {{ t('graphDataPendingOverlay') }}：
              {{ modelValue.nodes.length }} {{ t('graphNodes') }} /
              {{ modelValue.relations.length }} {{ t('graphRelations') }}
            </span>
          </div>
          <div v-if="existingDataLoading || existingDataHasMore
                     || existingNodes.length || existingRelations.length"
               class="graph-data-existing-status">
            <span v-if="existingDataLoading" class="text-xs text-ash">
              {{ t('graphDataLoadingExistingData') }}
            </span>
            <button v-else-if="existingDataHasMore"
                    type="button"
                    class="btn btn-ghost btn-sm"
                    @click="$emit('load-more-existing-data')">
              {{ t('graphDataLoadMoreExistingData') }}
            </button>
            <span v-else class="text-xs text-ash">
              {{ t('graphDataExistingDataLoaded') }}：
              {{ existingNodes.length }} / {{ existingRelations.length }}
            </span>
          </div>
          <GraphTopology
            :nodes="topologyData.nodes"
            :relations="topologyData.relations"
            :connection-mode="connectMode"
            @select="selectTopologyElement"
            @connect="connectTopologyNodes"
          />
        </section>

        <aside class="graph-data-inspector">
          <template v-if="selectedNode">
            <div class="graph-designer-section-header">
              <div>
                <span class="tag tag-gold">{{ t('graphDataNodeInstance') }}</span>
                <strong>{{ selectedNode.nodeId || t('graphDataUnnamedNode') }}</strong>
              </div>
              <button class="btn btn-danger btn-sm" @click="deleteSelectedNode">
                {{ selectedNode.persisted ? t('graphDataCancelEdit') : t('delete') }}
              </button>
            </div>
            <div class="input-group">
              <label class="input-label">nodeId</label>
              <input class="input"
                     :value="selectedNode.nodeId"
                     :disabled="Boolean(selectedNode.persisted)"
                     @input="updateNodeId($event.target.value)" />
              <span class="text-xs text-ash">
                {{ selectedNode.persisted
                  ? t('graphDataExistingIdentityLocked')
                  : t('graphDataNodeIdHint') }}
              </span>
            </div>
            <div>
              <label class="input-label">{{ t('graphDataNodeTypes') }}</label>
              <select class="input"
                      :value="selectedNodeType"
                      :disabled="Boolean(selectedNode.persisted)"
                      @change="setNodeType($event.target.value)">
                <option v-for="nodeTypeName in nodeTypeNames"
                        :key="nodeTypeName"
                        :value="nodeTypeName">
                  {{ nodeTypeName }}
                </option>
              </select>
              <span class="text-xs text-ash">{{ t('graphDataSingleTypeHint') }}</span>
            </div>
            <GraphDataPropertyEditor
              :definitions="selectedNodeDefinitions"
              :values="selectedNode.propertyValues"
              @update:values="updateNodePropertyValues"
            />
          </template>

          <template v-else-if="selectedRelation">
            <div class="graph-designer-section-header">
              <div>
                <span class="tag tag-rose">{{ t('graphDataRelationInstance') }}</span>
                <strong>{{ selectedRelation.relationId || t('graphDataUnnamedRelation') }}</strong>
              </div>
              <button class="btn btn-danger btn-sm" @click="deleteSelectedRelation">
                {{ selectedRelation.persisted ? t('graphDataCancelEdit') : t('delete') }}
              </button>
            </div>
            <div class="input-group">
              <label class="input-label">relationId</label>
              <input class="input"
                     :value="selectedRelation.relationId"
                     :disabled="Boolean(selectedRelation.persisted)"
                     @input="updateRelationField('relationId', $event.target.value)" />
              <span v-if="selectedRelation.persisted" class="text-xs text-ash">
                {{ t('graphDataExistingRelationIdLocked') }}
              </span>
            </div>
            <div class="input-group">
              <label class="input-label">{{ t('graphRelationType') }}</label>
              <select class="input"
                      :value="selectedRelation.relationType"
                      @change="updateRelationField('relationType', $event.target.value)">
                <option v-for="relationTypeName in selectedRelationTypeOptions"
                        :key="relationTypeName"
                        :value="relationTypeName">
                  {{ relationTypeName }}
                </option>
              </select>
            </div>
            <div class="graph-relation-endpoints">
              <div class="input-group">
                <label class="input-label">{{ t('graphSourceNode') }}</label>
                <select class="input"
                        :value="selectedRelation.sourceNodeId"
                        @change="updateRelationField('sourceNodeId', $event.target.value)">
                  <option value="">{{ t('graphDataManualNodeId') }}</option>
                  <option v-for="node in sourceNodeOptions"
                          :key="node.origin + ':' + node.nodeId"
                          :value="node.nodeId">
                    {{ node.nodeId }} · {{ node.labels.join(', ') }} ·
                    {{ node.origin === 'existing' ? t('graphDataExistingNode') : t('graphDataBatchNode') }}
                  </option>
                  <option v-if="sourceUsesExternalId" :value="selectedRelation.sourceNodeId">
                    {{ t('graphDataExistingNode') }} · {{ selectedRelation.sourceNodeId }}
                  </option>
                </select>
                <input class="input"
                       v-if="sourceUsesExternalId || !selectedRelation.sourceNodeId"
                       :value="selectedRelation.sourceNodeId"
                       :placeholder="t('graphDataExistingNodeIdPlaceholder')"
                       @input="updateRelationField('sourceNodeId', $event.target.value)" />
              </div>
              <div class="input-group">
                <label class="input-label">{{ t('graphTargetNode') }}</label>
                <select class="input"
                        :value="selectedRelation.targetNodeId"
                        @change="updateRelationField('targetNodeId', $event.target.value)">
                  <option value="">{{ t('graphDataManualNodeId') }}</option>
                  <option v-for="node in targetNodeOptions"
                          :key="node.origin + ':' + node.nodeId"
                          :value="node.nodeId">
                    {{ node.nodeId }} · {{ node.labels.join(', ') }} ·
                    {{ node.origin === 'existing' ? t('graphDataExistingNode') : t('graphDataBatchNode') }}
                  </option>
                  <option v-if="targetUsesExternalId" :value="selectedRelation.targetNodeId">
                    {{ t('graphDataExistingNode') }} · {{ selectedRelation.targetNodeId }}
                  </option>
                </select>
                <input class="input"
                       v-if="targetUsesExternalId || !selectedRelation.targetNodeId"
                       :value="selectedRelation.targetNodeId"
                       :placeholder="t('graphDataExistingNodeIdPlaceholder')"
                       @input="updateRelationField('targetNodeId', $event.target.value)" />
              </div>
            </div>
            <div class="text-xs text-ash">{{ t('graphDataEndpointHint') }}</div>
            <GraphDataPropertyEditor
              :definitions="selectedRelationDefinitions"
              :values="selectedRelation.propertyValues"
              @update:values="updateRelationPropertyValues"
            />
          </template>

          <template v-else-if="selectedExistingNode">
            <div class="graph-designer-section-header">
              <div>
                <span class="tag tag-dusk">{{ t('graphDataExistingNode') }}</span>
                <strong>{{ selectedExistingNode.properties?.name || selectedExistingNode.nodeId }}</strong>
              </div>
            </div>
            <dl class="graph-detail-list">
              <dt>nodeId</dt>
              <dd>{{ selectedExistingNode.nodeId }}</dd>
              <dt>{{ t('graphLabels') }}</dt>
              <dd>{{ Array.from(selectedExistingNode.labels).join(', ') }}</dd>
            </dl>
            <div class="text-xs text-ash">{{ t('graphProperties') }}</div>
            <pre class="graph-property-view">{{ formatExistingProperties(selectedExistingNode.properties) }}</pre>
            <div class="graph-existing-actions">
              <button class="btn btn-primary btn-sm"
                      :disabled="existingOperationPending"
                      @click="editExistingNode">
                {{ t('graphDataEditExisting') }}
              </button>
              <button class="btn btn-danger btn-sm"
                      :disabled="existingOperationPending"
                      @click="deleteSelectedExistingNode">
                {{ t('delete') }}
              </button>
            </div>
          </template>

          <template v-else-if="selectedExistingRelation">
            <div class="graph-designer-section-header">
              <div>
                <span class="tag tag-dusk">{{ t('graphDataExistingRelation') }}</span>
                <strong>{{ selectedExistingRelation.relationId }}</strong>
              </div>
            </div>
            <dl class="graph-detail-list">
              <dt>{{ t('graphRelationType') }}</dt>
              <dd>{{ selectedExistingRelation.relationType }}</dd>
              <dt>{{ t('graphSourceNode') }}</dt>
              <dd>{{ selectedExistingRelation.sourceNodeId }}</dd>
              <dt>{{ t('graphTargetNode') }}</dt>
              <dd>{{ selectedExistingRelation.targetNodeId }}</dd>
            </dl>
            <div class="text-xs text-ash">{{ t('graphProperties') }}</div>
            <pre class="graph-property-view">{{ formatExistingProperties(selectedExistingRelation.properties) }}</pre>
            <div class="graph-existing-actions">
              <button class="btn btn-primary btn-sm"
                      :disabled="existingOperationPending"
                      @click="editExistingRelation">
                {{ t('graphDataEditExisting') }}
              </button>
              <button class="btn btn-danger btn-sm"
                      :disabled="existingOperationPending"
                      @click="deleteSelectedExistingRelation">
                {{ t('delete') }}
              </button>
            </div>
          </template>

          <div v-else class="graph-designer-empty-inspector">
            {{ t('graphDataSelectElement') }}
          </div>
          <div class="graph-data-keyboard-hint">{{ t('graphDataKeyboardDeleteHint') }}</div>
        </aside>
      </div>
    </div>
  `,
};

function graphDataPropertiesToPreview(definitions, propertyValues) {
  return Object.entries(definitions).reduce((result, [name, definition]) => {
    const state = propertyValues[name];
    if (!state || (!definition.required && !state.enabled)) return result;
    try {
      result[name] = parseGraphDataPropertyValue(state.rawValue, definition.type);
    } catch (e) {
      result[name] = state.rawValue;
    }
    return result;
  }, {});
}

const GraphBrowsePage = {
  components: { GraphTopology },
  setup() {
    const Icons = inject('Icons');
    const t = inject('t');
    const status = ref({ provider: 'none', enabled: false, schemaCount: 0 });
    const schemas = ref([]);
    const graphSpaces = ref([]);
    const graphSpacePageInfo = ref({ limit: 20, nextCursor: '', hasMore: false });
    const selectedGraphSpaceKey = ref('');
    const subjectIdsText = ref('');
    const nodeNameText = ref('');
    const selectedRelationTypes = ref([]);
    const queryDepth = ref(1);
    const queryLimit = ref(100);
    const topologyNodes = ref([]);
    const topologyRelations = ref([]);
    const selectedElement = ref(null);
    const loading = ref(false);
    const loadingMore = ref(false);
    const querying = ref(false);
    const renderingTopology = ref(false);
    const expandingNodeId = ref('');
    const error = ref('');
    const queryNotice = ref('');
    const graphLoadProgress = ref(createGraphLoadProgress());
    let topologyQueryVersion = 0;

    function createGraphLoadProgress() {
      return {
        phase: 'idle',
        loadedNodes: 0,
        totalNodes: 0,
        loadedRelations: 0,
        totalRelations: null,
      };
    }

    function graphSpaceKey(space) {
      return JSON.stringify([space.graphId, space.schemaId]);
    }

    const selectedGraphSpace = computed(() =>
      graphSpaces.value.find(space => graphSpaceKey(space) === selectedGraphSpaceKey.value) || null
    );
    const selectedSchema = computed(() =>
      schemas.value.find(schema => schema.schemaId === selectedGraphSpace.value?.schemaId) || null
    );
    const relationTypeNames = computed(() => Object.keys(selectedSchema.value?.relationTypes || {}));
    const availableDepths = computed(() => {
      const maxDepth = Math.max(1, selectedSchema.value?.maxDepth || 1);
      return Array.from({ length: maxDepth }, (_, index) => index + 1);
    });
    const subjectIds = computed(() => Array.from(new Set(
      subjectIdsText.value
        .split(/[\n,，]+/)
        .map(value => value.trim())
        .filter(Boolean)
    )));
    const canQuery = computed(() =>
      status.value.enabled
      && Boolean(selectedGraphSpace.value)
      && !querying.value
      && !renderingTopology.value
      && !expandingNodeId.value
    );
    const isGraphWideQuery = computed(() =>
      subjectIds.value.length === 0 && !nodeNameText.value.trim()
    );
    const isFullGraphQuery = computed(() =>
      isGraphWideQuery.value && selectedRelationTypes.value.length === 0
    );
    const queryButtonText = computed(() =>
      isGraphWideQuery.value
        ? (isFullGraphQuery.value ? t('graphBrowseAll') : t('graphBrowseFiltered'))
        : t('graphQuery')
    );
    const generatingGraph = computed(() =>
      querying.value
      || renderingTopology.value
      || Boolean(expandingNodeId.value)
    );
    const graphProgressPercent = computed(() => {
      const progress = graphLoadProgress.value;
      if (progress.phase === 'rendering') return 100;
      if (progress.phase !== 'loading' || progress.totalRelations == null) return null;
      const total = progress.totalNodes + progress.totalRelations;
      if (total <= 0) return 100;
      return Math.min(
        100,
        Math.round(((progress.loadedNodes + progress.loadedRelations) / total) * 100)
      );
    });
    const graphProgressText = computed(() => {
      const progress = graphLoadProgress.value;
      if (progress.phase === 'rendering') {
        return t('graphRenderingTopology');
      }
      if (progress.phase !== 'loading') {
        return t('graphGeneratingHint');
      }
      const relationTotal = progress.totalRelations == null ? '?' : progress.totalRelations;
      return `${t('graphNodes')} ${progress.loadedNodes}/${progress.totalNodes}`
        + ` · ${t('graphRelations')} ${progress.loadedRelations}/${relationTotal}`;
    });

    function formatProperties(properties) {
      return JSON.stringify(properties || {}, null, 2);
    }

    async function ensureSchema(schemaId) {
      if (schemas.value.some(schema => schema.schemaId === schemaId)) return;
      const schema = await CyreneAPI.getGraphSchema(schemaId);
      schemas.value = [...schemas.value, schema];
    }

    async function loadGraphSpaces({ append = false, cursor = '' } = {}) {
      const page = requirePageResponse(
        await CyreneAPI.listGraphSpaces({
          limit: graphSpacePageInfo.value.limit,
          cursor,
        }),
        space => typeof space?.graphId === 'string'
          && typeof space?.schemaId === 'string',
        t('graphInvalidPageResponse')
      );
      if (append) {
        const spacesByKey = new Map(graphSpaces.value.map(space => [graphSpaceKey(space), space]));
        page.items.forEach(space => spacesByKey.set(graphSpaceKey(space), space));
        graphSpaces.value = Array.from(spacesByKey.values());
      } else {
        graphSpaces.value = page.items;
      }
      graphSpacePageInfo.value = page.pageInfo;
      if (!graphSpaces.value.some(space => graphSpaceKey(space) === selectedGraphSpaceKey.value)) {
        selectedGraphSpaceKey.value = graphSpaces.value.length > 0
          ? graphSpaceKey(graphSpaces.value[0])
          : '';
      }
    }

    async function prepareSelectedGraphSpace() {
      topologyQueryVersion += 1;
      error.value = '';
      queryNotice.value = '';
      topologyNodes.value = [];
      topologyRelations.value = [];
      selectedElement.value = null;
      selectedRelationTypes.value = [];
      querying.value = false;
      expandingNodeId.value = '';
      renderingTopology.value = false;
      graphLoadProgress.value = createGraphLoadProgress();
      if (!selectedGraphSpace.value) return;
      try {
        await ensureSchema(selectedGraphSpace.value.schemaId);
        queryDepth.value = selectedSchema.value?.defaultMaxDepth || 1;
      } catch (e) {
        error.value = e.message;
      }
    }

    async function refreshGraphSpaces() {
      loading.value = true;
      error.value = '';
      try {
        const graphStatus = await CyreneAPI.getGraphStatus();
        status.value = {
          provider: graphStatus.provider,
          enabled: graphStatus.enabled,
          schemaCount: graphStatus.schemaCount,
        };
        await loadGraphSpaces();
        await prepareSelectedGraphSpace();
      } catch (e) {
        error.value = e.message;
      } finally {
        loading.value = false;
      }
    }

    async function loadMoreGraphSpaces() {
      if (!graphSpacePageInfo.value.hasMore || loadingMore.value) return;
      loadingMore.value = true;
      error.value = '';
      try {
        await loadGraphSpaces({
          append: true,
          cursor: graphSpacePageInfo.value.nextCursor,
        });
      } catch (e) {
        error.value = e.message;
      } finally {
        loadingMore.value = false;
      }
    }

    async function executeGraphQuery(anchorIds, maxDepth) {
      const space = selectedGraphSpace.value;
      if (!space) throw new Error(t('graphSelectSpaceRequired'));
      const result = await CyreneAPI.queryGraph({
        graphId: space.graphId,
        schemaId: space.schemaId,
        subjectIds: anchorIds,
        relationTypes: selectedRelationTypes.value,
        maxDepth,
        limit: Number(queryLimit.value),
      });
      if (!Array.isArray(result.nodes) || !Array.isArray(result.relations)) {
        throw new Error(t('graphInvalidQueryResponse'));
      }
      return result;
    }

    function mergeTopologyData(nodes, relations, append) {
      const nodesById = new Map(
        (append ? topologyNodes.value : []).map(node => [node.nodeId, node])
      );
      nodes.forEach(node => nodesById.set(node.nodeId, node));
      const relationsById = new Map(
        (append ? topologyRelations.value : [])
          .map(relation => [relation.relationId, relation])
      );
      relations.forEach(relation => relationsById.set(relation.relationId, relation));
      relationsById.forEach(relation => {
        [relation.sourceNodeId, relation.targetNodeId].forEach(nodeId => {
          if (!nodesById.has(nodeId)) {
            nodesById.set(nodeId, {
              nodeId,
              labels: ['Unloaded'],
              properties: { name: nodeId },
              placeholder: true,
            });
          }
        });
      });
      renderingTopology.value = true;
      topologyNodes.value = Array.from(nodesById.values());
      topologyRelations.value = Array.from(relationsById.values());
    }

    async function loadAllGraphNodePages(space, queryVersion, requestedSpaceKey) {
      const nodes = [];
      let cursor = '';
      let hasMore = false;
      do {
        const page = requireGraphNodePage(await CyreneAPI.listGraphNodes({
          graphId: space.graphId,
          schemaId: space.schemaId,
          limit: Number(queryLimit.value),
          cursor,
        }), t);
        if (queryVersion !== topologyQueryVersion
            || requestedSpaceKey !== selectedGraphSpaceKey.value) {
          return null;
        }
        nodes.push(...page.items);
        graphLoadProgress.value.loadedNodes = nodes.length;
        cursor = page.pageInfo.nextCursor;
        hasMore = page.pageInfo.hasMore;
      } while (hasMore);
      return nodes;
    }

    async function loadAllGraphRelationPages(
      space,
      relationType,
      queryVersion,
      requestedSpaceKey
    ) {
      const relations = [];
      let cursor = '';
      let hasMore = false;
      do {
        const page = requireGraphRelationPage(await CyreneAPI.listGraphRelations({
          graphId: space.graphId,
          schemaId: space.schemaId,
          relationType,
          limit: Number(queryLimit.value),
          cursor,
        }), t);
        if (queryVersion !== topologyQueryVersion
            || requestedSpaceKey !== selectedGraphSpaceKey.value) {
          return null;
        }
        relations.push(...page.items);
        graphLoadProgress.value.loadedRelations += page.items.length;
        cursor = page.pageInfo.nextCursor;
        hasMore = page.pageInfo.hasMore;
      } while (hasMore);
      return relations;
    }

    async function loadFullGraphData(queryVersion) {
      const space = selectedGraphSpace.value;
      if (!space) throw new Error(t('graphSelectSpaceRequired'));
      const requestedSpaceKey = selectedGraphSpaceKey.value;
      const relationTypes = selectedRelationTypes.value.length > 0
        ? [...selectedRelationTypes.value]
        : [''];
      const expectedNodeCount = Number(space.nodeCount) || 0;
      const expectedRelationCount = selectedRelationTypes.value.length === 0
        ? Number(space.relationCount) || 0
        : null;
      graphLoadProgress.value = {
        phase: 'loading',
        loadedNodes: 0,
        totalNodes: expectedNodeCount,
        loadedRelations: 0,
        totalRelations: expectedRelationCount,
      };
      const [nodes, ...relationGroups] = await Promise.all([
        loadAllGraphNodePages(space, queryVersion, requestedSpaceKey),
        ...relationTypes.map(relationType => loadAllGraphRelationPages(
          space,
          relationType,
          queryVersion,
          requestedSpaceKey
        )),
      ]);
      if (nodes == null || relationGroups.some(relations => relations == null)) return;
      const relations = relationGroups.flat();
      graphLoadProgress.value.loadedNodes = nodes.length;
      graphLoadProgress.value.loadedRelations = relations.length;
      graphLoadProgress.value.phase = 'rendering';
      mergeTopologyData(nodes, relations, false);
      if (expectedRelationCount == null) {
        queryNotice.value = `${t('graphFilteredGraphLoaded')} `
          + `${nodes.length} ${t('graphNodes')} · `
          + `${relations.length} ${t('graphRelations')}`;
      } else if (nodes.length === expectedNodeCount
          && relations.length === expectedRelationCount) {
        queryNotice.value = `${t('graphFullGraphVerified')} `
          + `${nodes.length}/${expectedNodeCount} ${t('graphNodes')} · `
          + `${relations.length}/${expectedRelationCount} ${t('graphRelations')}`;
      } else {
        queryNotice.value = `${t('graphFullGraphIncomplete')} `
          + `${nodes.length}/${expectedNodeCount} ${t('graphNodes')} · `
          + `${relations.length}/${expectedRelationCount} ${t('graphRelations')}`;
      }
    }

    async function resolveNameAnchorIds(name, queryVersion) {
      const space = selectedGraphSpace.value;
      if (!space) throw new Error(t('graphSelectSpaceRequired'));
      const page = await CyreneAPI.listGraphNodes({
        graphId: space.graphId,
        schemaId: space.schemaId,
        name,
        limit: Number(queryLimit.value),
      });
      if (queryVersion !== topologyQueryVersion) return null;
      requireGraphNodePage(page, t);
      if (page.pageInfo.hasMore) {
        queryNotice.value = t('graphNameMatchesLimited');
      }
      return page.items.map(node => node.nodeId);
    }

    async function queryGraphData() {
      if (!canQuery.value) return;
      const queryVersion = ++topologyQueryVersion;
      querying.value = true;
      error.value = '';
      queryNotice.value = '';
      selectedElement.value = null;
      try {
        const name = nodeNameText.value.trim();
        if (subjectIds.value.length === 0 && !name) {
          await loadFullGraphData(queryVersion);
          return;
        }
        const anchorIds = new Set(subjectIds.value);
        if (name) {
          const nameAnchorIds = await resolveNameAnchorIds(name, queryVersion);
          if (nameAnchorIds == null) return;
          nameAnchorIds.forEach(nodeId => anchorIds.add(nodeId));
        }
        if (queryVersion !== topologyQueryVersion) return;
        if (anchorIds.size === 0) {
          topologyNodes.value = [];
          topologyRelations.value = [];
          queryNotice.value = t('graphNameNoMatches');
          return;
        }
        const result = await executeGraphQuery(
          Array.from(anchorIds),
          Number(queryDepth.value)
        );
        if (queryVersion !== topologyQueryVersion) return;
        renderingTopology.value = true;
        topologyNodes.value = result.nodes;
        topologyRelations.value = result.relations;
      } catch (e) {
        error.value = e.message;
        graphLoadProgress.value = createGraphLoadProgress();
      } finally {
        if (queryVersion === topologyQueryVersion) {
          querying.value = false;
        }
      }
    }

    async function expandNode(nodeId) {
      if (!nodeId || expandingNodeId.value) return;
      expandingNodeId.value = nodeId;
      error.value = '';
      try {
        const result = await executeGraphQuery([nodeId], 1);
        mergeTopologyData(result.nodes, result.relations, true);
      } catch (e) {
        error.value = e.message;
      } finally {
        expandingNodeId.value = '';
      }
    }

    function finishTopologyRender() {
      renderingTopology.value = false;
      graphLoadProgress.value = createGraphLoadProgress();
    }

    onMounted(refreshGraphSpaces);

    return {
      Icons, t, status, graphSpaces, graphSpacePageInfo, selectedGraphSpaceKey,
      selectedGraphSpace, relationTypeNames, selectedRelationTypes, subjectIdsText,
      nodeNameText,
      queryDepth, queryLimit, availableDepths, topologyNodes, topologyRelations,
      selectedElement, loading, loadingMore, querying,
      renderingTopology, generatingGraph, expandingNodeId, error, queryNotice,
      queryButtonText, graphProgressPercent, graphProgressText,
      canQuery, graphSpaceKey, formatProperties, prepareSelectedGraphSpace,
      refreshGraphSpaces, loadMoreGraphSpaces, queryGraphData,
      expandNode, finishTopologyRender,
    };
  },
  template: `
    <div class="graph-page">
      <div class="card card-gold mb-4">
        <div class="card-header graph-card-header">
          <div>
            <div class="card-title">{{ t('graphRuntimeStatus') }}</div>
            <div class="text-xs text-ash mt-2">{{ t('graphBrowseStatusHint') }}</div>
          </div>
          <button class="btn btn-ghost btn-sm" @click="refreshGraphSpaces" :disabled="loading">
            <span v-html="Icons.refresh" style="width:14px;height:14px;"></span>
            {{ t('reload') }}
          </button>
        </div>
        <div class="card-body">
          <div v-if="loading" style="text-align:center;padding:1rem;">
            <div class="loading-dots"><span></span><span></span><span></span></div>
          </div>
          <div v-else class="graph-status-grid">
            <div class="graph-status-item">
              <span class="text-xs text-ash">{{ t('graphProvider') }}</span>
              <strong>{{ status.provider }}</strong>
            </div>
            <div class="graph-status-item">
              <span class="text-xs text-ash">{{ t('status') }}</span>
              <span :class="['tag', status.enabled ? 'tag-gold' : 'tag-dusk']">
                {{ status.enabled ? t('enabled') : t('disabled') }}
              </span>
            </div>
            <div class="graph-status-item">
              <span class="text-xs text-ash">{{ t('graphSpaceCountOnPage') }}</span>
              <strong>{{ graphSpaces.length }}</strong>
            </div>
          </div>
        </div>
      </div>

      <div class="graph-browser-layout">
        <div class="card graph-query-card">
          <div class="card-header">
            <div>
              <div class="card-title">{{ t('graphNeighborhoodQuery') }}</div>
              <div class="text-xs text-ash mt-2">{{ t('graphNeighborhoodHint') }}</div>
            </div>
          </div>
          <div class="card-body">
            <div class="input-group">
              <label class="input-label">{{ t('graphSpace') }}</label>
              <select class="input"
                      v-model="selectedGraphSpaceKey"
                      :disabled="graphSpaces.length === 0"
                      @change="prepareSelectedGraphSpace">
                <option value="" disabled>{{ t('graphSelectSpacePlaceholder') }}</option>
                <option v-for="space in graphSpaces"
                        :key="graphSpaceKey(space)"
                        :value="graphSpaceKey(space)">
                  {{ space.graphId }} · {{ space.schemaId }}
                </option>
              </select>
            </div>

            <div v-if="selectedGraphSpace" class="graph-space-summary mt-4">
              <div>
                <span>{{ t('graphNodes') }}</span>
                <strong>{{ selectedGraphSpace.nodeCount }}</strong>
              </div>
              <div>
                <span>{{ t('graphRelations') }}</span>
                <strong>{{ selectedGraphSpace.relationCount }}</strong>
              </div>
            </div>

            <div class="input-group mt-4">
              <label class="input-label">{{ t('graphSubjectIds') }}</label>
              <textarea class="input graph-subject-input"
                        v-model="subjectIdsText"
                        :placeholder="t('graphSubjectIdsPlaceholder')"></textarea>
              <div class="text-xs text-ash">{{ t('graphSubjectIdsHint') }}</div>
            </div>

            <div class="input-group mt-4">
              <label class="input-label">{{ t('graphNodeName') }}</label>
              <input class="input"
                     v-model="nodeNameText"
                     :placeholder="t('graphNodeNamePlaceholder')" />
              <div class="text-xs text-ash">{{ t('graphNodeNameHint') }}</div>
            </div>

            <div v-if="relationTypeNames.length > 0" class="input-group mt-4">
              <label class="input-label">{{ t('graphRelationFilter') }}</label>
              <div class="graph-filter-list">
                <label v-for="relationType in relationTypeNames"
                       :key="relationType"
                       class="graph-filter-option">
                  <input type="checkbox" :value="relationType" v-model="selectedRelationTypes" />
                  <span>{{ relationType }}</span>
                </label>
              </div>
              <div class="text-xs text-ash">{{ t('graphRelationFilterHint') }}</div>
            </div>

            <div class="graph-query-options mt-4">
              <div class="input-group">
                <label class="input-label">{{ t('graphDepth') }}</label>
                <select class="input" v-model.number="queryDepth">
                  <option v-for="depth in availableDepths" :key="depth" :value="depth">
                    {{ depth }}
                  </option>
                </select>
              </div>
              <div class="input-group">
                <label class="input-label">{{ t('graphResultLimit') }}</label>
                <select class="input" v-model.number="queryLimit">
                  <option :value="25">25</option>
                  <option :value="50">50</option>
                  <option :value="100">100</option>
                  <option :value="200">200</option>
                </select>
              </div>
            </div>

            <div v-if="!status.enabled" class="graph-empty-hint mt-4">
              {{ t('graphProviderDisabledHint') }}
            </div>
            <div v-else-if="graphSpaces.length === 0 && !loading" class="graph-empty-hint mt-4">
              {{ t('graphNoSpaces') }}
            </div>
            <div v-if="error" class="text-sm mt-4" style="color:var(--error);">{{ error }}</div>
            <div v-if="queryNotice" class="graph-query-notice mt-4">{{ queryNotice }}</div>
            <button class="btn btn-primary w-full mt-4"
                    @click="queryGraphData"
                    :disabled="!canQuery">
              {{ querying ? t('graphQuerying') : queryButtonText }}
            </button>
            <button v-if="graphSpacePageInfo.hasMore"
                    class="btn btn-ghost btn-sm w-full mt-4"
                    @click="loadMoreGraphSpaces"
                    :disabled="loadingMore">
              {{ loadingMore ? t('graphLoadingMore') : t('graphLoadMoreSpaces') }}
            </button>
          </div>
        </div>

        <div class="card graph-visual-card">
          <div class="card-header graph-card-header">
            <div>
              <div class="card-title">{{ t('graphTopology') }}</div>
              <div class="text-xs text-ash mt-2">{{ t('graphTopologyHint') }}</div>
            </div>
            <div class="graph-chip-list">
              <span class="tag tag-gold">{{ topologyNodes.length }} {{ t('graphNodes') }}</span>
              <span class="tag tag-rose">{{ topologyRelations.length }} {{ t('graphRelations') }}</span>
            </div>
          </div>
          <div class="card-body graph-visual-body">
            <div v-if="generatingGraph"
                 class="graph-generation-progress"
                 role="status"
                 aria-live="polite">
              <div class="graph-generation-progress-copy">
                <strong>{{ t('graphGenerating') }}</strong>
                <span>{{ graphProgressText }}</span>
              </div>
              <div class="graph-generation-progress-track" aria-hidden="true">
                <span v-if="graphProgressPercent != null"
                      class="is-determinate"
                      :style="{ width: graphProgressPercent + '%' }"></span>
                <span v-else></span>
              </div>
            </div>
            <GraphTopology
              :nodes="topologyNodes"
              :relations="topologyRelations"
              :loading="querying"
              @select="selectedElement = $event"
              @rendered="finishTopologyRender"
            />

            <aside class="graph-detail-panel">
              <template v-if="selectedElement">
                <div class="flex justify-between items-center gap-2">
                  <span :class="['tag', selectedElement.kind === 'node' ? 'tag-gold' : 'tag-rose']">
                    {{ selectedElement.kind === 'node' ? t('graphNode') : t('graphRelation') }}
                  </span>
                  <button v-if="selectedElement.kind === 'node'"
                          class="btn btn-ghost btn-sm"
                          @click="expandNode(selectedElement.item.nodeId)"
                          :disabled="Boolean(expandingNodeId)">
                    {{ expandingNodeId === selectedElement.item.nodeId
                      ? t('graphExpanding')
                      : t('graphExpandOneLevel') }}
                  </button>
                </div>
                <dl class="graph-detail-list">
                  <template v-if="selectedElement.kind === 'node'">
                    <dt>{{ t('id') }}</dt>
                    <dd>{{ selectedElement.item.nodeId }}</dd>
                    <dt>{{ t('graphLabels') }}</dt>
                    <dd>{{ Array.from(selectedElement.item.labels).join(', ') }}</dd>
                  </template>
                  <template v-else>
                    <dt>{{ t('id') }}</dt>
                    <dd>{{ selectedElement.item.relationId }}</dd>
                    <dt>{{ t('graphRelationType') }}</dt>
                    <dd>{{ selectedElement.item.relationType }}</dd>
                    <dt>{{ t('graphSourceNode') }}</dt>
                    <dd>{{ selectedElement.item.sourceNodeId }}</dd>
                    <dt>{{ t('graphTargetNode') }}</dt>
                    <dd>{{ selectedElement.item.targetNodeId }}</dd>
                  </template>
                </dl>
                <div class="text-xs text-ash mb-2">{{ t('graphProperties') }}</div>
                <pre class="graph-property-view">{{ formatProperties(selectedElement.item.properties) }}</pre>
              </template>
              <div v-else class="graph-detail-empty">
                {{ t('graphSelectElementHint') }}
              </div>
            </aside>
          </div>
        </div>
      </div>
    </div>
  `,
};

const DEFAULT_GRAPH_SCHEMA_JSON = JSON.stringify({
  schemaId: 'student-capability-v1',
  version: 1,
  mode: 'STRICT',
  nodeTypes: {
    Student: {
      label: 'Student',
      properties: {
        name: {
          name: 'name',
          type: 'STRING',
          required: true,
          sensitive: false,
          queryable: true,
          sortable: true,
        },
      },
    },
    Teacher: {
      label: 'Teacher',
      properties: {
        name: {
          name: 'name',
          type: 'STRING',
          required: true,
          sensitive: false,
          queryable: true,
          sortable: true,
        },
      },
    },
    Capability: {
      label: 'Capability',
      properties: {
        name: {
          name: 'name',
          type: 'STRING',
          required: true,
          sensitive: false,
          queryable: true,
          sortable: true,
        },
      },
    },
  },
  relationTypes: {
    HAS_CAPABILITY: {
      relationType: 'HAS_CAPABILITY',
      sourceLabels: ['Student'],
      targetLabels: ['Capability'],
      properties: {},
    },
    TAUGHT_BY: {
      relationType: 'TAUGHT_BY',
      sourceLabels: ['Student'],
      targetLabels: ['Teacher'],
      properties: {},
    },
  },
  defaultMaxDepth: 1,
  maxDepth: 2,
}, null, 2);

const GRAPH_SCHEMA_PROPERTY_TYPES = Object.freeze([
  'STRING',
  'BOOLEAN',
  'INTEGER',
  'LONG',
  'DOUBLE',
  'NUMBER',
  'TEMPORAL',
  'STRING_LIST',
  'SCALAR_LIST',
  'JSON',
]);
const GRAPH_SCHEMA_MODES = Object.freeze(['STRICT', 'HYBRID', 'OPEN']);
const GRAPH_SCHEMA_IDENTIFIER_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const GRAPH_SCHEMA_ID_PATTERN = /^[a-z][a-z0-9-]{1,63}$/;
const GRAPH_SCHEMA_RESERVED_PROPERTIES = new Set([
  'storageKey', 'nodeId', 'relationId', 'graphId', 'schemaId', 'createdAt', 'updatedAt',
]);
let graphSchemaDesignerIdSequence = 0;

function nextGraphSchemaDesignerId(prefix) {
  graphSchemaDesignerIdSequence += 1;
  return `${prefix}-${graphSchemaDesignerIdSequence}`;
}

function cloneGraphSchemaDesigner(value) {
  return JSON.parse(JSON.stringify(value));
}

function graphPropertiesToDesigner(properties = {}) {
  return Object.entries(properties).map(([name, definition]) => ({
    name,
    type: definition?.type || 'STRING',
    required: Boolean(definition?.required),
    sensitive: Boolean(definition?.sensitive),
    queryable: Boolean(definition?.queryable),
    sortable: Boolean(definition?.sortable),
  }));
}

function graphDefinitionToDesigner(definition = {}) {
  const nodeTypes = Object.entries(definition.nodeTypes || {}).map(([label, nodeType]) => ({
    id: nextGraphSchemaDesignerId('node'),
    label,
    properties: graphPropertiesToDesigner(nodeType?.properties),
  }));
  const nodeIdByLabel = new Map(nodeTypes.map(nodeType => [nodeType.label, nodeType.id]));
  const relationTypes = Object.entries(definition.relationTypes || {}).map(([relationType, relation]) => ({
    id: nextGraphSchemaDesignerId('relation'),
    relationType,
    sourceNodeIds: Array.from(relation?.sourceLabels || [])
      .map(label => nodeIdByLabel.get(label))
      .filter(Boolean),
    targetNodeIds: Array.from(relation?.targetLabels || [])
      .map(label => nodeIdByLabel.get(label))
      .filter(Boolean),
    properties: graphPropertiesToDesigner(relation?.properties),
  }));

  return {
    schemaId: definition.schemaId || 'student-capability-v1',
    version: Number(definition.version) || 1,
    mode: GRAPH_SCHEMA_MODES.includes(definition.mode) ? definition.mode : 'STRICT',
    nodeTypes,
    relationTypes,
    defaultMaxDepth: Number(definition.defaultMaxDepth) || 1,
    maxDepth: Number(definition.maxDepth) || 2,
  };
}

function graphDesignerPropertiesToDefinition(properties = []) {
  return properties.reduce((result, property) => {
    const name = property.name.trim();
    result[name] = {
      name,
      type: property.type,
      required: Boolean(property.required),
      sensitive: Boolean(property.sensitive),
      queryable: Boolean(property.queryable),
      sortable: Boolean(property.sortable),
    };
    return result;
  }, {});
}

function graphDesignerToDefinition(designer) {
  const nodeLabelById = new Map(
    designer.nodeTypes.map(nodeType => [nodeType.id, nodeType.label.trim()])
  );
  const nodeTypes = designer.nodeTypes.reduce((result, nodeType) => {
    const label = nodeType.label.trim();
    result[label] = {
      label,
      properties: graphDesignerPropertiesToDefinition(nodeType.properties),
    };
    return result;
  }, {});
  const relationTypes = designer.relationTypes.reduce((result, relation) => {
    const relationType = relation.relationType.trim();
    result[relationType] = {
      relationType,
      sourceLabels: relation.sourceNodeIds.map(nodeId => nodeLabelById.get(nodeId)).filter(Boolean),
      targetLabels: relation.targetNodeIds.map(nodeId => nodeLabelById.get(nodeId)).filter(Boolean),
      properties: graphDesignerPropertiesToDefinition(relation.properties),
    };
    return result;
  }, {});

  return {
    schemaId: designer.schemaId.trim(),
    version: Number(designer.version),
    mode: designer.mode,
    nodeTypes,
    relationTypes,
    defaultMaxDepth: Number(designer.defaultMaxDepth),
    maxDepth: Number(designer.maxDepth),
  };
}

function collectGraphSchemaDesignerIssues(designer, t) {
  const issues = [];
  if (!GRAPH_SCHEMA_ID_PATTERN.test(designer.schemaId.trim())) {
    issues.push(t('graphDesignerSchemaIdInvalid'));
  }
  if (!Number.isInteger(Number(designer.version)) || Number(designer.version) <= 0) {
    issues.push(t('graphDesignerVersionInvalid'));
  }
  if (!designer.nodeTypes.length) {
    issues.push(t('graphDesignerNodeRequired'));
  }
  if (!Number.isInteger(Number(designer.defaultMaxDepth))
      || !Number.isInteger(Number(designer.maxDepth))
      || Number(designer.defaultMaxDepth) <= 0
      || Number(designer.maxDepth) <= 0
      || Number(designer.defaultMaxDepth) > Number(designer.maxDepth)) {
    issues.push(t('graphDesignerDepthInvalid'));
  }

  const nodeLabels = new Set();
  const validateProperties = (properties, ownerName) => {
    const propertyNames = new Set();
    properties.forEach(property => {
      const propertyName = property.name.trim();
      if (!GRAPH_SCHEMA_IDENTIFIER_PATTERN.test(propertyName)) {
        issues.push(`${t('graphDesignerPropertyInvalid')}: ${ownerName || '-'}/${propertyName || '-'}`);
      } else if (GRAPH_SCHEMA_RESERVED_PROPERTIES.has(propertyName)) {
        issues.push(`${t('graphDesignerPropertyReserved')}: ${ownerName}/${propertyName}`);
      } else if (propertyNames.has(propertyName)) {
        issues.push(`${t('graphDesignerPropertyDuplicate')}: ${ownerName}/${propertyName}`);
      }
      propertyNames.add(propertyName);
    });
  };

  designer.nodeTypes.forEach(nodeType => {
    const label = nodeType.label.trim();
    if (!GRAPH_SCHEMA_IDENTIFIER_PATTERN.test(label)) {
      issues.push(`${t('graphDesignerNodeInvalid')}: ${label || '-'}`);
    } else if (nodeLabels.has(label)) {
      issues.push(`${t('graphDesignerNodeDuplicate')}: ${label}`);
    }
    nodeLabels.add(label);
    validateProperties(nodeType.properties, label);
  });

  const nodeIds = new Set(designer.nodeTypes.map(nodeType => nodeType.id));
  const relationNames = new Set();
  designer.relationTypes.forEach(relation => {
    const relationType = relation.relationType.trim();
    if (!GRAPH_SCHEMA_IDENTIFIER_PATTERN.test(relationType)) {
      issues.push(`${t('graphDesignerRelationInvalid')}: ${relationType || '-'}`);
    } else if (relationNames.has(relationType)) {
      issues.push(`${t('graphDesignerRelationDuplicate')}: ${relationType}`);
    }
    relationNames.add(relationType);
    if (!relation.sourceNodeIds.length || relation.sourceNodeIds.some(nodeId => !nodeIds.has(nodeId))) {
      issues.push(`${t('graphDesignerSourceRequired')}: ${relationType || '-'}`);
    }
    if (!relation.targetNodeIds.length || relation.targetNodeIds.some(nodeId => !nodeIds.has(nodeId))) {
      issues.push(`${t('graphDesignerTargetRequired')}: ${relationType || '-'}`);
    }
    validateProperties(relation.properties, relationType);
  });
  return issues;
}

const GraphPropertyEditor = {
  props: {
    properties: { type: Array, required: true },
    editable: { type: Boolean, default: true },
  },
  emits: ['update:properties'],
  setup(props, { emit }) {
    const t = inject('t');

    function updateProperties(mutator) {
      const nextProperties = cloneGraphSchemaDesigner(props.properties);
      mutator(nextProperties);
      emit('update:properties', nextProperties);
    }

    function nextPropertyName() {
      const names = new Set(props.properties.map(property => property.name));
      let index = 1;
      let candidate = 'property';
      while (names.has(candidate)) {
        index += 1;
        candidate = `property${index}`;
      }
      return candidate;
    }

    function addProperty() {
      updateProperties(properties => properties.push({
        name: nextPropertyName(),
        type: 'STRING',
        required: false,
        sensitive: false,
        queryable: true,
        sortable: false,
      }));
    }

    function updateProperty(index, field, value) {
      updateProperties(properties => {
        properties[index][field] = value;
      });
    }

    function removeProperty(index) {
      updateProperties(properties => properties.splice(index, 1));
    }

    return {
      t,
      propertyTypes: GRAPH_SCHEMA_PROPERTY_TYPES,
      addProperty,
      updateProperty,
      removeProperty,
    };
  },
  template: `
    <div class="graph-property-designer">
      <div class="graph-designer-section-header">
        <div>
          <strong>{{ t('graphDesignerProperties') }}</strong>
          <span class="text-xs text-ash">{{ t('graphDesignerPropertiesHint') }}</span>
        </div>
        <button v-if="editable" class="btn btn-ghost btn-sm" @click="addProperty">
          {{ t('graphDesignerAddProperty') }}
        </button>
      </div>

      <div v-if="properties.length === 0" class="graph-designer-inline-empty">
        {{ t('graphDesignerNoProperties') }}
      </div>
      <div v-else class="graph-property-list">
        <article v-for="(property, index) in properties" :key="index" class="graph-property-card">
          <div class="graph-property-main-row">
            <div class="input-group">
              <label class="input-label">{{ t('name') }}</label>
              <input class="input"
                     :value="property.name"
                     :disabled="!editable"
                     @input="updateProperty(index, 'name', $event.target.value)" />
            </div>
            <div class="input-group">
              <label class="input-label">{{ t('graphDesignerPropertyType') }}</label>
              <select class="input"
                      :value="property.type"
                      :disabled="!editable"
                      @change="updateProperty(index, 'type', $event.target.value)">
                <option v-for="propertyType in propertyTypes" :key="propertyType" :value="propertyType">
                  {{ propertyType }}
                </option>
              </select>
            </div>
            <button v-if="editable"
                    class="btn btn-danger btn-sm graph-property-delete"
                    :aria-label="t('delete')"
                    @click="removeProperty(index)">
              ×
            </button>
          </div>
          <div class="graph-property-flags">
            <label>
              <input type="checkbox" :checked="property.required" :disabled="!editable"
                     @change="updateProperty(index, 'required', $event.target.checked)" />
              <span>{{ t('graphDesignerRequired') }}</span>
            </label>
            <label>
              <input type="checkbox" :checked="property.queryable" :disabled="!editable"
                     @change="updateProperty(index, 'queryable', $event.target.checked)" />
              <span>{{ t('graphDesignerQueryable') }}</span>
            </label>
            <label>
              <input type="checkbox" :checked="property.sortable" :disabled="!editable"
                     @change="updateProperty(index, 'sortable', $event.target.checked)" />
              <span>{{ t('graphDesignerSortable') }}</span>
            </label>
            <label>
              <input type="checkbox" :checked="property.sensitive" :disabled="!editable"
                     @change="updateProperty(index, 'sensitive', $event.target.checked)" />
              <span>{{ t('graphDesignerSensitive') }}</span>
            </label>
          </div>
        </article>
      </div>
    </div>
  `,
};

const GraphSchemaDesigner = {
  components: { GraphTopology, GraphPropertyEditor },
  props: {
    modelValue: { type: Object, required: true },
    editable: { type: Boolean, default: true },
    schemaIdEditable: { type: Boolean, default: true },
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const t = inject('t');
    const selectedKind = ref('node');
    const selectedId = ref(props.modelValue.nodeTypes[0]?.id || '');

    const selectedNode = computed(() => selectedKind.value === 'node'
      ? props.modelValue.nodeTypes.find(nodeType => nodeType.id === selectedId.value) || null
      : null);
    const selectedRelation = computed(() => selectedKind.value === 'relation'
      ? props.modelValue.relationTypes.find(relation => relation.id === selectedId.value) || null
      : null);
    const topologyNodes = computed(() => props.modelValue.nodeTypes.map(nodeType => ({
      nodeId: nodeType.id,
      labels: [nodeType.label],
      properties: { name: nodeType.label || t('graphDesignerUnnamedNode') },
      designerId: nodeType.id,
    })));
    const topologyRelations = computed(() => props.modelValue.relationTypes.flatMap(relation =>
      relation.sourceNodeIds.flatMap(sourceNodeId =>
        relation.targetNodeIds.map(targetNodeId => ({
          relationId: `${relation.id}:${sourceNodeId}:${targetNodeId}`,
          relationType: relation.relationType || t('graphDesignerUnnamedRelation'),
          sourceNodeId,
          targetNodeId,
          properties: {},
          designerId: relation.id,
        }))
      )
    ));

    function commit(mutator) {
      const nextModel = cloneGraphSchemaDesigner(props.modelValue);
      mutator(nextModel);
      emit('update:modelValue', nextModel);
    }

    function updateSchemaField(field, value) {
      commit(model => {
        model[field] = ['version', 'defaultMaxDepth', 'maxDepth'].includes(field)
          ? Number(value)
          : value;
      });
    }

    function uniqueName(existingNames, baseName) {
      const names = new Set(existingNames);
      let index = 1;
      let candidate = baseName;
      while (names.has(candidate)) {
        index += 1;
        candidate = `${baseName}${index}`;
      }
      return candidate;
    }

    function addNode() {
      const nodeId = nextGraphSchemaDesignerId('node');
      const label = uniqueName(props.modelValue.nodeTypes.map(nodeType => nodeType.label), 'Node');
      commit(model => model.nodeTypes.push({
        id: nodeId,
        label,
        properties: [{
          name: 'name',
          type: 'STRING',
          required: true,
          sensitive: false,
          queryable: true,
          sortable: true,
        }],
      }));
      selectedKind.value = 'node';
      selectedId.value = nodeId;
    }

    function addRelation() {
      if (!props.modelValue.nodeTypes.length) return;
      const relationId = nextGraphSchemaDesignerId('relation');
      const relationType = uniqueName(
        props.modelValue.relationTypes.map(relation => relation.relationType),
        'RELATES_TO'
      );
      const sourceNodeId = props.modelValue.nodeTypes[0].id;
      const targetNodeId = props.modelValue.nodeTypes[1]?.id || sourceNodeId;
      commit(model => model.relationTypes.push({
        id: relationId,
        relationType,
        sourceNodeIds: [sourceNodeId],
        targetNodeIds: [targetNodeId],
        properties: [],
      }));
      selectedKind.value = 'relation';
      selectedId.value = relationId;
    }

    function selectNode(nodeId) {
      selectedKind.value = 'node';
      selectedId.value = nodeId;
    }

    function selectRelation(relationId) {
      selectedKind.value = 'relation';
      selectedId.value = relationId;
    }

    function selectTopologyElement(element) {
      if (!element) return;
      if (element.kind === 'node') {
        selectNode(element.item.designerId);
      } else {
        selectRelation(element.item.designerId);
      }
    }

    function updateSelectedNodeField(field, value) {
      commit(model => {
        const nodeType = model.nodeTypes.find(item => item.id === selectedId.value);
        if (nodeType) nodeType[field] = value;
      });
    }

    function updateSelectedRelationField(field, value) {
      commit(model => {
        const relation = model.relationTypes.find(item => item.id === selectedId.value);
        if (relation) relation[field] = value;
      });
    }

    function updateSelectedProperties(properties) {
      commit(model => {
        const collection = selectedKind.value === 'node' ? model.nodeTypes : model.relationTypes;
        const item = collection.find(candidate => candidate.id === selectedId.value);
        if (item) item.properties = properties;
      });
    }

    function toggleRelationNode(field, nodeId) {
      commit(model => {
        const relation = model.relationTypes.find(item => item.id === selectedId.value);
        if (!relation) return;
        relation[field] = relation[field].includes(nodeId)
          ? relation[field].filter(candidate => candidate !== nodeId)
          : [...relation[field], nodeId];
      });
    }

    function deleteSelectedNode() {
      const nodeId = selectedId.value;
      const nextModel = cloneGraphSchemaDesigner(props.modelValue);
      nextModel.nodeTypes = nextModel.nodeTypes.filter(nodeType => nodeType.id !== nodeId);
      nextModel.relationTypes = nextModel.relationTypes
        .map(relation => ({
          ...relation,
          sourceNodeIds: relation.sourceNodeIds.filter(candidate => candidate !== nodeId),
          targetNodeIds: relation.targetNodeIds.filter(candidate => candidate !== nodeId),
        }))
        .filter(relation => relation.sourceNodeIds.length && relation.targetNodeIds.length);
      emit('update:modelValue', nextModel);
      selectedKind.value = 'node';
      selectedId.value = nextModel.nodeTypes[0]?.id || '';
    }

    function deleteSelectedRelation() {
      const relationId = selectedId.value;
      const nextModel = cloneGraphSchemaDesigner(props.modelValue);
      nextModel.relationTypes = nextModel.relationTypes
        .filter(relation => relation.id !== relationId);
      emit('update:modelValue', nextModel);
      selectedKind.value = 'relation';
      selectedId.value = nextModel.relationTypes[0]?.id || '';
      if (!selectedId.value) {
        selectedKind.value = 'node';
        selectedId.value = nextModel.nodeTypes[0]?.id || '';
      }
    }

    watch(
      () => [props.modelValue.nodeTypes, props.modelValue.relationTypes],
      () => {
        const selectionExists = selectedKind.value === 'node'
          ? props.modelValue.nodeTypes.some(nodeType => nodeType.id === selectedId.value)
          : props.modelValue.relationTypes.some(relation => relation.id === selectedId.value);
        if (!selectionExists) {
          selectedKind.value = 'node';
          selectedId.value = props.modelValue.nodeTypes[0]?.id || '';
        }
      },
      { deep: true }
    );

    return {
      t,
      schemaModes: GRAPH_SCHEMA_MODES,
      selectedKind,
      selectedId,
      selectedNode,
      selectedRelation,
      topologyNodes,
      topologyRelations,
      updateSchemaField,
      addNode,
      addRelation,
      selectNode,
      selectRelation,
      selectTopologyElement,
      updateSelectedNodeField,
      updateSelectedRelationField,
      updateSelectedProperties,
      toggleRelationNode,
      deleteSelectedNode,
      deleteSelectedRelation,
    };
  },
  template: `
    <div class="graph-schema-designer">
      <section class="graph-schema-basics">
        <div class="input-group">
          <label class="input-label">schemaId</label>
          <input class="input"
                 :value="modelValue.schemaId"
                 :disabled="!schemaIdEditable"
                 @input="updateSchemaField('schemaId', $event.target.value)" />
        </div>
        <div class="input-group">
          <label class="input-label">{{ t('graphDesignerVersion') }}</label>
          <input class="input" type="number" min="1"
                 :value="modelValue.version"
                 :disabled="!editable"
                 @input="updateSchemaField('version', $event.target.value)" />
        </div>
        <div class="input-group">
          <label class="input-label">{{ t('graphDesignerMode') }}</label>
          <select class="input"
                  :value="modelValue.mode"
                  :disabled="!editable"
                  @change="updateSchemaField('mode', $event.target.value)">
            <option v-for="schemaMode in schemaModes" :key="schemaMode" :value="schemaMode">
              {{ schemaMode }}
            </option>
          </select>
        </div>
        <div class="input-group">
          <label class="input-label">{{ t('graphDesignerDefaultDepth') }}</label>
          <input class="input" type="number" min="1"
                 :value="modelValue.defaultMaxDepth"
                 :disabled="!editable"
                 @input="updateSchemaField('defaultMaxDepth', $event.target.value)" />
        </div>
        <div class="input-group">
          <label class="input-label">{{ t('graphDesignerMaxDepth') }}</label>
          <input class="input" type="number" min="1"
                 :value="modelValue.maxDepth"
                 :disabled="!editable"
                 @input="updateSchemaField('maxDepth', $event.target.value)" />
        </div>
      </section>

      <div class="graph-schema-workbench">
        <aside class="graph-schema-outline">
          <div class="graph-designer-section-header">
            <strong>{{ t('graphDesignerStructure') }}</strong>
          </div>
          <div class="graph-outline-actions">
            <button class="btn btn-ghost btn-sm" :disabled="!editable" @click="addNode">
              {{ t('graphDesignerAddNode') }}
            </button>
            <button class="btn btn-ghost btn-sm"
                    :disabled="!editable || modelValue.nodeTypes.length === 0"
                    @click="addRelation">
              {{ t('graphDesignerAddRelation') }}
            </button>
          </div>

          <div class="graph-outline-group">
            <div class="graph-outline-title">
              <span>{{ t('graphNodeTypes') }}</span>
              <span class="tag tag-gold">{{ modelValue.nodeTypes.length }}</span>
            </div>
            <button v-for="nodeType in modelValue.nodeTypes"
                    :key="nodeType.id"
                    :class="['graph-outline-item', { active: selectedKind === 'node' && selectedId === nodeType.id }]"
                    @click="selectNode(nodeType.id)">
              <span class="graph-outline-node-dot"></span>
              <span>{{ nodeType.label || t('graphDesignerUnnamedNode') }}</span>
            </button>
          </div>

          <div class="graph-outline-group">
            <div class="graph-outline-title">
              <span>{{ t('graphRelationTypes') }}</span>
              <span class="tag tag-rose">{{ modelValue.relationTypes.length }}</span>
            </div>
            <button v-for="relation in modelValue.relationTypes"
                    :key="relation.id"
                    :class="['graph-outline-item', { active: selectedKind === 'relation' && selectedId === relation.id }]"
                    @click="selectRelation(relation.id)">
              <span class="graph-outline-relation-line"></span>
              <span>{{ relation.relationType || t('graphDesignerUnnamedRelation') }}</span>
            </button>
          </div>
        </aside>

        <div class="graph-schema-preview">
          <div class="graph-designer-section-header">
            <div>
              <strong>{{ t('graphDesignerTopologyPreview') }}</strong>
              <span class="text-xs text-ash">{{ t('graphDesignerTopologyHint') }}</span>
            </div>
          </div>
          <GraphTopology
            :nodes="topologyNodes"
            :relations="topologyRelations"
            @select="selectTopologyElement"
          />
        </div>

        <aside class="graph-schema-inspector">
          <template v-if="selectedNode">
            <div class="graph-designer-section-header">
              <div>
                <span class="tag tag-gold">{{ t('graphNode') }}</span>
                <strong>{{ selectedNode.label || t('graphDesignerUnnamedNode') }}</strong>
              </div>
              <button v-if="editable" class="btn btn-danger btn-sm" @click="deleteSelectedNode">
                {{ t('delete') }}
              </button>
            </div>
            <div class="input-group">
              <label class="input-label">{{ t('graphDesignerNodeLabel') }}</label>
              <input class="input"
                     :value="selectedNode.label"
                     :disabled="!editable"
                     @input="updateSelectedNodeField('label', $event.target.value)" />
              <span class="text-xs text-ash">{{ t('graphDesignerIdentifierHint') }}</span>
            </div>
            <GraphPropertyEditor
              :properties="selectedNode.properties"
              :editable="editable"
              @update:properties="updateSelectedProperties"
            />
          </template>

          <template v-else-if="selectedRelation">
            <div class="graph-designer-section-header">
              <div>
                <span class="tag tag-rose">{{ t('graphRelation') }}</span>
                <strong>{{ selectedRelation.relationType || t('graphDesignerUnnamedRelation') }}</strong>
              </div>
              <button v-if="editable" class="btn btn-danger btn-sm" @click="deleteSelectedRelation">
                {{ t('delete') }}
              </button>
            </div>
            <div class="input-group">
              <label class="input-label">{{ t('graphRelationType') }}</label>
              <input class="input"
                     :value="selectedRelation.relationType"
                     :disabled="!editable"
                     @input="updateSelectedRelationField('relationType', $event.target.value)" />
              <span class="text-xs text-ash">{{ t('graphDesignerIdentifierHint') }}</span>
            </div>

            <div class="graph-relation-endpoints">
              <div>
                <label class="input-label">{{ t('graphDesignerSourceTypes') }}</label>
                <div class="graph-node-choice-list">
                  <button v-for="nodeType in modelValue.nodeTypes"
                          :key="nodeType.id"
                          :class="['graph-node-choice', { active: selectedRelation.sourceNodeIds.includes(nodeType.id) }]"
                          :disabled="!editable"
                          @click="toggleRelationNode('sourceNodeIds', nodeType.id)">
                    {{ nodeType.label }}
                  </button>
                </div>
              </div>
              <div>
                <label class="input-label">{{ t('graphDesignerTargetTypes') }}</label>
                <div class="graph-node-choice-list">
                  <button v-for="nodeType in modelValue.nodeTypes"
                          :key="nodeType.id"
                          :class="['graph-node-choice', { active: selectedRelation.targetNodeIds.includes(nodeType.id) }]"
                          :disabled="!editable"
                          @click="toggleRelationNode('targetNodeIds', nodeType.id)">
                    {{ nodeType.label }}
                  </button>
                </div>
              </div>
            </div>

            <GraphPropertyEditor
              :properties="selectedRelation.properties"
              :editable="editable"
              @update:properties="updateSelectedProperties"
            />
          </template>

          <div v-else class="graph-designer-empty-inspector">
            {{ t('graphDesignerSelectElement') }}
          </div>
        </aside>
      </div>
    </div>
  `,
};

const GraphSchemaPage = {
  components: { GraphSchemaDesigner },
  setup() {
    const t = inject('t');
    const schemas = ref([]);
    const pageInfo = ref({ limit: 20, nextCursor: '', hasMore: false });
    const selectedSchemaId = ref('');
    const details = ref(null);
    const editorMode = ref('create');
    const editorView = ref('visual');
    const content = ref(DEFAULT_GRAPH_SCHEMA_JSON);
    const designerModel = ref(graphDefinitionToDesigner(JSON.parse(DEFAULT_GRAPH_SCHEMA_JSON)));
    const createEnabled = ref(false);
    const focusMode = ref(false);
    const loading = ref(false);
    const loadingMore = ref(false);
    const saving = ref(false);
    const changingState = ref(false);
    const deleting = ref(false);
    const error = ref('');

    const isCreateMode = computed(() => editorMode.value === 'create');
    const isEditable = computed(() => isCreateMode.value || Boolean(details.value?.editable));
    const designerIssues = computed(() => collectGraphSchemaDesignerIssues(designerModel.value, t));
    const canSave = computed(() =>
      isEditable.value
        && !saving.value
        && (editorView.value === 'visual'
          ? designerIssues.value.length === 0
          : Boolean(content.value.trim()))
    );

    function resetEditor() {
      editorMode.value = 'create';
      editorView.value = 'visual';
      selectedSchemaId.value = '';
      details.value = null;
      content.value = DEFAULT_GRAPH_SCHEMA_JSON;
      designerModel.value = graphDefinitionToDesigner(JSON.parse(DEFAULT_GRAPH_SCHEMA_JSON));
      createEnabled.value = false;
      error.value = '';
    }

    function applyDetails(schemaDetails) {
      details.value = schemaDetails;
      editorMode.value = 'edit';
      editorView.value = 'visual';
      designerModel.value = graphDefinitionToDesigner(schemaDetails.definition);
      content.value = JSON.stringify(schemaDetails.definition, null, 2);
    }

    function setEditorView(view) {
      if (editorView.value === view) return;
      error.value = '';
      try {
        if (view === 'source') {
          content.value = JSON.stringify(graphDesignerToDefinition(designerModel.value), null, 2);
        } else {
          designerModel.value = graphDefinitionToDesigner(JSON.parse(content.value));
        }
        editorView.value = view;
      } catch (e) {
        error.value = `${t('graphSchemaSourceInvalid')}: ${e.message}`;
      }
    }

    async function selectSchema(schemaId) {
      if (!schemaId) return;
      selectedSchemaId.value = schemaId;
      error.value = '';
      try {
        applyDetails(await CyreneAPI.getGraphSchemaConfig(schemaId));
      } catch (e) {
        error.value = e.message;
      }
    }

    async function loadSchemas({ append = false, cursor = '', preferredSchemaId = '' } = {}) {
      const page = requirePageResponse(
        await CyreneAPI.listGraphSchemaConfigs({
          limit: pageInfo.value.limit,
          cursor,
        }),
        schema => typeof schema?.schemaId === 'string',
        t('graphInvalidSchemaPageResponse')
      );
      schemas.value = append ? [...schemas.value, ...page.items] : page.items;
      pageInfo.value = page.pageInfo;
      if (append) return;

      const targetSchemaId = preferredSchemaId
        || (schemas.value.some(schema => schema.schemaId === selectedSchemaId.value)
          ? selectedSchemaId.value
          : schemas.value[0]?.schemaId);
      if (targetSchemaId) {
        await selectSchema(targetSchemaId);
      } else {
        resetEditor();
      }
    }

    async function refreshSchemas(preferredSchemaId = '') {
      loading.value = true;
      error.value = '';
      try {
        await loadSchemas({ preferredSchemaId });
      } catch (e) {
        error.value = e.message;
      } finally {
        loading.value = false;
      }
    }

    async function loadMoreSchemas() {
      if (!pageInfo.value.hasMore || loadingMore.value) return;
      loadingMore.value = true;
      error.value = '';
      try {
        await loadSchemas({ append: true, cursor: pageInfo.value.nextCursor });
      } catch (e) {
        error.value = e.message;
      } finally {
        loadingMore.value = false;
      }
    }

    async function saveSchema() {
      if (!canSave.value) return;
      const wasCreating = isCreateMode.value;
      saving.value = true;
      error.value = '';
      try {
        let definition;
        if (editorView.value === 'visual') {
          if (designerIssues.value.length) {
            throw new Error(designerIssues.value[0]);
          }
          definition = graphDesignerToDefinition(designerModel.value);
        } else {
          definition = JSON.parse(content.value);
          const sourceModel = graphDefinitionToDesigner(definition);
          const sourceIssues = collectGraphSchemaDesignerIssues(sourceModel, t);
          if (sourceIssues.length) {
            throw new Error(sourceIssues[0]);
          }
          designerModel.value = sourceModel;
        }
        if (!wasCreating && definition.schemaId !== selectedSchemaId.value) {
          throw new Error(t('graphSchemaIdRule'));
        }
        content.value = JSON.stringify(definition, null, 2);
        const response = wasCreating
          ? await CyreneAPI.createGraphSchemaConfig({
              format: 'JSON',
              content: content.value,
              enabled: createEnabled.value,
            })
          : await CyreneAPI.updateGraphSchemaConfig(selectedSchemaId.value, {
              format: 'JSON',
              content: content.value,
            });
        const schemaId = response.definition.schemaId;
        await refreshSchemas(schemaId);
        showToast(t(wasCreating ? 'graphSchemaCreated' : 'graphSchemaSaved'), 'success');
      } catch (e) {
        error.value = e.message;
      } finally {
        saving.value = false;
      }
    }

    async function toggleSchema() {
      if (!details.value?.editable || changingState.value) return;
      changingState.value = true;
      error.value = '';
      try {
        const response = details.value.enabled
          ? await CyreneAPI.disableGraphSchemaConfig(selectedSchemaId.value)
          : await CyreneAPI.enableGraphSchemaConfig(selectedSchemaId.value);
        applyDetails(response);
        await refreshSchemas(selectedSchemaId.value);
        showToast(t(response.enabled ? 'graphSchemaEnabled' : 'graphSchemaDisabled'), 'success');
      } catch (e) {
        error.value = e.message;
      } finally {
        changingState.value = false;
      }
    }

    async function deleteSchema() {
      if (!details.value?.editable || details.value.enabled || deleting.value) return;
      if (!window.confirm(t('graphSchemaDeleteConfirm'))) return;
      deleting.value = true;
      error.value = '';
      try {
        await CyreneAPI.deleteGraphSchemaConfig(selectedSchemaId.value);
        showToast(t('graphSchemaDeleted'), 'success');
        selectedSchemaId.value = '';
        await refreshSchemas();
      } catch (e) {
        error.value = e.message;
      } finally {
        deleting.value = false;
      }
    }

    onMounted(refreshSchemas);

    return {
      t, schemas, pageInfo, selectedSchemaId, details,
      editorView, content, designerModel, designerIssues, createEnabled,
      loading, loadingMore, saving, changingState, deleting, error, focusMode,
      isCreateMode, isEditable, canSave,
      resetEditor, selectSchema, refreshSchemas, loadMoreSchemas, saveSchema,
      setEditorView, toggleSchema, deleteSchema,
    };
  },
  template: `
    <div class="graph-page">
      <div v-if="error" class="alert alert-error mb-4">
        <span>{{ error }}</span>
        <button class="btn btn-ghost btn-sm" @click="error = ''">×</button>
      </div>

      <div :class="['graph-schema-layout', { 'focus-mode': focusMode }]">
        <section v-show="!focusMode" class="card graph-schema-list-card">
          <div class="card-header graph-card-header">
            <div>
              <div class="card-title">{{ t('graphSchemaManagement') }}</div>
              <div class="text-xs text-ash mt-2">{{ t('graphSchemaManagementHint') }}</div>
            </div>
            <button class="btn btn-primary btn-sm" @click="resetEditor">
              {{ t('graphCreateSchema') }}
            </button>
          </div>
          <div class="card-body graph-schema-list-body">
            <div v-if="loading" class="graph-schema-loading">
              <div class="loading-dots"><span></span><span></span><span></span></div>
            </div>
            <template v-else>
              <button v-for="schema in schemas"
                      :key="schema.schemaId"
                      :class="['graph-schema-list-item', { active: selectedSchemaId === schema.schemaId }]"
                      @click="selectSchema(schema.schemaId)">
                <span class="graph-schema-list-main">
                  <strong>{{ schema.schemaId }}</strong>
                  <span class="text-xs text-ash">
                    v{{ schema.version }} · {{ schema.mode }}
                  </span>
                </span>
                <span class="graph-schema-list-meta">
                  <span :class="['tag', schema.enabled ? 'tag-gold' : 'tag-dusk']">
                    {{ schema.enabled ? t('enabled') : t('disabled') }}
                  </span>
                  <span class="tag tag-iris">{{ schema.source }}</span>
                </span>
                <span class="text-xs text-ash">
                  {{ schema.nodeTypeCount }} {{ t('graphNodes') }} ·
                  {{ schema.relationTypeCount }} {{ t('graphRelations') }} ·
                  {{ schema.format }}
                </span>
              </button>
              <div v-if="schemas.length === 0" class="graph-schema-empty">
                <strong>{{ t('graphNoManagedSchema') }}</strong>
                <span class="text-xs text-ash">{{ t('graphNoManagedSchemaHint') }}</span>
              </div>
              <button v-if="pageInfo.hasMore"
                      class="btn btn-ghost btn-sm graph-schema-load-more"
                      :disabled="loadingMore"
                      @click="loadMoreSchemas">
                {{ loadingMore ? t('graphLoadingMore') : t('graphLoadMore') }}
              </button>
            </template>
          </div>
        </section>

        <section class="card graph-schema-editor-card">
          <div class="card-header graph-card-header">
            <div>
              <div class="card-title">
                {{ isCreateMode ? t('graphCreateSchema') : selectedSchemaId }}
              </div>
              <div v-if="details" class="graph-schema-editor-meta mt-2">
                <span :class="['tag', details.enabled ? 'tag-gold' : 'tag-dusk']">
                  {{ details.enabled ? t('enabled') : t('disabled') }}
                </span>
                <span class="tag tag-iris">{{ details.source }}</span>
                <span class="tag tag-dusk">{{ details.format }}</span>
              </div>
            </div>
            <div class="graph-schema-header-actions">
              <div v-if="details?.editable" class="graph-schema-actions">
                <button class="btn btn-ghost btn-sm"
                        :disabled="changingState"
                        @click="toggleSchema">
                  {{ changingState
                    ? t('graphChangingSchemaState')
                    : details.enabled ? t('graphDisableSchema') : t('graphEnableSchema') }}
                </button>
                <button class="btn btn-danger btn-sm"
                        :disabled="details.enabled || deleting"
                        :title="details.enabled ? t('graphDisableBeforeDelete') : ''"
                        @click="deleteSchema">
                  {{ deleting ? t('graphDeletingSchema') : t('delete') }}
                </button>
              </div>
              <button class="btn btn-ghost btn-sm" @click="focusMode = !focusMode">
                {{ focusMode ? t('graphShowSchemaList') : t('graphFocusEditor') }}
              </button>
            </div>
          </div>

          <div class="card-body graph-schema-editor-body">
            <div v-if="!isCreateMode && !details" class="graph-schema-loading">
              <div class="loading-dots"><span></span><span></span><span></span></div>
            </div>
            <template v-else>
              <div v-if="details && !details.editable" class="alert alert-info mb-4">
                {{ t('graphSpiSchemaReadOnly') }}
              </div>
              <div class="graph-schema-editor-toolbar">
                <div class="graph-schema-view-switch" role="tablist">
                  <button :class="['graph-schema-view-button', { active: editorView === 'visual' }]"
                          role="tab"
                          :aria-selected="editorView === 'visual'"
                          @click="setEditorView('visual')">
                    {{ t('graphDesignerVisualMode') }}
                  </button>
                  <button :class="['graph-schema-view-button', { active: editorView === 'source' }]"
                          role="tab"
                          :aria-selected="editorView === 'source'"
                          @click="setEditorView('source')">
                    {{ t('graphDesignerSourceMode') }}
                  </button>
                </div>
                <label v-if="isCreateMode" class="graph-schema-enable-option">
                  <input type="checkbox" v-model="createEnabled" />
                  <span>{{ t('graphEnableAfterCreate') }}</span>
                </label>
              </div>

              <template v-if="editorView === 'visual'">
                <div v-if="designerIssues.length" class="alert alert-error graph-schema-validation mb-4">
                  <div>
                    <strong>{{ t('graphDesignerValidationTitle') }}</strong>
                    <ul>
                      <li v-for="issue in designerIssues.slice(0, 4)" :key="issue">{{ issue }}</li>
                    </ul>
                  </div>
                </div>
                <GraphSchemaDesigner
                  v-model="designerModel"
                  :editable="isEditable"
                  :schema-id-editable="isCreateMode && isEditable"
                />
              </template>

              <template v-else>
                <div class="text-xs text-ash mb-2">{{ t('graphDesignerSourceHint') }}</div>
                <textarea class="input graph-schema-editor"
                          v-model="content"
                          :readonly="!isEditable"
                          spellcheck="false"
                          :aria-label="t('graphSchemaContent')"></textarea>
              </template>

              <div class="graph-schema-editor-footer">
                <div class="text-xs text-ash">
                  {{ editorView === 'visual' ? t('graphDesignerSaveHint') : t('graphSchemaIdRule') }}
                </div>
                <button class="btn btn-primary"
                        :disabled="!canSave"
                        @click="saveSchema">
                  {{ saving ? t('saving') : t('save') }}
                </button>
              </div>
            </template>
          </div>
        </section>
      </div>
    </div>
  `,
};

const GraphPage = {
  components: { GraphBrowsePage, GraphBuildPage, GraphSchemaPage },
  setup() {
    const t = inject('t');
    const activeGraphTab = ref('browse');
    return { t, activeGraphTab };
  },
  template: `
    <div class="graph-shell">
      <div class="graph-tabs" role="tablist">
        <button :class="['graph-tab', { active: activeGraphTab === 'browse' }]"
                role="tab"
                :aria-selected="activeGraphTab === 'browse'"
                @click="activeGraphTab = 'browse'">
          {{ t('graphBrowseTab') }}
        </button>
        <button :class="['graph-tab', { active: activeGraphTab === 'build' }]"
                role="tab"
                :aria-selected="activeGraphTab === 'build'"
                @click="activeGraphTab = 'build'">
          {{ t('graphBuildTab') }}
        </button>
        <button :class="['graph-tab', { active: activeGraphTab === 'schema' }]"
                role="tab"
                :aria-selected="activeGraphTab === 'schema'"
                @click="activeGraphTab = 'schema'">
          {{ t('graphSchemaTab') }}
        </button>
      </div>
      <GraphBrowsePage v-if="activeGraphTab === 'browse'" />
      <GraphBuildPage v-else-if="activeGraphTab === 'build'" />
      <GraphSchemaPage v-else />
    </div>
  `,
};

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
        traces.value = requireArrayResponse(
          traceData,
          trace => trace && typeof trace === 'object',
          t('invalidTraceListResponse')
        );
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
          configText.value = '{\n  "discoveredAt": "",\n  "projectDescription": "",\n  "baseUrl": "",\n  "projectRoot": "",\n  "endpoints": []\n}';
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
  components: { ToastContainer, StarsBackground, PreConfigModal, ChatPage, KnowledgePage, GraphPage, AuditPage, ConfigPage },
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
      { id: 'graph', label: t('graph'), icon: Icons.graph },
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
app.component('GraphDataDesigner', GraphDataDesigner);
app.config.globalProperties.Icons = Icons;
app.provide('Icons', Icons);
app.mount('#app');
