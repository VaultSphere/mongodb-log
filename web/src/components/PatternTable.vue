<script setup>
import { computed } from 'vue'
import { formatDuration } from '../utils/format.js'

const props = defineProps({ patterns: { type: Object, default: () => ({}) } })
const rows = computed(() => Object.entries(props.patterns).map(([pattern, stat], index) => ({ rank: index + 1, pattern, ...stat })))
</script>

<template>
  <section class="panel">
    <div class="section-heading"><div><span>PATTERNS</span><h2>Top 50 查询模式</h2></div><small>按总耗时排序</small></div>
    <el-empty v-if="!rows.length" description="暂无可识别的查询模式" />
    <el-table v-else :data="rows" max-height="460" stripe>
      <el-table-column prop="rank" label="#" width="55" />
      <el-table-column prop="pattern" label="查询模式" min-width="360" show-overflow-tooltip />
      <el-table-column prop="count" label="次数" width="90" />
      <el-table-column label="平均耗时" width="120"><template #default="scope">{{ formatDuration(scope.row.averageDurationMillis) }}</template></el-table-column>
      <el-table-column label="最大耗时" width="120"><template #default="scope">{{ formatDuration(scope.row.maxDurationMillis) }}</template></el-table-column>
      <el-table-column label="总耗时" width="120"><template #default="scope">{{ formatDuration(scope.row.totalDurationMillis) }}</template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.section-heading { display: flex; justify-content: space-between; align-items: flex-start; }
.section-heading span { color: var(--green); font-size: 11px; font-weight: 800; letter-spacing: .14em; }
.section-heading h2 { margin: 5px 0 16px; font-size: 20px; }
.section-heading small { color: var(--muted); }
</style>

