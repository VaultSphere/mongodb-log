<script setup>
import { computed } from 'vue'
import { formatDuration } from '../utils/format.js'

const props = defineProps({ summary: { type: Object, required: true } })
const cards = computed(() => [
  { label: '日志总行数', value: props.summary.totalLines?.toLocaleString(), tone: 'neutral' },
  { label: '慢查询', value: props.summary.slowQueryCount?.toLocaleString(), tone: 'green' },
  { label: '慢查询总耗时', value: formatDuration(props.summary.totalSlowDurationMillis), tone: 'amber' },
  { label: '解析成功', value: props.summary.successLines?.toLocaleString(), tone: 'blue' },
  { label: '部分解析', value: props.summary.partialLines?.toLocaleString(), tone: 'amber' },
  { label: '解析失败', value: props.summary.failedLines?.toLocaleString(), tone: 'red' },
])
</script>

<template>
  <section class="summary-grid">
    <article v-for="card in cards" :key="card.label" class="summary-card" :class="card.tone">
      <span>{{ card.label }}</span>
      <strong>{{ card.value }}</strong>
    </article>
  </section>
</template>

<style scoped>
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.summary-card { padding: 18px; border: 1px solid var(--line); border-radius: 15px; background: #fff; }
.summary-card span { display: block; color: var(--muted); font-size: 12px; }
.summary-card strong { display: block; margin-top: 9px; font-size: 23px; letter-spacing: -.03em; }
.summary-card.green { border-top: 3px solid #26966a; }
.summary-card.amber { border-top: 3px solid #d7922d; }
.summary-card.blue { border-top: 3px solid #4e7cc7; }
.summary-card.red { border-top: 3px solid #d85e62; }
@media (max-width: 1100px) { .summary-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 650px) { .summary-grid { grid-template-columns: repeat(2, 1fr); } }
</style>

