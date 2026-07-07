/**
 * Cyrene i18n — 独立语言包
 * 新增语言只需在 messages 中添加新 key 即可
 */
const messages = {
  zh: {
    // Shared
    cancel: '取消', confirm: '确认', save: '保存', saving: '保存中...', delete: '删除',
    deleted: '已删除', deleteFailed: '删除失败: ', operation: '操作', source: '来源',
    enabled: '已启用', disabled: '未启用', retry: '重试', edit: '编辑', chunks: '分块',
    back: '← 返回列表', current: '当前：', id: 'ID：', method: '方法', path: '路径',
    name: '名称', auth: '鉴权', status: '状态', time: '时间', risk: '风险', user: '用户',
    uploadFailed: '上传失败: ', loadFailed: '加载失败: ',
    // Nav
    chat: '对话', knowledge: '知识库', audit: '审计', config: '配置',
    // Root App
    userIdSet: '用户 ID 已设置: ', welcomeTitle: '欢迎来到 Cyrene',
    welcomeSubtitle: '涟漪尚未荡起，等待第一个音符♪', userId: '用户 ID',
    enterUserId: '输入你的用户 ID', userIdHint: '用于标识你的会话和记忆，只需设置一次，后续自动使用',
    enterCyrene: '进入 Cyrene', expandSidebar: '展开侧边栏', collapseSidebar: '收起侧边栏',
    inTimeRipples: '在时间的涟漪中', clickToEditUserId: '点击修改用户 ID', unset: '未设置',
    // ChatPage
    setUserIdFirst: '请先设置用户 ID', majorCompress: '大压缩', minorCompress: '小压缩',
    unknownError: '未知错误', requestFailed: '请求失败', newChat: '新对话',
    unnamedChat: '未命名对话', noChats: '暂无对话',
    chatEmptyTitle: '涟漪尚未荡起，等待第一个音符♪', chatEmptyHint: '输入消息开始对话',
    chatPlaceholder: '输入消息... (Enter 发送, Shift+Enter 换行)',
    uploadFile: '上传文件', voiceInput: '语音输入', send: '发送',
    // KnowledgePage
    enterCollectionName: '请输入知识库名称', uploadSuccess: '上传成功',
    collectionDeleted: '知识库已删除', uploadKnowledge: '上传知识库',
    collectionName: '知识库名称', chooseFile: '选择文件', uploading: '上传中...',
    upload: '上传', browseKnowledge: '知识库浏览',
    chunksSource: '来源', chunksCount: '分块',
    deleteDoc: '删除', saved: '已保存', saveFailed: '保存失败: ', seedsNotSown: '记忆的种子尚未播下',
    uploadToBuild: '上传文件以构建知识库', noDocsInCollection: '该知识库暂无文档',
    editChunk: '编辑文档分块',
    // AuditPage
    cleanedNRecords: '条记录已清理', totalRecords: '总记录数',
    retentionDays: '保留天数', auditRecords: '审计记录', cleanupExpired: '清理过期',
    traceId: 'Trace ID', duration: '耗时', journeyNotStarted: '旅途尚未开始，无痕可寻',
    auditHint: '对话和工具调用的审计记录将显示在这里',
    // ConfigPage
    configSaved: '配置已保存', jsonError: 'JSON 格式错误: ',
    configReloaded: '配置已重新加载', reloadFailed: '重载失败: ',
    reload: '重新加载', hotReload: '热加载到工具',
    configHint: '修改后点击「保存」写入文件，再点击「热加载到工具」使配置生效（无需重启服务）',
    configuredEndpoints: '已配置接口', waitingForYou: '她还在等待——在世界的背面，等你迈出第一步',
    scanOrCreateHint: '运行首次扫描或手动创建配置文件',
    // PreConfigModal
    enterProjectPath: '请输入项目目录路径', configGenerated: '配置文件已生成',
    projectApiSetup: '项目接口对接',
    projectSubtitle: '她还在等待——在世界的背面，等你迈出第一步',
    projectPath: '项目目录路径', serviceBaseUrl: '服务 Base URL', optional: '（可选）',
    scanInstructions1: '输入本地项目的根目录，系统将自动扫描接口定义。',
    scanInstructions2: '首次扫描需要时间，请耐心等待。',
    later: '稍后再说', startScan: '开始扫描', scanning: '正在扫描',
    scanningSubtitle: '涟漪正在扩散，请耐心等待♪', scanningHint: '首次扫描需要时间，请耐心等待',
    scanComplete: '扫描完成', foundNEndpoints: '个接口', aiGenerated: 'AI 生成，请核对',
    noEndpoints: '未发现接口', rescan: '重新扫描', confirmGenerate: '确认生成',
    configDone: '配置已生成', configDoneSubtitle: '涟漪已记录，记忆的种子已播下♪',
    configDoneHint: 'project-apis.json 已生成，可在「配置」页面查看和修改',
  },
  en: {
    // Shared
    cancel: 'Cancel', confirm: 'Confirm', save: 'Save', saving: 'Saving...', delete: 'Delete',
    deleted: 'Deleted', deleteFailed: 'Delete failed: ', operation: 'Operation', source: 'Source',
    enabled: 'Enabled', disabled: 'Disabled', retry: 'Retry', edit: 'Edit', chunks: 'Chunks',
    back: '← Back to list', current: 'Current: ', id: 'ID: ', method: 'Method', path: 'Path',
    name: 'Name', auth: 'Auth', status: 'Status', time: 'Time', risk: 'Risk', user: 'User',
    uploadFailed: 'Upload failed: ', loadFailed: 'Load failed: ',
    // Nav
    chat: 'Chat', knowledge: 'Knowledge', audit: 'Audit', config: 'Config',
    // Root App
    userIdSet: 'User ID set: ', welcomeTitle: 'Welcome to Cyrene',
    welcomeSubtitle: 'The ripples have yet to rise, awaiting the first note ♪', userId: 'User ID',
    enterUserId: 'Enter your User ID', userIdHint: 'Used to identify your sessions and memory. Set once, used automatically.',
    enterCyrene: 'Enter Cyrene', expandSidebar: 'Expand sidebar', collapseSidebar: 'Collapse sidebar',
    inTimeRipples: 'In the ripples of time', clickToEditUserId: 'Click to edit User ID', unset: 'Not set',
    // ChatPage
    setUserIdFirst: 'Please set your User ID first', majorCompress: 'Major compress', minorCompress: 'Minor compress',
    unknownError: 'Unknown error', requestFailed: 'Request failed', newChat: 'New Chat',
    unnamedChat: 'Untitled', noChats: 'No conversations yet',
    chatEmptyTitle: 'The ripples have yet to rise, awaiting the first note ♪', chatEmptyHint: 'Type a message to start chatting',
    chatPlaceholder: 'Type a message... (Enter to send, Shift+Enter for new line)',
    uploadFile: 'Upload file', voiceInput: 'Voice input', send: 'Send',
    // KnowledgePage
    enterCollectionName: 'Please enter a collection name', uploadSuccess: 'Upload successful',
    collectionDeleted: 'Collection deleted', uploadKnowledge: 'Upload Knowledge',
    collectionName: 'Collection name', chooseFile: 'Choose file', uploading: 'Uploading...',
    upload: 'Upload', browseKnowledge: 'Browse Knowledge',
    chunksSource: 'Source', chunksCount: 'Chunks',
    deleteDoc: 'Delete', saved: 'Saved', saveFailed: 'Save failed: ', seedsNotSown: 'Seeds of memory not yet sown',
    uploadToBuild: 'Upload files to build your knowledge base', noDocsInCollection: 'No documents in this collection',
    editChunk: 'Edit Document Chunk',
    // AuditPage
    cleanedNRecords: 'records cleaned', totalRecords: 'Total Records',
    retentionDays: 'Retention Days', auditRecords: 'Audit Records', cleanupExpired: 'Cleanup Expired',
    traceId: 'Trace ID', duration: 'Duration', journeyNotStarted: 'The journey has not begun, no traces to find',
    auditHint: 'Audit records of conversations and tool calls will appear here',
    // ConfigPage
    configSaved: 'Config saved', jsonError: 'JSON format error: ',
    configReloaded: 'Config reloaded', reloadFailed: 'Reload failed: ',
    reload: 'Reload', hotReload: 'Hot Reload to Tools',
    configHint: 'Click "Save" to write to file, then "Hot Reload to Tools" to apply (no restart needed)',
    configuredEndpoints: 'Configured Endpoints', waitingForYou: 'She is still waiting — on the other side of the world, for you to take the first step',
    scanOrCreateHint: 'Run a scan or manually create the config file',
    // PreConfigModal
    enterProjectPath: 'Please enter the project directory path', configGenerated: 'Config file generated',
    projectApiSetup: 'Project API Setup',
    projectSubtitle: 'She is still waiting — on the other side of the world, for you to take the first step',
    projectPath: 'Project directory path', serviceBaseUrl: 'Service Base URL', optional: '(optional)',
    scanInstructions1: 'Enter the root directory of your local project. The system will automatically scan for API definitions.',
    scanInstructions2: 'The first scan takes time, please be patient.',
    later: 'Later', startScan: 'Start Scan', scanning: 'Scanning',
    scanningSubtitle: 'The ripples are spreading, please wait ♪', scanningHint: 'The first scan takes time, please be patient',
    scanComplete: 'Scan Complete', foundNEndpoints: 'endpoints found', aiGenerated: 'AI generated, please verify',
    noEndpoints: 'No endpoints found', rescan: 'Rescan', confirmGenerate: 'Confirm & Generate',
    configDone: 'Config Generated', configDoneSubtitle: 'The ripples are recorded, seeds of memory have been sown ♪',
    configDoneHint: 'project-apis.json has been generated. View and edit in the "Config" page.',
  },
};

// ── i18n helpers ──
const CyreneI18n = {
  locale: null, // 延迟初始化，因为 Vue ref 在此文件加载时还不可用

  init(VueRef, VueWatch) {
    this.locale = VueRef(localStorage.getItem('cyrene_locale') || 'zh');
    VueWatch(this.locale, (val) => localStorage.setItem('cyrene_locale', val));
    return this.locale;
  },

  t(key) {
    const lang = this.locale?.value || 'zh';
    return messages[lang]?.[key] || messages.zh[key] || key;
  },

  /** 获取当前语言对应的 locale string（用于 toLocaleString 等） */
  localeString() {
    return (this.locale?.value || 'zh') === 'zh' ? 'zh-CN' : 'en-US';
  },

  /** 切换语言 */
  toggle() {
    if (this.locale) {
      this.locale.value = this.locale.value === 'zh' ? 'en' : 'zh';
    }
  },
};
