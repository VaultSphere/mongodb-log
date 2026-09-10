<script setup>
import { computed } from 'vue'
import { formatBytes, formatDate, formatDuration } from '../utils/format.js'

const props = defineProps({ modelValue: { type: Boolean, default: false }, query: { type: Object, default: null } })
defineEmits(['update:modelValue'])
const attributes = computed(() => JSON.stringify(props.query?.attributes || {}, null, 2))
</script>

<template>
  <el-drawer :model-value="modelValue" title="慢查询详情" size="min(760px, 92vw)" @close="$emit('update:modelValue', false)">
    <template v-if="query">
      <div class="detail-grid">
        <div><span>耗时</span><strong>{{ formatDuration(query.durationMillis) }}</strong></div>
        <div><span>时间</span><strong>{{ formatDate(query.timestampEpochMillis) }}</strong></div>
        <div><span>Operation</span><strong>{{ query.operation || '—' }}</strong></div>
        <div><span>Namespace</span><strong>{{ query.namespace || '—' }}</strong></div>
        <div><span>执行计划</span><strong>{{ query.planSummary || '—' }}</strong></div>
        <div><span>响应量</span><strong>{{ formatBytes(query.responseLength) }}</strong></div>
      </div>
      <h3>查询模式</h3>
      <pre>{{ query.queryPattern }}</pre>
      <h3>属性</h3>
      <pre>{{ attributes }}</pre>
      <h3>原始日志</h3>
      <pre class="raw">{{ query.rawLine }}</pre>
    </template>
  </el-drawer>
</template>

<style scoped>
.detail-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.detail-grid div { padding: 12px; border: 1px solid var(--line); border-radius: 10px; }
.detail-grid span { display: block; color: var(--muted); font-size: 11px; }
.detail-grid strong { display: block; margin-top: 6px; overflow-wrap: anywhere; }
h3 { margin: 22px 0 8px; font-size: 14px; }
pre { margin: 0; padding: 14px; border-radius: 10px; background: #101722; color: #d9e4ef; font: 12px/1.65 "SFMono-Regular", Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.raw { max-height: 300px; overflow: auto; }
</style>

