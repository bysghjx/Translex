/* ============================================================
   Translex Web UI — 全局组件（v2.1）
   在 Vue.createApp 之前注册。i18n 通过 inject('lang') 获取。
   v-model 用 modelValue + update:modelValue 约定。
   ============================================================ */

/* 通用开关行：label + 描述 + toggle */
window.TranslexComponents = function(app) {
app.component('toggle-row', {
    props: { label: String, desc: String, modelValue: Boolean },
    emits: ['update:modelValue'],
    template: `
    <label class="flex items-center justify-between cursor-pointer">
        <div>
            <div class="text-sm" style="color:var(--text-primary)">{{ label }}</div>
            <div v-if="desc" class="text-xs mt-0.5" style="color:var(--text-muted)">{{ desc }}</div>
        </div>
        <input type="checkbox" :checked="modelValue"
               @change="$emit('update:modelValue', $event.target.checked)"
               class="toggle-switch">
    </label>`
});

/* 通用下拉：label + 描述 + select */
app.component('select-field', {
    props: {
        label: String, desc: String, modelValue: [String, Number],
        options: { type: Array, default: () => [] }, // [{value, label}]
    },
    emits: ['update:modelValue'],
    template: `
    <div>
        <label v-if="label" class="input-label">{{ label }}</label>
        <select :value="modelValue" @change="$emit('update:modelValue', $event.target.value)" class="input-field">
            <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <div v-if="desc" class="text-xs mt-1" style="color:var(--text-muted)">{{ desc }}</div>
    </div>`
});

/* 统计卡片 */
app.component('stat-card', {
    props: { label: String, value: [String, Number], sub: String, accent: { type: String, default: '' } },
    template: `
    <div class="card-panel p-5">
        <div class="text-xs mb-1" style="color:var(--text-muted)">{{ label }}</div>
        <div class="stat-value text-3xl font-bold" :style="accent ? 'color:var(--' + accent + ')' : ''">{{ value }}</div>
        <div v-if="sub" class="text-xs mt-1" v-html="sub" style="color:var(--text-secondary)"></div>
    </div>`
});

/* 控制台面板（Debug 用，单一实例，消除 footer 重复） */
app.component('console-panel', {
    props: {
        lines: { type: Array, default: () => [] },
        levelFilter: { type: String, default: 'all' },
        showFilter: { type: Boolean, default: true },
        emptyText: { type: String, default: '' },
        liveLabel: { type: String, default: '' },
    },
    emits: ['update:levelFilter', 'clear'],
    template: `
    <div class="card-panel flex flex-col" style="min-height: 55vh;">
        <div class="flex items-center justify-between px-4 py-3 border-b" style="border-color:var(--border-color)">
            <div class="flex items-center gap-2 text-sm" style="color:var(--text-secondary)">
                <span style="color:var(--accent)">〉_</span>
                <span>{{ liveLabel }}</span>
                <span style="color:var(--text-muted)">({{ lines.length }})</span>
            </div>
            <div class="flex items-center gap-3">
                <select v-if="showFilter" :value="levelFilter"
                        @change="$emit('update:levelFilter', $event.target.value)"
                        class="text-xs rounded px-2 py-1" style="background:var(--bg-input);border:1px solid var(--border-color);color:var(--text-secondary)">
                    <option value="all">ALL</option>
                    <option value="DEBUG">DEBUG</option>
                    <option value="INFO">INFO</option>
                    <option value="WARN">WARN</option>
                    <option value="ERROR">ERROR</option>
                </select>
                <button @click="$emit('clear')" class="text-xs hover:opacity-80" style="color:var(--text-muted)">{{ '✕' }}</button>
            </div>
        </div>
        <div ref="termEl" class="terminal p-4 flex-1 overflow-y-auto" style="max-height: 62vh;">
            <div v-for="(line, i) in lines" :key="i" :class="'log-' + line.level.toLowerCase()">
                <span style="color:#555">[{{ line.timestamp }}]</span>
                <span class="badge" :style="badgeStyle(line.level)">{{ line.level }}</span>
                {{ line.message }}
            </div>
            <div v-if="lines.length === 0" style="color:#555" class="select-none">{{ emptyText }}</div>
        </div>
    </div>`,
    methods: {
        badgeStyle(level) {
            const m = { ERROR:'var(--danger)', WARN:'var(--warning)', INFO:'var(--accent)', DEBUG:'#6b7280' };
            const c = m[level] || 'var(--text-muted)';
            return `display:inline-block;min-width:42px;padding:0 6px;margin:0 6px;border-radius:4px;font-size:10px;font-weight:600;color:${c};border:1px solid ${c}40`;
        }
    },
    watch: {
        lines() { this.$nextTick(() => { const el = this.$refs.termEl; if (el) el.scrollTop = el.scrollHeight; }); }
    }
});

/* Spam 过滤器行 */
app.component('filter-row', {
    props: {
        modelValue: { type: Object, required: true }, // {name, patternString, type, state, formatted}
        placeholders: { type: Object, default: () => ({}) },
    },
    emits: ['update:modelValue', 'remove'],
    template: `
    <div class="filter-row">
        <input :value="modelValue.name" @input="update('name', $event.target.value)" class="input-field" :placeholder="placeholders.name">
        <input :value="modelValue.patternString" @input="update('patternString', $event.target.value)" class="input-field font-mono text-xs" :placeholder="placeholders.pattern">
        <select :value="modelValue.type" @change="update('type', $event.target.value)" class="input-field select-field text-xs">
            <option value="STARTSWITH">StartsWith</option>
            <option value="CONTAINS">Contains</option>
            <option value="REGEX">Regex</option>
        </select>
        <select :value="modelValue.state" @change="update('state', $event.target.value)" class="input-field select-field text-xs"
                :style="modelValue.state==='HIDDEN' ? 'color:var(--danger)' : modelValue.state==='SEPARATE' ? 'color:var(--accent)' : ''">
            <option value="NORMAL">Normal</option>
            <option value="HIDDEN">Hidden</option>
            <option value="SEPARATE">Separate</option>
        </select>
        <label class="flex items-center justify-center gap-1 text-xs cursor-pointer select-none whitespace-nowrap" style="color:var(--text-muted)">
            <span class="help-badge" :title="placeholders.colorHelp">?</span>
            <input type="checkbox" :checked="modelValue.formatted" @change="update('formatted', $event.target.checked)" class="toggle-switch" style="transform:scale(0.65)">
        </label>
        <button @click="$emit('remove')" class="btn btn-danger !p-1.5 !rounded-lg text-xs" :title="placeholders.delete">✕</button>
    </div>`,
    methods: {
        update(key, val) { this.$emit('update:modelValue', { ...this.modelValue, [key]: val }); }
    }
});

/* 抓包手风琴（整块） */
app.component('trace-accordion', {
    props: { traces: { type: Array, default: () => [] } },
    emits: ['toggle'],
    template: `
    <div class="space-y-3">
        <div v-if="traces.length === 0" class="card-panel p-8 text-center" style="color:var(--text-muted)">{{ '—' }}</div>
        <div v-for="(trace, idx) in traces" :key="trace.id" class="card-panel overflow-hidden">
            <button @click="$emit('toggle', idx)"
                    class="w-full flex items-center gap-4 px-5 py-3.5 text-left transition-colors" style="border:none;background:transparent">
                <span :class="['w-2 h-2 rounded-full shrink-0', trace.success ? '' : '']"
                      :style="{ background: trace.success ? 'var(--success)' : 'var(--danger)' }"></span>
                <span class="text-xs font-mono shrink-0 w-20" style="color:var(--text-muted)">{{ trace.timestamp }}</span>
                <span class="text-xs font-semibold shrink-0 w-14" style="color:var(--text-secondary)">{{ trace.method }}</span>
                <span class="text-sm truncate flex-1" style="color:var(--text-secondary)">{{ trace.url }}</span>
                <span class="text-xs shrink-0" style="color:var(--text-muted)">{{ trace.durationMs }}ms</span>
                <span v-if="trace.estimatedTokens > 0" class="text-xs shrink-0" style="color:var(--success)">~{{ fmt(trace.estimatedTokens) }} tok</span>
                <span v-if="trace.hasTokenData" class="text-xs shrink-0" style="color:var(--accent)">{{ fmt(trace.promptTokens + trace.completionTokens) }} tok</span>
                <span class="shrink-0 transition-transform" :style="{ transform: trace.expanded ? 'rotate(180deg)' : 'rotate(0deg)', color:'var(--text-muted)' }">▼</span>
            </button>
            <div class="accordion-body" :style="{ maxHeight: trace.expanded ? '900px' : '0px' }">
                <div class="px-5 pb-4 space-y-3 border-t pt-3" style="border-color:var(--border-color)">
                    <div v-if="trace.estimatedTokens > 0" class="text-xs flex items-center gap-3 flex-wrap" style="color:var(--text-secondary)">
                        <span>~{{ fmt(trace.estimatedTokens) }} tok</span>
                        <span v-if="trace.estimatedSystemPromptTokens > 0" style="color:var(--text-muted)">
                            (sys ~{{ fmt(trace.estimatedSystemPromptTokens) }} + payload ~{{ fmt(trace.estimatedPayloadTokens) }})
                        </span>
                    </div>
                    <div>
                        <div class="text-xs font-semibold mb-1.5" style="color:var(--accent)">Request</div>
                        <pre class="trace-pre text-xs rounded-lg p-3 font-mono" style="background:var(--bg-input);color:var(--text-secondary)">{{ pretty(trace.requestBody) }}</pre>
                    </div>
                    <div>
                        <div class="text-xs font-semibold mb-1.5" style="color:var(--success)">Response</div>
                        <pre class="trace-pre text-xs rounded-lg p-3 font-mono" style="background:var(--bg-input);color:var(--text-secondary)">{{ pretty(trace.responseBody) }}</pre>
                    </div>
                    <div v-if="trace.hasTokenData" class="text-xs space-y-1.5 rounded-lg p-3 mt-2" style="background:var(--bg-input)">
                        <div class="grid grid-cols-2 gap-x-4 gap-y-1">
                            <span style="color:var(--text-secondary)">prompt</span>
                            <span class="text-right" style="color:var(--success)">{{ trace.promptTokens }}</span>
                            <span style="color:var(--text-secondary)">completion</span>
                            <span class="text-right" style="color:var(--accent)">{{ trace.completionTokens }}</span>
                            <span v-if="trace.reasoningTokens > 0" style="color:var(--text-secondary)">reasoning</span>
                            <span v-if="trace.reasoningTokens > 0" class="text-right" style="color:var(--danger)">{{ trace.reasoningTokens }}</span>
                            <span style="color:var(--text-secondary)">cached</span>
                            <span class="text-right" style="color:var(--text-muted)">{{ trace.cachedTokens > 0 ? trace.cachedTokens : '—' }}</span>
                        </div>
                        <div class="border-t pt-1.5 flex justify-between" style="border-color:var(--border-color)">
                            <span style="color:var(--text-secondary)">total</span>
                            <span class="font-semibold" style="color:var(--warning)">{{ trace.totalTokens || (trace.promptTokens + trace.completionTokens) }} tok</span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>`,
    methods: {
        fmt(n) { if (!n) return '0'; if (n >= 1e6) return (n/1e6).toFixed(1)+'M'; if (n >= 1e3) return (n/1e3).toFixed(1)+'k'; return String(n); },
        pretty(s) { if (!s) return ''; try { return JSON.stringify(JSON.parse(s), null, 2); } catch(e) { return s; } }
    }
});

/* API Key 输入（封装占位符明文 + 显隐） */
app.component('api-key-input', {
    props: { modelValue: String, placeholder: String, hint: String },
    emits: ['update:modelValue'],
    data() { return { show: false }; },
    computed: {
        isPlaceholder() { return !this.modelValue || this.modelValue === 'YOUR_API_KEY_HERE'; },
        inputType() { return this.isPlaceholder ? 'text' : (this.show ? 'text' : 'password'); }
    },
    template: `
    <div>
        <div class="relative">
            <input :type="inputType" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)"
                   class="input-field font-mono" :class="{ 'pr-12': !isPlaceholder }" :placeholder="placeholder">
            <button v-if="!isPlaceholder" @click="show = !show"
                    class="absolute right-2 top-1/2 -translate-y-1/2 text-xs px-2 py-1 hover:opacity-80"
                    style="color:var(--text-muted)">{{ show ? '🙈' : '👁' }}</button>
            <span v-else class="absolute right-2 top-1/2 -translate-y-1/2 text-xs" style="color:var(--warning)">{{ hint }}</span>
        </div>
    </div>`
});

/* 保存按钮（带 spinner） */
app.component('save-button', {
    props: { loading: Boolean, loadingText: { type: String, default: '...' }, text: String },
    template: `
    <button class="btn btn-primary" :disabled="loading">
        <span v-if="loading" class="inline-block w-4 h-4 border-2 rounded-full animate-spin"
              style="border-color:rgba(0,0,0,0.2);border-top-color:transparent"></span>
        <slot>{{ loading ? loadingText : text }}</slot>
    </button>`
});
};
