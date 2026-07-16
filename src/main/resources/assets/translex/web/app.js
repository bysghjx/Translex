/* ============================================================
   Translex Web UI — 应用主逻辑（v2.1）
   依赖：Vue 3 (vue.global.prod.js)、components.js（TranslexComponents）
   ============================================================ */
const { createApp } = Vue;

/* ── 安全访问 localStorage（跟踪防护/隐私模式可能抛 SecurityError） ── */
function safeStorage(op, key, val) {
    try { return op === 'getItem' ? localStorage.getItem(key) : localStorage.setItem(key, val); }
    catch(e) { return op === 'getItem' ? null : undefined; }
}

/* ── i18n 字典（zh/en） ── */
const I18N_DICT = {
    zh: {
        subtitle: 'AI 翻译控制面板',
        connected: '已连接', disconnected: '未连接', switchLangHint: '切换语言',
        dashboard: '运行看板', config: '配置管理', network: '网络抓包', debugTab: '调试',
        debugStatusSse: 'SSE 连接', debugStatusCache: '缓存状态', debugStatusMode: '翻译模式', debugStatusApi: 'API 地址',
        debugNoMatch: '没有匹配的日志行', enabled: '已启用', disabled: '已禁用', liveConsole: '实时控制台',
        cacheHits: '本地规则命中', aiRequests: 'AI 请求数', tokenSaveRate: 'Token 节省率', avgLatency: '平均延迟',
        hitRatio: '命中率分布（本地缓存 vs AI 请求）', latencyTrend: '最近 20 次 API 响应延迟趋势',
        tokenCostTrend: 'Token 消耗趋势（实际）',
        refresh: '刷新数据', loading: '加载中...', reloadConfig: '重新加载', saveConfig: '保存配置', saving: '保存中...',
        apiKey: 'API Key', apiKeyPlaceholder: '请输入你的 API Key...', apiKeyHint: '⬆ 请替换为你的真实 Key',
        showKey: '显示', hideKey: '隐藏',
        apiUrl: 'API URL', apiUrlPlaceholder: 'https://api.deepseek.com/chat/completions',
        model: 'Model（模型标识）', modelPlaceholder: '例如: deepseek-v4-flash, claude-sonnet-4-6',
        provider: 'AI 供应商格式', providerDesc: 'OpenAI 兼容适用于 DeepSeek/OpenAI 等；Anthropic 为 Claude 原生格式',
        maxTokens: '最大输出 Token', maxTokensDesc: 'Anthropic 必需。OpenAI 兼容端点忽略此项',
        anthropicVersion: 'anthropic-version 头', anthropicVersionDesc: '仅 Anthropic 使用，一般保持默认 2023-06-01',
        temperature: '采样温度', temperatureDesc: '0~0.3 降低幻觉；负值表示不发送该字段',
        structuredOutput: 'Structured Output', structuredOutputDesc: '强制 JSON 输出（部分端点不支持）',
        presets: '连接预设', presetsDesc: '保存多套供应商/密钥/模型组合，一键切换。应用后记得点保存配置',
        presetCustom: '（自定义连接）', presetApply: '应用', presetDelete: '删除', presetSaveCurrent: '存为预设', presetNamePlaceholder: '新预设名称',
        targetLanguage: '目标语言（翻译成）', targetLanguageCustomPlaceholder: '自定义语言，如 Italiano', targetLanguageModeToggle: '自定义语言',
        userChatPrompt: '聊天附加指令（可选）', userChatPromptPlaceholder: '留空则不发送。例如：口语化、保留游戏梗',
        userItemPrompt: '物品附加指令（可选）', userItemPromptPlaceholder: '留空则不发送。例如：保留装备词条缩写',
        properNounMode: '专有名词处理', properNounKeep: '保留英文（物品名/附魔/能力）', properNounTranslate: '全部翻译', properNounItemOnly: '只保留物品名',
        saveSuccess: '配置已保存并重载成功 ✓', saveFailed: '配置保存失败，请检查后端日志', loadFailed: '加载配置失败',
        requestPayload: '📤 Request Payload', responseBody: '📥 Response Body', noTraces: '暂无请求记录',
        consolePlaceholder: '等待后端日志推送... 控制台自动滚动到底部。', clear: '清屏',
        themeHint: '切换主题 (系统 → 深色 → 浅色 → 系统)', themeLabel: '主题',
        tokensConsumed: 'Token 消耗', tokensSaved: '缓存节省 (估算)', est: '本地估算', api: 'API 实际',
        tokenUsage: 'Token', estTokens: '请求侧估算', systemPromptLabel: '提示词', payloadLabel: '载荷',
        promptLabel: '输入', completionLabel: '输出', cachedLabel: '服务器端缓存命中', reasoningLabel: '其中 Reasoning', totalLabel: '合计',
        tokenWaste: '输出 token 超过输入 3 倍，建议检查 System Prompt 是否过于复杂，或模型是否开启了 thinking 模式',
        reasoningWaste: '检测到 Reasoning token ({n})！建议在 API 请求中关闭 thinking 模式以节省成本',
        sectionApi: 'API 连接', sectionBehavior: '翻译行为', sectionLangPrompt: '语言与提示词', sectionCache: '缓存设置', sectionCompact: '聊天折叠',
        subTabApi: 'API 连接', subTabBehavior: '翻译行为', subTabCache: '缓存设置', subTabCompact: '聊天折叠',
        translationMode: '翻译模式', translationModeAutoDesc: '自动选择（短消息用文本，长消息用ID）', translationModeIdDesc: '仅消息ID模式', translationModeTextDesc: '仅文本命令模式',
        translationModeHint: 'Auto 模式下短消息直接附带文本，长消息自动切换 ID 模式。需重启生效。',
        debugMode: '调试模式', debugModeDesc: '启动时自动打开浏览器并导航到网络抓包页面',
        welcomeTitle: '🎉 欢迎使用 Translex！',
        welcomeText: '请先在 <code>配置管理</code> 标签页中设置 <b>API Key</b> 以启用翻译功能。<br>配置完成后，返回游戏即可使用翻译按钮或按下 <code>P</code> 键翻译物品 Lore。',
        welcomeDismiss: '知道了',
        buttonStyle: '按钮样式', enableTranslateButton: '显示翻译按钮', enableTranslateButtonDesc: '在聊天消息旁显示 [翻译] 按钮，关闭后按钮不再出现',
        outputMode: '翻译输出模式', outputModeChat: 'chat — 直接替换聊天消息', outputModeTemporary: 'temporary — 临时 Tooltip 显示', outputModePermanent: 'permanent — 永久写入物品缓存',
        styleProtocol: '样式协议', styleProtocolSn: 'sN - 旧版 <sN> 标签（位置 ID，稳定）', styleProtocolTsp: 'TSP - [[ID||TEXT]]（颜色 dedup，省 token，新）',
        enableCachePersistence: '缓存持久化', enableCachePersistenceDesc: '将翻译缓存写入磁盘，重启后保留',
        enablePeriodicSave: '定时自动保存', enablePeriodicSaveDesc: '每隔一定 tick 自动将缓存刷写到磁盘',
        periodicSaveInterval: '保存间隔 (ticks)', periodicSaveIntervalDesc: '24000 ticks ≈ 20 分钟。范围: 1200~240000',
        cacheMaxEntries: '内存缓存上限 (条)', cacheMaxEntriesDesc: '超过后按 LRU 淘汰最久未访问的条目。默认 20000',
        enableChatCompact: '聊天折叠', enableChatCompactDesc: '将连续重复的聊天消息折叠为一条，附带次数标记',
        compactTimeSeconds: '折叠时间窗口 (秒)', compactColorCode: '折叠计数器颜色',
        // 连接测试（新）
        testConnection: '测试连接', testing: '测试中...', testSuccess: '连接成功', testFailed: '连接失败',
        testResultOk: '✓ 成功', testResultFail: '✗ 失败', testLatency: '延迟', testHint: '用当前填写的配置发一个最小请求验证（消耗少量 token）',
        // 未保存提示（新）
        unsavedChanges: '未保存', unsavedWarn: '有未保存的配置改动，确定离开？',
        // Spam
        spamEnable: '启用 Spam Hider', spamEnableDesc: '关闭后所有过滤规则不生效，消息正常显示',
        spamFilters: '过滤规则', spamAdd: '添加规则', spamEmpty: '还没有过滤规则，点击「添加规则」创建一个',
        spamName: '名称', spamPattern: '匹配内容', spamType: '匹配方式', spamState: '状态', spamColorCode: '§码',
        spamNamePlaceholder: '过滤器名称', spamPatternPlaceholder: '要匹配的文字...', spamColorCodeLabel: '§码',
        spamColorCodeHelp: '开启后按带 Minecraft § 颜色码的文本匹配。关闭则按纯文本匹配。', spamDelete: '删除此规则',
        spamSave: '保存规则', spamReload: '重新加载', spamSaved: '规则已保存', spamSaveFailed: '保存失败', spamLoadFailed: '加载过滤规则失败', spamNewFilter: '新过滤器',
        spamRefTitle: '状态说明', spamRefNormal: '消息正常显示在聊天栏，不做任何处理', spamRefHidden: '消息被完全丢弃，不进入聊天栏也不在别处显示', spamRefSeparate: '消息从聊天栏移除，改为在浮动 HUD 中显示',
        spamRefMatchTitle: '匹配方式：', spamRefStartsWith: '前缀匹配', spamRefContains: '包含匹配', spamRefRegex: 'Java 正则',
        spamRefColorCodeNote: '<strong>§码开关：</strong>开启后按带颜色码的文本匹配（如 <code>§r§cThere are no enemies nearby!</code>）。',
    },
    en: {
        subtitle: 'AI Translation Dashboard',
        connected: 'Connected', disconnected: 'Disconnected', switchLangHint: 'Switch Language',
        dashboard: 'Dashboard', config: 'Configuration', network: 'Network Trace', debugTab: 'Debug',
        debugStatusSse: 'SSE Status', debugStatusCache: 'Cache', debugStatusMode: 'Mode', debugStatusApi: 'API URL',
        debugNoMatch: 'No matching log lines', enabled: 'Enabled', disabled: 'Disabled', liveConsole: 'Live Console',
        cacheHits: 'Local Cache Hits', aiRequests: 'AI Requests', tokenSaveRate: 'Token Save Rate', avgLatency: 'Avg Latency',
        hitRatio: 'Hit Ratio (Local Cache vs AI)', latencyTrend: 'Last 20 API Latency Trend',
        tokenCostTrend: 'Token Cost Trend (actual)',
        refresh: 'Refresh', loading: 'Loading...', reloadConfig: 'Reload', saveConfig: 'Save Config', saving: 'Saving...',
        apiKey: 'API Key', apiKeyPlaceholder: 'Enter your API Key...', apiKeyHint: '⬆ Replace with your real key',
        showKey: 'Show', hideKey: 'Hide',
        apiUrl: 'API URL', apiUrlPlaceholder: 'https://api.deepseek.com/chat/completions',
        model: 'Model', modelPlaceholder: 'e.g. deepseek-v4-flash, claude-sonnet-4-6',
        provider: 'AI Provider Format', providerDesc: 'OpenAI-compatible for DeepSeek/OpenAI etc.; Anthropic for Claude native format',
        maxTokens: 'Max Output Tokens', maxTokensDesc: 'Required by Anthropic. Ignored by OpenAI-compatible endpoints',
        anthropicVersion: 'anthropic-version Header', anthropicVersionDesc: 'Anthropic only; usually keep default 2023-06-01',
        temperature: 'Temperature', temperatureDesc: '0~0.3 reduces hallucination; negative to omit the field',
        structuredOutput: 'Structured Output', structuredOutputDesc: 'Force JSON output (unsupported by some endpoints)',
        presets: 'Connection Presets', presetsDesc: 'Save multiple provider/key/model combos, switch with one click. Remember to Save Config after applying',
        presetCustom: '(Custom connection)', presetApply: 'Apply', presetDelete: 'Delete', presetSaveCurrent: 'Save Preset', presetNamePlaceholder: 'New preset name',
        targetLanguage: 'Target Language (translate into)', targetLanguageCustomPlaceholder: 'Custom language, e.g. Italiano', targetLanguageModeToggle: 'Custom language',
        userChatPrompt: 'Extra Chat Instructions (optional)', userChatPromptPlaceholder: 'Empty = not sent. e.g. casual tone, keep memes',
        userItemPrompt: 'Extra Item Instructions (optional)', userItemPromptPlaceholder: 'Empty = not sent. e.g. keep stat abbreviations',
        properNounMode: 'Proper Noun Handling', properNounKeep: 'Keep All English (items/enchants/abilities)', properNounTranslate: 'Translate Everything', properNounItemOnly: 'Keep Item Names Only',
        saveSuccess: 'Config saved & reloaded successfully ✓', saveFailed: 'Failed to save config, check backend logs', loadFailed: 'Failed to load config',
        requestPayload: '📤 Request Payload', responseBody: '📥 Response Body', noTraces: 'No request traces yet',
        consolePlaceholder: 'Waiting for backend log stream... Console auto-scrolls to bottom.', clear: 'Clear',
        themeHint: 'Switch theme (System → Dark → Light → System)', themeLabel: 'Theme',
        tokensConsumed: 'Token Usage', tokensSaved: 'Saved by Cache (est.)', est: 'Local est.', api: 'API actual',
        tokenUsage: 'Token', estTokens: 'Request est. tokens', systemPromptLabel: 'system prompt', payloadLabel: 'payload',
        promptLabel: 'Prompt', completionLabel: 'Completion', cachedLabel: 'Server cache hit', reasoningLabel: 'Of which Reasoning', totalLabel: 'Total',
        tokenWaste: 'Output tokens > 3x input. Consider simplifying System Prompt or disabling thinking mode.',
        reasoningWaste: 'Reasoning tokens detected ({n})! Disable thinking mode in API request to save cost.',
        sectionApi: 'API Connection', sectionBehavior: 'Translation Behavior', sectionLangPrompt: 'Language & Prompts', sectionCache: 'Cache Settings', sectionCompact: 'Chat Compact',
        subTabApi: 'API Connection', subTabBehavior: 'Translation', subTabCache: 'Cache', subTabCompact: 'Chat Compact',
        translationMode: 'Translation Mode', translationModeAutoDesc: 'Auto (short→text, long→ID)', translationModeIdDesc: 'Message ID only', translationModeTextDesc: 'Text command only',
        translationModeHint: 'Auto mode uses text commands for short messages and ID mode for long ones. Requires restart.',
        debugMode: 'Debug Mode', debugModeDesc: 'Auto-open browser to network traces page on startup',
        welcomeTitle: '🎉 Welcome to Translex!',
        welcomeText: 'Please set your <b>API Key</b> in the <code>Configuration</code> tab to enable translation.<br>Once configured, return to the game to use the translate button or press <code>P</code> to translate item lore.',
        welcomeDismiss: 'Got it',
        buttonStyle: 'Button Style', enableTranslateButton: 'Show Translate Button', enableTranslateButtonDesc: 'Show [Translate] button next to chat messages. Turn off to hide the button.',
        outputMode: 'Translation Output Mode', outputModeChat: 'chat — Replace chat message inline', outputModeTemporary: 'temporary — Show as temporary tooltip', outputModePermanent: 'permanent — Save permanently to item cache',
        styleProtocol: 'Style Protocol', styleProtocolSn: 'sN - Legacy <sN> tags (position ID, stable)', styleProtocolTsp: 'TSP - [[ID||TEXT]] (color dedup, saves tokens, new)',
        enableCachePersistence: 'Cache Persistence', enableCachePersistenceDesc: 'Write translation cache to disk, survives restarts',
        enablePeriodicSave: 'Periodic Auto-Save', enablePeriodicSaveDesc: 'Flush cache to disk at regular intervals',
        periodicSaveInterval: 'Save Interval (ticks)', periodicSaveIntervalDesc: '24000 ticks ≈ 20 min. Range: 1200–240000',
        cacheMaxEntries: 'Max Cache Entries', cacheMaxEntriesDesc: 'Evicts least-recently-used entries beyond this. Default 20000',
        enableChatCompact: 'Chat Compact', enableChatCompactDesc: 'Fold duplicate chat messages with a repeat counter',
        compactTimeSeconds: 'Compact Time Window (seconds)', compactColorCode: 'Compact Counter Color',
        testConnection: 'Test Connection', testing: 'Testing...', testSuccess: 'Connection OK', testFailed: 'Connection failed',
        testResultOk: '✓ OK', testResultFail: '✗ Fail', testLatency: 'Latency', testHint: 'Send a minimal request with current config to verify (consumes a few tokens)',
        unsavedChanges: 'Unsaved', unsavedWarn: 'You have unsaved config changes. Leave anyway?',
        spamEnable: 'Enable Spam Hider', spamEnableDesc: 'When disabled, all filter rules are inactive and messages appear normally',
        spamFilters: 'Filter Rules', spamAdd: 'Add Rule', spamEmpty: 'No filter rules yet. Click "Add Rule" to create one.',
        spamName: 'Name', spamPattern: 'Match Pattern', spamType: 'Match Mode', spamState: 'Action', spamColorCode: '§ code',
        spamNamePlaceholder: 'Filter name', spamPatternPlaceholder: 'Text to match...', spamColorCodeLabel: '§ code',
        spamColorCodeHelp: 'When enabled, matches against formatted text with Minecraft § color codes. When disabled, matches plain text only.', spamDelete: 'Delete this rule',
        spamSave: 'Save Rules', spamReload: 'Reload', spamSaved: 'Rules saved', spamSaveFailed: 'Failed to save', spamLoadFailed: 'Failed to load filter rules', spamNewFilter: 'New Filter',
        spamRefTitle: 'Action Reference', spamRefNormal: 'Message displays normally in chat, no filtering applied', spamRefHidden: 'Message is completely discarded from chat and all other displays', spamRefSeparate: 'Message is removed from chat and shown in a floating HUD overlay',
        spamRefMatchTitle: 'Match Modes: ', spamRefStartsWith: 'prefix match', spamRefContains: 'contains match', spamRefRegex: 'Java regex',
        spamRefColorCodeNote: '<strong>§ code toggle:</strong> When enabled, matches against text with color codes (e.g. <code>§r§cThere are no enemies nearby!</code>).',
    }
};

