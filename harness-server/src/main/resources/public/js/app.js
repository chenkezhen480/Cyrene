/**
 * Cyrene Web UI — Vue 3 SPA
 * 在时间的涟漪中，记录与被记住
 */
const { createApp, ref, reactive, computed, watch, onMounted, onUnmounted, nextTick, provide, inject } = Vue;

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
  refresh: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>`,
  menu: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>`,
  close: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`,
  save: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>`,
  search: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>`,
};

// ── Ripple Effect Helper ──
function createRipple(event, element) {
  const rect = element.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height) * 2;
  const x = event.clientX - rect.left - size / 2;
  const y = event.clientY - rect.top - size / 2;
  const ripple = document.createElement('span');
  ripple.className = 'ripple';
  ripple.style.width = ripple.style.height = `${size}px`;
  ripple.style.left = `${x}px`;
  ripple.style.top = `${y}px`;
  element.appendChild(ripple);
  ripple.addEventListener('animationend', () => ripple.remove());
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

// Stars Background
const StarsBackground = {
  setup() {
    const stars = ref([]);
    onMounted(() => {
      const arr = [];
      for (let i = 0; i < 20; i++) {
        arr.push({
          id: i,
          left: Math.random() * 100 + '%',
          top: Math.random() * 100 + '%',
          delay: Math.random() * 5 + 's',
          duration: (3 + Math.random() * 4) + 's',
          dim: Math.random() > 0.6,
        });
      }
      stars.value = arr;
    });
    return { stars };
  },
  template: `
    <div>
      <div v-for="s in stars" :key="s.id"
           :class="['star', s.dim ? 'star-dim' : '']"
           :style="{ left: s.left, top: s.top, animationDelay: s.delay, animationDuration: s.duration }">
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
    const step = ref('input'); // input | scanning | result | done
    const sourceRoot = ref('');
    const baseUrl = ref('');
    const scanResult = ref(null);
    const error = ref('');
    const scanning = ref(false);

    async function startScan() {
      if (!sourceRoot.value.trim()) {
        error.value = '请输入项目目录路径';
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
        // Pass full config (discoveredAt, sourceRoot, endpoints)
        const config = {
          discoveredAt: scanResult.value.discoveredAt || new Date().toISOString(),
          sourceRoot: scanResult.value.sourceRoot || sourceRoot.value.trim(),
          endpoints: (scanResult.value.endpoints || []).map(ep => ({ ...ep, confirmed: true })),
        };
        await CyreneAPI.generateConfig(config);
        step.value = 'done';
        showToast('配置文件已生成', 'success');
        setTimeout(() => emit('complete'), 1500);
      } catch (e) {
        error.value = e.message;
      }
    }

    function skip() {
      emit('close');
    }

    return { Icons, step, sourceRoot, baseUrl, scanResult, error, scanning, startScan, confirmGenerate, skip };
  },
  template: `
    <div class="modal-overlay" v-if="visible" @click.self="skip">
      <div class="modal">
        <!-- Step: Input -->
        <template v-if="step === 'input'">
          <div class="modal-header">
            <div class="modal-title">项目接口对接</div>
            <div class="modal-subtitle">她还在等待——在世界的背面，等你迈出第一步</div>
          </div>
          <div class="modal-body">
            <div class="input-group">
              <label class="input-label">项目目录路径</label>
              <input class="input" v-model="sourceRoot"
                     placeholder="/path/to/your/project"
                     @keydown.enter="startScan" />
            </div>
            <div class="input-group mt-4">
              <label class="input-label">服务 Base URL <span class="text-xs text-ash">（可选）</span></label>
              <input class="input" v-model="baseUrl"
                     placeholder="http://localhost:8081"
                     @keydown.enter="startScan" />
            </div>
            <div v-if="error" class="text-sm" style="color: var(--error);">{{ error }}</div>
            <p class="text-sm text-ash mt-4">
              输入本地项目的根目录，系统将自动扫描接口定义。
              首次扫描需要时间，请耐心等待。
            </p>
          </div>
          <div class="modal-footer">
            <button class="btn btn-ghost" @click="skip">稍后再说</button>
            <button class="btn btn-primary" @click="startScan">开始扫描</button>
          </div>
        </template>

        <!-- Step: Scanning -->
        <template v-if="step === 'scanning'">
          <div class="modal-header">
            <div class="modal-title">正在扫描</div>
            <div class="modal-subtitle">涟漪正在扩散，请耐心等待♪</div>
          </div>
          <div class="modal-body" style="text-align: center; padding: 3rem;">
            <div class="loading-dots" style="justify-content: center;">
              <span></span><span></span><span></span>
            </div>
            <p class="text-sm text-ash mt-4">首次扫描需要时间，请耐心等待</p>
          </div>
        </template>

        <!-- Step: Result -->
        <template v-if="step === 'result'">
          <div class="modal-header">
            <div class="modal-title">扫描完成</div>
            <div class="modal-subtitle">
              发现 {{ scanResult?.endpoints?.length || 0 }} 个接口
              <span v-if="scanResult?.source === 'code_scan'" class="tag tag-dusk" style="margin-left: 8px;">AI 生成，请核对</span>
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
                未发现接口
              </div>
            </div>
            <div v-if="error" class="text-sm mt-4" style="color: var(--error);">{{ error }}</div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-ghost" @click="step = 'input'">重新扫描</button>
            <button class="btn btn-ghost" @click="skip">取消</button>
            <button class="btn btn-primary" @click="confirmGenerate">确认生成</button>
          </div>
        </template>

        <!-- Step: Done -->
        <template v-if="step === 'done'">
          <div class="modal-header">
            <div class="modal-title">配置已生成</div>
            <div class="modal-subtitle">涟漪已记录，记忆的种子已播下♪</div>
          </div>
          <div class="modal-body" style="text-align: center; padding: 2rem;">
            <p class="text-sm text-ash">project-apis.json 已生成，可在「配置」页面查看和修改</p>
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
        showToast('请先设置用户 ID', 'error');
        return;
      }

      // Add user message to UI
      messages.value.push({ role: 'user', content: text });
      inputText.value = '';
      scrollToBottom();

      isStreaming.value = true;
      const assistantMsg = { role: 'assistant', content: '' };
      messages.value.push(assistantMsg);

      try {
        const resp = await CyreneAPI.chat(currentSessionId.value, text, {
          userId: userId.value,
          outputMode: 'streaming',
        });

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            if (line.startsWith('event: ')) {
              const eventType = line.slice(7).trim();
              continue;
            }
            if (line.startsWith('data: ')) {
              const data = line.slice(6);
              try {
                const parsed = JSON.parse(data);
                if (parsed.sessionId && !currentSessionId.value) {
                  currentSessionId.value = parsed.sessionId;
                }
                if (parsed.text) {
                  assistantMsg.content += parsed.text;
                  scrollToBottom();
                }
                if (parsed.output) {
                  assistantMsg.content = parsed.output;
                  scrollToBottom();
                }
              } catch (e) {
                // not JSON, treat as token
                assistantMsg.content += data;
                scrollToBottom();
              }
            }
          }
        }

        // Reload sessions list
        loadSessions();
      } catch (e) {
        assistantMsg.content = `Error: ${e.message}`;
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
      Icons, sessions, currentSessionId, messages, inputText, isStreaming,
      messagesEl, userId,
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
              新对话
            </button>
          </div>
          <div style="flex: 1; overflow-y: auto; padding: var(--space-2);">
            <div v-for="s in sessions" :key="s.id"
                 :class="['nav-item', currentSessionId === s.id ? 'active' : '']"
                 @click="selectSession(s.id)"
                 style="padding: var(--space-2) var(--space-3); font-size: var(--text-sm);">
              <span class="truncate">{{ s.title || s.id || '未命名对话' }}</span>
            </div>
            <div v-if="!sessions.length" class="p-4 text-center text-xs text-ash">
              暂无对话
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
                <div class="message-content">{{ msg.content }}</div>
              </div>
              <div v-if="isStreaming && !messages[messages.length-1]?.content" class="message message-assistant">
                <div class="message-avatar">C</div>
                <div class="message-content">
                  <div class="loading-dots"><span></span><span></span><span></span></div>
                </div>
              </div>
            </template>
            <empty-state v-else
              :icon="Icons.chat"
              title="涟漪尚未荡起，等待第一个音符♪"
              hint="输入消息开始对话" />
          </div>

          <!-- Input area -->
          <div class="chat-input-area">
            <div class="chat-input-wrapper">
              <textarea class="chat-input" v-model="inputText"
                        placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
                        @keydown="handleKeydown"
                        rows="1"></textarea>
              <div class="chat-actions">
                <button class="chat-action-btn" title="上传文件">
                  <span v-html="Icons.upload" style="width:18px;height:18px;"></span>
                </button>
                <button class="chat-action-btn" title="语音输入">
                  <span v-html="Icons.mic" style="width:18px;height:18px;"></span>
                </button>
                <button class="chat-send-btn" @click="sendMessage"
                        :disabled="!inputText.trim() || isStreaming" title="发送">
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
    const collections = ref([]);
    const selectedCollection = ref('');
    const documents = ref([]);
    const uploading = ref(false);
    const uploadCollection = ref('');
    const fileInput = ref(null);

    async function loadCollections() {
      // No direct list-collections API, we'll use a workaround
      // For now, show empty state and let user upload
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
        showToast('请输入知识库名称', 'error');
        return;
      }
      uploading.value = true;
      try {
        await CyreneAPI.uploadKnowledge(file, uploadCollection.value.trim());
        showToast('上传成功', 'success');
        selectedCollection.value = uploadCollection.value.trim();
        loadDocuments();
      } catch (e) {
        showToast('上传失败: ' + e.message, 'error');
      } finally {
        uploading.value = false;
      }
    }

    async function deleteDoc(docId) {
      try {
        await CyreneAPI.deleteDocument(selectedCollection.value, docId);
        showToast('已删除', 'success');
        loadDocuments();
      } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
      }
    }

    async function deleteCol() {
      if (!selectedCollection.value) return;
      try {
        await CyreneAPI.deleteCollection(selectedCollection.value);
        showToast('知识库已删除', 'success');
        selectedCollection.value = '';
        documents.value = [];
      } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
      }
    }

    watch(selectedCollection, loadDocuments);

    return { Icons, collections, selectedCollection, documents, uploading, uploadCollection, fileInput, loadCollections, loadDocuments, uploadFile, deleteDoc, deleteCol };
  },
  template: `
    <div>
      <!-- Upload section -->
      <div class="card card-gold mb-4">
        <div class="card-header">
          <div class="card-title">上传知识库</div>
        </div>
        <div class="card-body">
          <div style="display: flex; gap: var(--space-3); align-items: flex-end;">
            <div class="input-group" style="flex: 1;">
              <label class="input-label">知识库名称</label>
              <input class="input" v-model="uploadCollection" placeholder="my-knowledge" />
            </div>
            <div class="input-group" style="flex: 1;">
              <label class="input-label">选择文件</label>
              <input type="file" ref="fileInput" class="input" style="padding: 8px;" />
            </div>
            <button class="btn btn-primary" @click="uploadFile" :disabled="uploading" style="white-space: nowrap;">
              {{ uploading ? '上传中...' : '上传' }}
            </button>
          </div>
        </div>
      </div>

      <!-- Browse section -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">知识库浏览</div>
          <div style="display: flex; gap: var(--space-2); align-items: center;">
            <input class="input" v-model="selectedCollection" placeholder="输入知识库名称"
                   style="width: 200px;" @keydown.enter="loadDocuments" />
            <button class="btn btn-ghost" @click="loadDocuments">
              <span v-html="Icons.search" style="width:16px;height:16px;"></span>
            </button>
            <button class="btn btn-danger btn-sm" v-if="selectedCollection" @click="deleteCol">
              <span v-html="Icons.trash" style="width:14px;height:14px;"></span>
            </button>
          </div>
        </div>
        <div class="card-body">
          <template v-if="documents.length">
            <table>
              <thead>
                <tr>
                  <th>文件名</th>
                  <th>大小</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="doc in documents" :key="doc.id">
                  <td>{{ doc.fileName || doc.file_name || doc.id }}</td>
                  <td class="text-ash">{{ doc.chunkCount || '?' }} chunks</td>
                  <td>
                    <button class="btn btn-ghost btn-sm" @click="deleteDoc(doc.id)">
                      <span v-html="Icons.trash" style="width:14px;height:14px;"></span>
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
          <empty-state v-else
            :icon="Icons.knowledge"
            title="记忆的种子尚未播下"
            hint="上传文件以构建知识库" />
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
        showToast('已删除', 'success');
        loadTraces();
      } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
      }
    }

    async function cleanupTraces() {
      try {
        const result = await CyreneAPI.cleanupTraces();
        showToast(`已清理 ${result.deleted || 0} 条记录`, 'success');
        loadTraces();
      } catch (e) {
        showToast('清理失败: ' + e.message, 'error');
      }
    }

    function formatDuration(ms) {
      if (!ms) return '-';
      if (ms < 1000) return ms + 'ms';
      return (ms / 1000).toFixed(1) + 's';
    }

    function formatTime(ts) {
      if (!ts) return '-';
      return new Date(ts).toLocaleString('zh-CN');
    }

    onMounted(loadTraces);

    return { Icons, traces, stats, loading, loadTraces, deleteTrace, cleanupTraces, formatDuration, formatTime };
  },
  template: `
    <div>
      <!-- Stats -->
      <div v-if="stats" style="display: flex; gap: var(--space-4); margin-bottom: var(--space-6);">
        <div class="card" style="flex: 1;">
          <div class="card-body" style="text-align: center;">
            <div style="font-family: var(--font-display); font-size: var(--text-2xl); color: var(--rose);">{{ stats.count || 0 }}</div>
            <div class="text-sm text-ash">总记录数</div>
          </div>
        </div>
        <div class="card" style="flex: 1;">
          <div class="card-body" style="text-align: center;">
            <div style="font-family: var(--font-display); font-size: var(--text-2xl); color: var(--gold);">{{ stats.retentionDays || 30 }}</div>
            <div class="text-sm text-ash">保留天数</div>
          </div>
        </div>
      </div>

      <!-- Traces table -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">审计记录</div>
          <div style="display: flex; gap: var(--space-2);">
            <button class="btn btn-ghost btn-sm" @click="loadTraces">
              <span v-html="Icons.refresh" style="width:14px;height:14px;"></span>
            </button>
            <button class="btn btn-danger btn-sm" @click="cleanupTraces">清理过期</button>
          </div>
        </div>
        <div class="card-body">
          <template v-if="traces.length">
            <div class="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Trace ID</th>
                    <th>用户</th>
                    <th>风险</th>
                    <th>耗时</th>
                    <th>时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="t in traces" :key="t.id">
                    <td class="text-xs" style="font-family: monospace; color: var(--iris);">{{ (t.id || '').slice(0, 8) }}...</td>
                    <td>{{ t.userId || '-' }}</td>
                    <td>
                      <span :class="['tag', t.risk === 'HIGH' ? 'tag-rose' : t.risk === 'MEDIUM' ? 'tag-gold' : 'tag-iris']">
                        {{ t.risk || '-' }}
                      </span>
                    </td>
                    <td class="text-ash">{{ formatDuration(t.durationMs) }}</td>
                    <td class="text-xs text-dusk">{{ formatTime(t.createdAt) }}</td>
                    <td>
                      <button class="btn btn-ghost btn-sm" @click="deleteTrace(t.id)">
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
            title="旅途尚未开始，无痕可寻"
            hint="对话和工具调用的审计记录将显示在这里" />
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
          configText.value = '{\n  "discoveredAt": "",\n  "sourceRoot": "",\n  "endpoints": []\n}';
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
        showToast('配置已保存', 'success');
        configObj.value = parsed;
      } catch (e) {
        if (e instanceof SyntaxError) {
          error.value = 'JSON 格式错误: ' + e.message;
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
        showToast('配置已重新加载', 'success');
      } catch (e) {
        showToast('重载失败: ' + e.message, 'error');
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

    return { Icons, configText, configObj, loading, saving, error, loadConfig, saveConfig, reloadConfig, handleKeydown };
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
              重新加载
            </button>
            <button class="btn btn-ghost btn-sm" @click="reloadConfig">
              热加载到工具
            </button>
            <button class="btn btn-primary btn-sm" @click="saveConfig" :disabled="saving">
              <span v-html="Icons.save" style="width:14px;height:14px;"></span>
              {{ saving ? '保存中...' : '保存' }}
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
              修改后点击「保存」写入文件，再点击「热加载到工具」使配置生效（无需重启服务）
            </div>
          </template>
        </div>
      </div>

      <!-- Endpoint summary -->
      <div class="card mt-4" v-if="configObj?.endpoints?.length">
        <div class="card-header">
          <div class="card-title">已配置接口 ({{ configObj.endpoints.length }})</div>
        </div>
        <div class="card-body">
          <table>
            <thead>
              <tr>
                <th>方法</th>
                <th>路径</th>
                <th>名称</th>
                <th>鉴权</th>
                <th>状态</th>
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
                    {{ ep.confirmed ? '已启用' : '未启用' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <empty-state v-if="!loading && !configObj?.endpoints?.length"
        :icon="Icons.config"
        title="她还在等待——在世界的背面，等你迈出第一步"
        hint="运行首次扫描或手动创建配置文件" />
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
    const showPreConfig = ref(false);
    const configExists = ref(true);
    const pageTransitioning = ref(false);

    // ── Global userId (persisted in localStorage) ──
    const userId = ref(localStorage.getItem('cyrene_user') || '');
    const showWelcome = ref(!userId.value);
    const editingUser = ref(false);
    const editUserId = ref('');

    provide('userId', userId);

    function confirmUserId() {
      const val = editUserId.value.trim();
      if (!val) return;
      userId.value = val;
      localStorage.setItem('cyrene_user', val);
      showWelcome.value = false;
      editingUser.value = false;
      showToast('用户 ID 已设置: ' + val, 'success');
    }

    function startEditUser() {
      editUserId.value = userId.value;
      editingUser.value = true;
    }

    function cancelEditUser() {
      editingUser.value = false;
    }

    const navItems = [
      { id: 'chat', label: '对话', icon: Icons.chat },
      { id: 'knowledge', label: '知识库', icon: Icons.knowledge },
      { id: 'audit', label: '审计', icon: Icons.audit },
      { id: 'config', label: '配置', icon: Icons.config },
    ];

    const pageTitle = computed(() => {
      const item = navItems.find(n => n.id === currentPage.value);
      return item ? item.label : 'Cyrene';
    });

    function navigate(page) {
      if (page === currentPage.value) return;
      pageTransitioning.value = true;
      setTimeout(() => {
        currentPage.value = page;
        pageTransitioning.value = false;
        window.location.hash = page;
      }, 200);
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
      if (navItems.find(n => n.id === hash)) {
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
      Icons, currentPage, sidebarOpen, showPreConfig, configExists, pageTransitioning,
      navItems, pageTitle,
      userId, showWelcome, editingUser, editUserId,
      confirmUserId, startEditUser, cancelEditUser,
      navigate, toggleSidebar, onPreConfigComplete, onPreConfigClose,
    };
  },
  template: `
    <div>
      <stars-background />
      <toast-container />

      <!-- Page transition ripple -->
      <div v-if="pageTransitioning" class="page-ripple"></div>

      <!-- Welcome modal (first visit only) -->
      <div v-if="showWelcome" class="modal-overlay">
        <div class="modal" style="max-width: 420px;">
          <div class="modal-header">
            <div class="modal-title">欢迎来到 Cyrene</div>
            <div class="modal-subtitle">涟漪尚未荡起，等待第一个音符♪</div>
          </div>
          <div class="modal-body">
            <div class="input-group">
              <label class="input-label">用户 ID</label>
              <input class="input" v-model="editUserId"
                     placeholder="输入你的用户 ID"
                     @keydown.enter="confirmUserId" autofocus />
              <div class="text-xs text-ash mt-2">
                用于标识你的会话和记忆，只需设置一次，后续自动使用
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-primary" @click="confirmUserId" :disabled="!editUserId.trim()">
              进入 Cyrene
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
        <aside :class="['sidebar', sidebarOpen ? 'open' : '']">
          <div class="sidebar-header">
            <div class="sidebar-logo">
              <span>Cyrene</span>
              <span class="logo-accent">♪</span>
            </div>
          </div>
          <nav class="sidebar-nav">
            <button v-for="item in navItems" :key="item.id"
                    :class="['nav-item', currentPage === item.id ? 'active' : '']"
                    @click="navigate(item.id); sidebarOpen = false">
              <span class="nav-icon" v-html="item.icon"></span>
              <span class="nav-label">{{ item.label }}</span>
            </button>
          </nav>
          <div class="sidebar-footer">
            在时间的涟漪中
          </div>
        </aside>

        <!-- Main content -->
        <main class="main-content">
          <header class="header">
            <h2 class="header-title">{{ pageTitle }}</h2>
            <div class="header-actions">
              <!-- User ID badge (click to edit) -->
              <div class="user-badge" @click="startEditUser" style="cursor: pointer;" title="点击修改用户 ID">
                <span class="user-dot"></span>
                <span>{{ userId || '未设置' }}</span>
              </div>
            </div>
          </header>

          <!-- Inline user ID editor -->
          <div v-if="editingUser" style="padding: 0 var(--space-6); background: var(--surface); border-bottom: 1px solid var(--gold-line);">
            <div style="display: flex; gap: var(--space-3); align-items: center; padding: var(--space-3) 0;">
              <input class="input" v-model="editUserId" placeholder="用户 ID"
                     style="flex: 1; max-width: 300px;"
                     @keydown.enter="confirmUserId" @keydown.escape="cancelEditUser" />
              <button class="btn btn-primary btn-sm" @click="confirmUserId" :disabled="!editUserId.trim()">确认</button>
              <button class="btn btn-ghost btn-sm" @click="cancelEditUser">取消</button>
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

// Register Icons as global property so all components can access it
app.config.globalProperties.Icons = Icons;
app.provide('Icons', Icons);
app.mount('#app');
