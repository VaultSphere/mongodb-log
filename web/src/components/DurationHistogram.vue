<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { formatDuration, formatPercent } from '../utils/format.js'

const props = defineProps({ buckets: { type: Array, default: () => [] } })
const chartElement = ref(null)
let chart
const hasData = computed(() => props.buckets.some((bucket) => bucket.count > 0))

function renderChart() {
  if (!hasData.value || !chartElement.value) return
  if (!chart) chart = echarts.init(chartElement.value)
  chart.setOption({
    color: ['#278a65'],
    grid: { left: 44, right: 18, top: 22, bottom: 48 },
    tooltip: {
      trigger: 'axis',
      formatter: (items) => {
        const bucket = props.buckets[items[0].dataIndex]
        return `${bucket.label}<br/>数量：${bucket.count}<br/>占比：${formatPercent(bucket.percentage)}<br/>平均：${formatDuration(bucket.averageDurationMillis)}`
      },
    },
    xAxis: { type: 'category', data: props.buckets.map((bucket) => bucket.label), axisLabel: { rotate: 24 } },
    yAxis: { type: 'value', name: '慢查询数', minInterval: 1 },
    series: [{ type: 'bar', data: props.buckets.map((bucket) => bucket.count), barMaxWidth: 42, itemStyle: { borderRadius: [7, 7, 0, 0] } }],
  })
}

function resize() { chart?.resize() }
onMounted(() => { nextTick(renderChart); window.addEventListener('resize', resize) })
watch(() => props.buckets, () => nextTick(renderChart), { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose() })
</script>

<template>
  <section class="panel chart-panel">
    <div class="section-heading"><div><span>慢查询</span><h2>耗时区间分布</h2></div><small>统计范围：全部慢查询</small></div>
    <div v-if="!hasData" class="empty-state">暂无慢查询耗时数据</div>
    <div v-else ref="chartElement" class="chart"></div>
    <div class="bucket-table" role="table">
      <div class="bucket-row header"><span>区间</span><span>数量</span><span>占比</span><span>平均</span><span>最大</span></div>
      <div v-for="bucket in buckets" :key="bucket.key" class="bucket-row" :data-bucket-key="bucket.key">
        <strong>{{ bucket.label }}</strong>
        <span>{{ bucket.count.toLocaleString() }}</span>
        <span>{{ formatPercent(bucket.percentage) }}</span>
        <span>{{ formatDuration(bucket.averageDurationMillis) }}</span>
        <span>{{ formatDuration(bucket.maxDurationMillis) }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; }
.section-heading span { color: var(--green); font-size: 11px; font-weight: 800; letter-spacing: .14em; }
.section-heading h2 { margin: 5px 0; font-size: 20px; }
.section-heading small { color: var(--muted); }
.chart { height: 300px; margin-top: 10px; }
.empty-state { display: grid; place-items: center; height: 180px; color: var(--muted); background: var(--surface-soft); border-radius: 12px; margin-top: 15px; }
.bucket-table { margin-top: 14px; overflow-x: auto; }
.bucket-row { display: grid; grid-template-columns: 1.4fr repeat(4, 1fr); min-width: 590px; padding: 10px 12px; border-top: 1px solid var(--line); font-size: 12px; }
.bucket-row.header { color: var(--muted); background: var(--surface-soft); border: 0; border-radius: 8px; }
</style>