const PRESET_LANGUAGES = [
    'Simplified Chinese (简体中文)', 'English', '日本語 (Japanese)', '繁體中文 (Traditional Chinese)',
    '한국어 (Korean)', 'Français', 'Deutsch', 'Español', 'Русский (Russian)', 'Português',
];

/* ── Vue 应用 ── */
function buildApp() {
const app = createApp({
    data() {
        return {
            currentLang: 'zh', lang: I18N_DICT.zh,
            activeTab: 'dashboard', showWelcome: false,
            tabs: [ {id:'dashboard',label:''}, {id:'config',label:''}, {id:'network',label:''}, {id:'debug',label:''}, {id:'spam',label:''} ],
            configSubTab: 'api',
            configSubTabs: [ {id:'api',label:''}, {id:'behavior',label:''}, {id:'cache',label:''}, {id:'compact',label:''} ],
            connected: false, token: '',
            theme: 'system', systemDark: false,
            config: {
                apiKey:'', apiUrl:'', model:'', provider:'openai', maxTokens:4096, anthropicVersion:'2023-06-01',
                presets:[], activePreset:'',
                targetLanguage:'Simplified Chinese (简体中文)', targetLanguageMode:'preset',
                userChatPrompt:'', userItemPrompt:'', properNounMode:'keep',
                translationMode:'auto', buttonStyle:'NORMAL', enableTranslateButton:true, outputMode:'chat', styleProtocol:'TSP',
                enableCachePersistence:true, enablePeriodicSave:true, periodicSaveInterval:24000, cacheMaxEntries:20000,
                enableChatCompact:true, compactTimeSeconds:120, compactColorCode:'GRAY', debug:false,
                temperature:0.3, structuredOutput:false,
            },
            configBaseline: null,   // 未保存检测基线（深拷贝）
            configLoading: false, configSaving: false,
            availableProviders: [{id:'openai',name:'OpenAI Compatible'},{id:'anthropic',name:'Anthropic (Claude)'}],
            selectedPreset: '', newPresetName: '',
            showApiKey: false,
            metrics: { localHits:0, aiRequests:0, totalEstimatedTokens:0, totalSavedTokens:0, totalActualTokens:0, hasActualTokenData:false, latencyHistory:[] },
            metricsLoading: false,
            traces: [], consoleLines: [], consoleLevelFilter: 'all',
            donutChart: null, lineChart: null, costChart: null,
            toasts: [],
            // 连接测试
            testing: false, testResult: null,  // {success, httpCode, latencyMs, message, sampleResponse?}
            // Spam
            spamEnabled: true, spamFilters: [], spamLoaded: false, spamLoading: false, spamSaving: false,
            eventSource: null, metricsTimer: null, tracesTimer: null,
            presetLanguages: PRESET_LANGUAGES,
            // 历史成本数据点（用于趋势图）
            costHistory: [],
        };
    },
    provide() { return { lang: () => this.lang }; },
    computed: {
        tokenSaveRate() { const t = this.metrics.localHits + this.metrics.aiRequests; return t === 0 ? 0 : Math.round(this.metrics.localHits / t * 100); },
        avgLatency() { const a = this.metrics.latencyHistory; if (!a || !a.length) return 0; return Math.round(a.reduce((x,y)=>x+y,0) / a.length); },
        themeIcon() { return this.theme === 'system' ? '🌓' : this.theme === 'dark' ? '🌙' : '☀️'; },
        filteredConsoleLines() { if (this.consoleLevelFilter === 'all') return this.consoleLines; return this.consoleLines.filter(l => l.level === this.consoleLevelFilter); },
        isApiKeyPlaceholder() { return !this.config.apiKey || this.config.apiKey === 'YOUR_API_KEY_HERE'; },
        apiKeyInputType() { return this.isApiKeyPlaceholder ? 'text' : (this.showApiKey ? 'text' : 'password'); },
        hasUnsavedChanges() {
            if (!this.configBaseline) return false;
            return JSON.stringify(this.configBaseline) !== JSON.stringify(this.sanitizedConfig());
        },
        // select 组件用的 options
        providerOptions() { return this.availableProviders.map(p => ({value:p.id, label:p.name})); },
        properNounOptions() { return [
            {value:'keep', label:this.lang.properNounKeep},
            {value:'translate', label:this.lang.properNounTranslate},
            {value:'item_only', label:this.lang.properNounItemOnly},
        ]; },
        translationModeOptions() { return [
            {value:'auto', label:'auto — '+this.lang.translationModeAutoDesc},
            {value:'message_id', label:'message_id — '+this.lang.translationModeIdDesc},
            {value:'text', label:'text — '+this.lang.translationModeTextDesc},
        ]; },
        buttonStyleOptions() { return [{value:'NORMAL',label:'NORMAL'},{value:'COMPACT',label:'COMPACT ([T])'}]; },
        outputModeOptions() { return [
            {value:'chat', label:this.lang.outputModeChat},
            {value:'temporary', label:this.lang.outputModeTemporary},
            {value:'permanent', label:this.lang.outputModePermanent},
        ]; },
        styleProtocolOptions() { return [
            {value:'sN', label:this.lang.styleProtocolSn},
            {value:'TSP', label:this.lang.styleProtocolTsp},
        ]; },
        compactColorOptions() { return ['GRAY','DARK_GRAY','GREEN','DARK_GREEN','AQUA','YELLOW','GOLD','RED'].map(c=>({value:c,label:c})); },
        debugStatusCards() { return [
            { label:this.lang.debugStatusSse, value:this.connected?this.lang.connected:this.lang.disconnected, color:this.connected?'success':'danger' },
            { label:this.lang.debugStatusCache, value:this.config.debug?this.lang.disabled:this.lang.enabled, color:this.config.debug?'warning':'success' },
            { label:this.lang.debugStatusMode, value:this.config.translationMode||'auto', color:'accent' },
            { label:this.lang.debugStatusApi, value:this.config.apiUrl||'—', color:'accent', title:this.config.apiUrl },
        ]; },
    },
    watch: {
        activeTab(val) {
            if (val === 'dashboard') { this.$nextTick(() => this.updateCharts()); }
            if (val === 'spam' && !this.spamLoaded) { this.loadSpamFilters(); }
            if (val !== 'config' && this.hasUnsavedChanges && !confirm(this.lang.unsavedWarn)) { this.activeTab = 'config'; }
        },
        consoleLines() { /* console-panel 组件内部自动滚动 */ },
        currentLang() { this.updateTabLabels(); },
        theme() { this.$nextTick(() => this.updateCharts()); },
    },
    methods: {
        /* ── i18n / 工具 ── */
        apiUrl(path) { const sep = path.includes('?') ? '&' : '?'; return path + sep + 'token=' + encodeURIComponent(this.token); },
        detectLanguage() {
            const params = new URLSearchParams(location.search);
            this.token = params.get('token') || '';
            const langParam = params.get('lang');
            if (langParam === 'zh' || langParam === 'en') this.currentLang = langParam;
            else this.currentLang = (navigator.language || 'zh').startsWith('zh') ? 'zh' : 'en';
            this.lang = I18N_DICT[this.currentLang];
            this.updateTabLabels();
        },
        switchLanguage() {
            this.currentLang = this.currentLang === 'zh' ? 'en' : 'zh';
            this.lang = I18N_DICT[this.currentLang];
            const url = new URL(location.href); url.searchParams.set('lang', this.currentLang);
            history.replaceState(null, '', url);
        },
        updateTabLabels() {
            const L = this.lang;
            this.tabs[0].label=L.dashboard; this.tabs[1].label=L.config; this.tabs[2].label=L.network; this.tabs[3].label=L.debugTab; this.tabs[4].label='🛡 Spam Hider';
            this.configSubTabs[0].label=L.subTabApi; this.configSubTabs[1].label=L.subTabBehavior; this.configSubTabs[2].label=L.subTabCache; this.configSubTabs[3].label=L.subTabCompact;
        },
        handleHashNavigation() {
            const hash = location.hash.replace('#','');
            const valid = ['dashboard','config','network','debug','spam'];
            if (valid.includes(hash)) this.activeTab = hash;
            if (hash === 'welcome') { this.showWelcome = true; }
        },
        /* ── 主题 ── */
        initTheme() {
            const saved = safeStorage('getItem', 'translex-theme');
            this.theme = saved || 'system';
            // MediaQueryList 留在局部变量（存进 data 会被 Vue reactivity Proxy 化，导致 change 监听失效）
            const mq = matchMedia('(prefers-color-scheme: dark)');
            this.systemDark = mq.matches;
            mq.addEventListener('change', e => {
                this.systemDark = e.matches;
                if (this.theme === 'system') this.applyTheme();
            });
            // 切回 WebUI 标签页时重新读取系统主题（处理切系统后未触发 change 的情况）
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden && this.theme === 'system') {
                    this.systemDark = matchMedia('(prefers-color-scheme: dark)').matches;
                    this.applyTheme();
                }
            });
            this.applyTheme();
        },
        cycleTheme() {
            this.theme = this.theme === 'system' ? 'dark' : this.theme === 'dark' ? 'light' : 'system';
            safeStorage('setItem', 'translex-theme', this.theme);
            this.applyTheme();
        },
        applyTheme() {
            const dark = this.theme === 'dark' || (this.theme === 'system' && this.systemDark);
            document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
        },
        /* ── Toast ── */
        showToast(message, type) {
            const t = { message, type: type||'info', id: Date.now()+Math.random() };
            this.toasts.push(t);
            setTimeout(() => { const i = this.toasts.indexOf(t); if (i>-1) this.toasts.splice(i,1); }, 3500);
        },
        /* ── 配置 ── */
        sanitizedConfig() {
            // 仅取要保存的字段（剔除 targetLanguageMode 前端状态）
            const c = this.config;
            return {
                apiKey:c.apiKey, apiUrl:c.apiUrl, model:c.model, provider:c.provider, maxTokens:c.maxTokens, anthropicVersion:c.anthropicVersion,
                presets:c.presets, activePreset:c.activePreset,
                targetLanguage:c.targetLanguage, userChatPrompt:c.userChatPrompt, userItemPrompt:c.userItemPrompt, properNounMode:c.properNounMode,
                translationMode:c.translationMode, buttonStyle:c.buttonStyle, enableTranslateButton:c.enableTranslateButton, outputMode:c.outputMode, styleProtocol:c.styleProtocol,
                enableCachePersistence:c.enableCachePersistence, enablePeriodicSave:c.enablePeriodicSave, periodicSaveInterval:c.periodicSaveInterval, cacheMaxEntries:c.cacheMaxEntries,
                enableChatCompact:c.enableChatCompact, compactTimeSeconds:c.compactTimeSeconds, compactColorCode:c.compactColorCode, debug:c.debug,
                temperature:c.temperature, structuredOutput:c.structuredOutput,
            };
        },
        async fetchConfig() {
            this.configLoading = true;
            try {
                const res = await fetch(this.apiUrl('/api/config'));
                if (!res.ok) throw new Error('HTTP '+res.status);
                const d = await res.json();
                const c = this.config;
                c.apiKey=d.apiKey||''; c.apiUrl=d.apiUrl||''; c.model=d.model||''; c.provider=d.provider||'openai';
                c.maxTokens=d.maxTokens||4096; c.anthropicVersion=d.anthropicVersion||'2023-06-01';
                c.presets=Array.isArray(d.presets)?d.presets:[]; c.activePreset=d.activePreset||'';
                this.selectedPreset=d.activePreset||'';
                c.targetLanguage=d.targetLanguage||'Simplified Chinese (简体中文)';
                c.targetLanguageMode=d.targetLanguageMode||'preset';
                c.userChatPrompt=d.userChatPrompt||''; c.userItemPrompt=d.userItemPrompt||''; c.properNounMode=d.properNounMode||'keep';
                c.translationMode=d.translationMode||'auto'; c.buttonStyle=d.buttonStyle||'NORMAL';
                c.enableTranslateButton=d.enableTranslateButton!==false; c.outputMode=d.outputMode||'chat'; c.styleProtocol=d.styleProtocol||'sN';
                c.enableCachePersistence=d.enableCachePersistence!==false; c.enablePeriodicSave=d.enablePeriodicSave!==false;
                c.periodicSaveInterval=d.periodicSaveInterval||24000; c.cacheMaxEntries=d.cacheMaxEntries||20000;
                c.enableChatCompact=d.enableChatCompact!==false; c.compactTimeSeconds=d.compactTimeSeconds||120;
                c.compactColorCode=d.compactColorCode||'GRAY'; c.debug=!!d.debug;
                c.temperature=(typeof d.temperature==='number')?d.temperature:0.3; c.structuredOutput=!!d.structuredOutput;
                if (Array.isArray(d.availableProviders) && d.availableProviders.length) this.availableProviders = d.availableProviders;
                this.configBaseline = JSON.parse(JSON.stringify(this.sanitizedConfig()));
            } catch(e) { this.showToast(this.lang.loadFailed+': '+e.message, 'error'); }
            finally { this.configLoading = false; }
        },
        async saveConfig() {
            this.configSaving = true;
            try {
                const res = await fetch(this.apiUrl('/api/config/save'), {
                    method:'POST', headers:{'Content-Type':'application/json'},
                    body: JSON.stringify(this.sanitizedConfig())
                });
                const d = await res.json();
                if (d.success) { this.showToast(this.lang.saveSuccess, 'success'); this.configBaseline = JSON.parse(JSON.stringify(this.sanitizedConfig())); }
                else this.showToast(this.lang.saveFailed+': '+(d.error||''), 'error');
            } catch(e) { this.showToast(this.lang.saveFailed+': '+e.message, 'error'); }
            finally { this.configSaving = false; }
        },
        /* ── 连接测试（新） ── */
        async testConnection() {
            this.testing = true; this.testResult = null;
            try {
                const c = this.config;
                const res = await fetch(this.apiUrl('/api/test'), {
                    method:'POST', headers:{'Content-Type':'application/json'},
                    body: JSON.stringify({ provider:c.provider, apiUrl:c.apiUrl, apiKey:c.apiKey, model:c.model, maxTokens:Math.min(c.maxTokens,64), anthropicVersion:c.anthropicVersion })
                });
                this.testResult = await res.json();
            } catch(e) { this.testResult = { success:false, httpCode:0, latencyMs:0, message:'Network: '+e.message }; }
            finally { this.testing = false; }
        },
        /* ── 预设 ── */
        applyPreset() {
            const p = (this.config.presets||[]).find(x => x.name === this.selectedPreset);
            if (!p) return;
            this.config.provider=p.provider||'openai'; this.config.apiUrl=p.apiUrl||''; this.config.model=p.model||'';
            this.config.maxTokens=p.maxTokens||4096; this.config.anthropicVersion=p.anthropicVersion||'2023-06-01';
            if (p.apiKey && p.apiKey !== 'YOUR_API_KEY_HERE') this.config.apiKey = p.apiKey;
            else if (!this.config.apiKey) this.config.apiKey = 'YOUR_API_KEY_HERE';
            this.config.activePreset = p.name;
            this.showToast(this.lang.presetApply+': '+p.name, 'success');
        },
        savePreset() {
            const name = this.newPresetName.trim(); if (!name) return;
            const c = this.config;
            const preset = { name, provider:c.provider, apiUrl:c.apiUrl, apiKey:c.apiKey, model:c.model, maxTokens:c.maxTokens, anthropicVersion:c.anthropicVersion };
            if (!Array.isArray(this.config.presets)) this.config.presets = [];
            const i = this.config.presets.findIndex(x => x.name === name);
            if (i >= 0) this.config.presets.splice(i,1,preset); else this.config.presets.push(preset);
            this.config.activePreset = name; this.selectedPreset = name; this.newPresetName = '';
            this.showToast(this.lang.presetSaveCurrent+': '+name, 'success');
        },
        deletePreset() {
            if (!this.selectedPreset) return;
            this.config.presets = (this.config.presets||[]).filter(x => x.name !== this.selectedPreset);
            if (this.config.activePreset === this.selectedPreset) this.config.activePreset = '';
            this.selectedPreset = '';
            this.showToast(this.lang.presetDelete, 'success');
        },
        /* ── 指标 / 图表 ── */
        async fetchMetrics() {
            this.metricsLoading = true;
            try {
                const res = await fetch(this.apiUrl('/api/metrics'));
                if (!res.ok) throw new Error('HTTP '+res.status);
                const d = await res.json();
                this.metrics = {
                    localHits:d.localHits||0, aiRequests:d.aiRequests||0,
                    totalEstimatedTokens:d.totalEstimatedTokens||0, totalSavedTokens:d.totalSavedTokens||0,
                    totalActualTokens:d.totalActualTokens||0, hasActualTokenData:!!d.hasActualTokenData,
                    latencyHistory:Array.isArray(d.latencyHistory)?d.latencyHistory:[],
                };
                // 成本趋势：每次请求的实际 token 消耗（非累计值，真实起伏）
                this.costHistory = Array.isArray(d.tokenHistory) ? d.tokenHistory.slice(-30) : [];
                this.updateCharts();
            } catch(e) {} finally { this.metricsLoading = false; }
        },
        chartColors() {
            const dark = document.documentElement.getAttribute('data-theme') === 'dark';
            return {
                grid: dark ? '#20202e' : '#e2e8f0',
                text: dark ? '#9aa3b2' : '#475569',
                accent: dark ? '#22d3ee' : '#0891b2',
                success: dark ? '#34d399' : '#16a34a',
                warning: dark ? '#fbbf24' : '#d97706',
            };
        },
        initCharts() {
            if (typeof ApexCharts === 'undefined') return;
            const c = this.chartColors();
            this.donutChart = new ApexCharts(this.$refs.donutChartEl, {
                chart:{type:'donut',height:300,background:'transparent',fontFamily:'inherit'},
                series:[this.metrics.localHits, this.metrics.aiRequests],
                labels:[this.lang.cacheHits, this.lang.aiRequests],
                colors:[c.success, c.accent],
                legend:{position:'bottom',labels:{colors:c.text}},
                stroke:{width:0},
                plotOptions:{pie:{donut:{size:'68%'}}},
                tooltip:{theme: document.documentElement.getAttribute('data-theme')},
            });
            this.donutChart.render();
            this.lineChart = new ApexCharts(this.$refs.lineChartEl, {
                chart:{type:'line',height:300,background:'transparent',fontFamily:'inherit',toolbar:{show:false}},
                series:[{name:this.lang.avgLatency,data:this.metrics.latencyHistory.slice(-20)}],
                colors:[c.accent],
                stroke:{curve:'smooth',width:2},
                grid:{borderColor:c.grid},
                xaxis:{labels:{style:{colors:c.text}},categories:this.metrics.latencyHistory.slice(-20).map((_,i)=>'#'+(i+1))},
                yaxis:{labels:{style:{colors:c.text}}},
                tooltip:{theme: document.documentElement.getAttribute('data-theme')},
            });
            this.lineChart.render();
            this.costChart = new ApexCharts(this.$refs.costChartEl, {
                chart:{type:'area',height:200,background:'transparent',fontFamily:'inherit',toolbar:{show:false}},
                series:[{name:this.lang.tokenCostTrend,data:this.costHistory}],
                colors:[c.warning], stroke:{curve:'smooth',width:2},
                fill:{type:'gradient',gradient:{shadeIntensity:1,opacityFrom:0.4,opacityTo:0.05,stops:[0,100]}},
                grid:{borderColor:c.grid},
                xaxis:{labels:{show:false}},
                yaxis:{labels:{style:{colors:c.text}}},
                tooltip:{theme: document.documentElement.getAttribute('data-theme')},
            });
            this.costChart.render();
        },
        updateCharts() {
            if (!this.donutChart) return;
            const c = this.chartColors();
            this.donutChart.updateOptions({ series:[this.metrics.localHits, this.metrics.aiRequests], colors:[c.success,c.accent], labels:[this.lang.cacheHits,this.lang.aiRequests], legend:{labels:{colors:c.text}}, tooltip:{theme:document.documentElement.getAttribute('data-theme')} });
            this.lineChart.updateOptions({ series:[{data:this.metrics.latencyHistory.slice(-20)}], colors:[c.accent], grid:{borderColor:c.grid}, xaxis:{labels:{style:{colors:c.text}},categories:this.metrics.latencyHistory.slice(-20).map((_,i)=>'#'+(i+1))}, yaxis:{labels:{style:{colors:c.text}}}, tooltip:{theme:document.documentElement.getAttribute('data-theme')} });
            this.costChart.updateOptions({ series:[{data:this.costHistory}], colors:[c.warning], grid:{borderColor:c.grid}, yaxis:{labels:{style:{colors:c.text}}}, tooltip:{theme:document.documentElement.getAttribute('data-theme')} });
        },
        async fetchTraces() {
            try {
                const res = await fetch(this.apiUrl('/api/traces'));
                if (!res.ok) throw new Error('HTTP '+res.status);
                const d = await res.json();
                const oldMap = {}; this.traces.forEach(t => oldMap[t.id] = t.expanded);
                this.traces = (d||[]).map(t => ({...t, expanded: oldMap[t.id] || false}));
            } catch(e) {}
        },
        toggleTrace(idx) { this.traces[idx].expanded = !this.traces[idx].expanded; },
        /* ── SSE / 控制台 ── */
        connectSSE() {
            this.eventSource = new EventSource(this.apiUrl('/api/debug/console'));
            this.eventSource.onopen = () => { this.connected = true; };
            this.eventSource.onerror = () => { this.connected = false; };
            this.eventSource.onmessage = (e) => this.appendConsoleLine(e.data);
        },
        appendConsoleLine(raw) {
            try { const o = JSON.parse(raw); this.consoleLines.push(o); }
            catch(e) { this.consoleLines.push({timestamp:'',level:'INFO',message:raw}); }
            if (this.consoleLines.length > 500) this.consoleLines.splice(0, this.consoleLines.length - 500);
        },
        clearConsole() { this.consoleLines = []; },
        /* ── Spam ── */
        async loadSpamFilters() {
            this.spamLoading = true;
            try {
                const res = await fetch(this.apiUrl('/api/spam-filters'));
                if (!res.ok) throw new Error('HTTP '+res.status);
                const d = await res.json();
                this.spamEnabled = d.enabled !== false;
                this.spamFilters = Array.isArray(d.filters) ? d.filters : [];
                this.spamLoaded = true;
            } catch(e) { this.showToast(this.lang.spamLoadFailed, 'error'); }
            finally { this.spamLoading = false; }
        },
        async saveSpamFilters() {
            this.spamSaving = true;
            try {
                const res = await fetch(this.apiUrl('/api/spam-filters/save'), {
                    method:'POST', headers:{'Content-Type':'application/json'},
                    body: JSON.stringify({ enabled:this.spamEnabled, filters:this.spamFilters })
                });
                const d = await res.json();
                if (d.success) this.showToast(this.lang.spamSaved, 'success');
                else this.showToast(this.lang.spamSaveFailed, 'error');
            } catch(e) { this.showToast(this.lang.spamSaveFailed, 'error'); }
            finally { this.spamSaving = false; }
        },
        addSpamFilter() { this.spamFilters.push({name:this.lang.spamNewFilter, patternString:'', type:'CONTAINS', state:'HIDDEN', formatted:false}); },
        removeSpamFilter(i) { this.spamFilters.splice(i,1); },
        spamRowPlaceholders() { return { name:this.lang.spamNamePlaceholder, pattern:this.lang.spamPatternPlaceholder, colorHelp:this.lang.spamColorCodeHelp, delete:this.lang.spamDelete }; },
        /* ── 工具 ── */
        formatTokens(n) { if (!n) return '0'; if (n >= 1e6) return (n/1e6).toFixed(1)+'M'; if (n >= 1e3) return (n/1e3).toFixed(1)+'k'; return String(n); },
        formatJson(s) { if (!s) return ''; try { return JSON.stringify(JSON.parse(s), null, 2); } catch(e) { return s; } },
    },
    mounted() {
        this.initTheme();
        this.detectLanguage();
        this.handleHashNavigation();
        window.addEventListener('hashchange', () => this.handleHashNavigation());
        this.$nextTick(() => { this.initCharts(); });
        Promise.all([this.fetchConfig(), this.fetchMetrics(), this.fetchTraces()]).then(() => {
            // 首次加载后若 apiKey 是占位符且 #welcome，显示欢迎
            if (location.hash === '#welcome' && this.isApiKeyPlaceholder) this.showWelcome = true;
        });
        this.connectSSE();
        this.metricsTimer = setInterval(() => this.fetchMetrics(), 5000);
        this.tracesTimer = setInterval(() => this.fetchTraces(), 3000);
    },
    beforeUnmount() {
        if (this.eventSource) this.eventSource.close();
        if (this.metricsTimer) clearInterval(this.metricsTimer);
        if (this.tracesTimer) clearInterval(this.tracesTimer);
        if (this.donutChart) this.donutChart.destroy();
        if (this.lineChart) this.lineChart.destroy();
        if (this.costChart) this.costChart.destroy();
    }
});
return app;
}

// 注册组件并挂载（脚本在 head 执行，需等 DOM 就绪再挂载）
function __bootTranslex() {
  try {
    const __app = buildApp();
    if (window.TranslexComponents) window.TranslexComponents(__app);
    __app.mount('#app');
    const __root = document.getElementById('app');
    if (__root) __root.removeAttribute('v-cloak');
  } catch(e) {
    console.error('[Translex] mount failed:', e);
    alert('Translex 挂载失败: ' + (e.message||e) + '\n' + (e.stack||''));
  }
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', __bootTranslex);
} else {
  __bootTranslex();
}
