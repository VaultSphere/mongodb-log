<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { formatBytes, formatDuration } from '../utils/format.js'

const props = defineProps({ summary: { type: Object, required: true } })
const metric = ref('operations')
const chartElement = ref(null)
let chart

const metrics = computed(() => [
  { key: 'operations', label: 'Operation' },
  { key: 'namespaces', label: 'Namespace' },
  { key: 'plans', label: '执行计划' },
  { key: 'remotes', label: '客户端 IP' },
  { key: 'cpuByOperationNamespace', label: 'CPU' },
])
const rows = computed(() => Object.entries(props.summary[metric.value] || {}).map(([name, stat]) => ({ name, ...stat })))
const unavailableReason = computed(() => metric.value === 'cpuByOperationNamespace' && !props.summary.cpuAvailable
  ? '日志未提供 cpuNanos'
  : '该维度暂无慢查询数据')

function renderChart() {
  if (!rows.value.length || !chartElement.value) return
  if (!chart) chart = echarts.init(chartElement.value)
  const cpu = metric.value === 'cpuByOperationNamespace'
  const data = rows.value.slice(0, 20)
  chart.setOption({
    color: ['#4977bd'],
    grid: { left: 150, right: 28, top: 12, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', name: cpu ? 'CPU（ms）' : '数量', minInterval: cpu ? undefined : 1 },
    yAxis: { type: 'category', inverse: true, data: data.map((row) => row.name), axisLabel: { width: 130, overflow: 'truncate' } },
    series: [{ type: 'bar', data: data.map((row) => cpu ? row.totalCpuNanos / 1_000_000 : row.count), barMaxWidth: 18, itemStyle: { borderRadius: [0, 6, 6, 0] } }],
  }, true)
}

function resize() { chart?.resize() }
onMounted(() => { nextTick(renderChart); window.addEventListener('resize', resize) })
watch([metric, () => props.summary], () => nextTick(renderChart), { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose() })
</script>

<template>
  <section class="panel breakdown-panel">
    <div class="section-heading">
      <div><span>BREAKDOWN</span><h2>慢查询维度分析</h2></div>
      <el-select v-model="metric" style="width: 170px">
        <el-option v-for="item in metrics" :key="item.key" :label="item.label" :value="item.key" />
      </el-select>
    </div>
    <div v-if="!rows.length" class="empty-state">{{ unavailableReason }}</div>
    <div v-else ref="chartElement" class="chart"></div>
    <el-table v-if="rows.length" :data="rows" max-height="290" size="small">
      <el-table-column prop="name" label="名称" min-width="170" show-overflow-tooltip />
      <el-table-column prop="count" label="数量" width="84" />
      <el-table-column label="总耗时" width="110"><template #default="scope">{{ formatDuration(scope.row.totalDurationMillis) }}</template></el-table-column>
      <el-table-column label="响应量" width="110"><template #default="scope">{{ formatBytes(scope.row.totalResponseBytes) }}</template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.section-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-heading span { color: var(--blue); font-size: 11px; font-weight: 800; letter-spacing: .14em; }
.section-heading h2 { margin: 5px 0; font-size: 20px; }
.chart { height: 300px; margin: 12px 0; }
.empty-state { display: grid; place-items: center; height: 220px; color: var(--muted); background: var(--surface-soft); border-radius: 12px; margin-top: 15px; }
</style>

