<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchSummary, fetchTask, fetchTasks } from './api/tasks.js'
import UploadPanel from './components/UploadPanel.vue'
import TaskList from './components/TaskList.vue'
import SummaryCards from './components/SummaryCards.vue'
import DurationHistogram from './components/DurationHistogram.vue'
import BreakdownCharts from './components/BreakdownCharts.vue'
import PatternTable from './components/PatternTable.vue'
import SlowQueryTable from './components/SlowQueryTable.vue'
import SlowQueryDetail from './components/SlowQueryDetail.vue'

const tasks = ref([])
const selectedTask = ref(null)
const summary = ref(null)
const selectedQuery = ref(null)
const detailVisible = ref(false)
let timer

const progress = computed(() => {
  const task = selectedTask.value
  if (!task) return 0
  if (task.status === 'COMPLETED') return 100
  return task.totalBytes ? Math.min(99, Math.round(task.processedBytes * 100 / task.totalBytes)) : 0
})

async function loadTasks() {
  try {
    tasks.value = await fetchTasks()
    if (!selectedTask.value && tasks.value.length) await selectTask(tasks.value[0])
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function selectTask(task) {
  stopPolling()
  selectedTask.value = task
  summary.value = null
  if (task.status === 'COMPLETED') {
    await loadSummary(task.id)
  } else if (task.status === 'QUEUED' || task.status === 'RUNNING') {
    startPolling(task.id)
  }
}

async function loadSummary(taskId) {
  try {
    summary.value = await fetchSummary(taskId)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function startPolling(taskId) {
  timer = window.setInterval(async () => {
    try {
      const task = await fetchTask(taskId)
      selectedTask.value = task
      const index = tasks.value.findIndex((item) => item.id === task.id)
      if (index >= 0) tasks.value.splice(index, 1, task)
      if (task.status === 'COMPLETED' || task.status === 'FAILED') {
        stopPolling()
        if (task.status === 'COMPLETED') await loadSummary(task.id)
        await loadTasks()
      }
    } catch (error) {
      stopPolling()
      ElMessage.error(error.message)
    }
  }, 1000)
}

function stopPolling() {
  if (timer) window.clearInterval(timer)
  timer = undefined
}

async function taskCreated(task) {
  tasks.value.unshift(task)
  await selectTask(task)
}

function showDetail(query) {
  selectedQuery.value = query
  detailVisible.value = true
}

onMounted(loadTasks)
onBeforeUnmount(stopPolling)
</script>

<template>
  <header class="topbar">
    <div class="brand-mark">M</div>
    <div><strong>MongoDB Log Analyzer</strong><span>本机离线分析</span></div>
    <div class="privacy"><i></i>数据不离开当前电脑</div>
  </header>
  <main class="shell">
    <section class="hero">
      <div><span class="kicker">OPERATIONS TOOLKIT</span><h1>把慢日志变成可行动的答案</h1></div>
      <p>流式扫描全部日志，精确统计慢查询耗时分布，只永久保存耗时最长的 Top 5000 明细。</p>
    </section>
    <UploadPanel @created="taskCreated" />
    <div class="workspace">
      <TaskList :tasks="tasks" :selected-id="selectedTask?.id" @select="selectTask" />
      <section class="results">
        <div v-if="!selectedTask" class="panel welcome-state">
          <strong>等待第一个分析任务</strong><span>上传日志后，这里会展示结果。</span>
        </div>
        <template v-else>
          <section class="panel task-status">
            <div><span>当前任务</span><h2>{{ selectedTask.name }}</h2></div>
            <el-progress v-if="['QUEUED', 'RUNNING'].includes(selectedTask.status)" :percentage="progress" :stroke-width="10" />
            <el-alert v-if="selectedTask.status === 'FAILED'" :title="selectedTask.errorMessage" type="error" :closable="false" show-icon />
          </section>
          <template v-if="summary">
            <SummaryCards :summary="summary" />
            <div class="chart-grid">
              <DurationHistogram :buckets="summary.durationDistribution" />
              <BreakdownCharts :summary="summary" />
            </div>
            <PatternTable :patterns="summary.patterns" />
            <SlowQueryTable :task-id="selectedTask.id" @select="showDetail" />
          </template>
        </template>
      </section>
    </div>
  </main>
  <SlowQueryDetail v-model="detailVisible" :query="selectedQuery" />
</template>

<style>
:root {
  --text: #17233b;
  --muted: #6d788c;
  --line: #e1e7ec;
  --surface-soft: #f4f7f8;
  --green: #21845e;
  --green-dark: #176445;
  --blue: #406fae;
}
* { box-sizing: border-box; }
body { margin: 0; color: var(--text); background: #eff3f4; font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif; }
button, input { font: inherit; }
.topbar { height: 66px; display: flex; align-items: center; gap: 11px; padding: 0 max(24px, calc((100vw - 1480px) / 2)); background: #10271f; color: #fff; box-shadow: 0 5px 22px rgba(16, 39, 31, .18); }
.brand-mark { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 11px; background: #36a77a; font-weight: 900; }
.topbar strong, .topbar span { display: block; }
.topbar span { margin-top: 2px; color: #a8cabb; font-size: 10px; letter-spacing: .12em; }
.privacy { margin-left: auto; color: #c7ded4; font-size: 12px; }
.privacy i { display: inline-block; width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: #54d89f; box-shadow: 0 0 0 4px rgba(84, 216, 159, .12); }
.shell { max-width: 1480px; margin: 0 auto; padding: 30px 24px 60px; }
.hero { display: flex; align-items: end; justify-content: space-between; gap: 40px; padding: 8px 4px 24px; }
.hero h1 { margin: 6px 0 0; font-size: clamp(28px, 4vw, 43px); letter-spacing: -.045em; }
.hero p { max-width: 520px; margin: 0 0 4px; color: var(--muted); line-height: 1.8; }
.kicker { color: var(--green); font-size: 11px; font-weight: 900; letter-spacing: .18em; }
.panel { padding: 22px; border: 1px solid rgba(216, 225, 229, .95); border-radius: 18px; background: rgba(255, 255, 255, .96); box-shadow: 0 12px 34px rgba(28, 53, 46, .055); }
.workspace { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 18px; align-items: start; margin-top: 18px; }
.results { display: grid; gap: 16px; min-width: 0; }
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.welcome-state { min-height: 230px; display: grid; place-content: center; text-align: center; }
.welcome-state strong { font-size: 20px; }
.welcome-state span { margin-top: 8px; color: var(--muted); }
.task-status { display: grid; grid-template-columns: minmax(200px, .7fr) 1fr; align-items: center; gap: 30px; }
.task-status span { color: var(--muted); font-size: 11px; }
.task-status h2 { margin: 5px 0 0; font-size: 20px; }
@media (max-width: 1100px) { .workspace { grid-template-columns: 1fr; } .chart-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .hero { display: block; } .hero p { margin-top: 14px; } .task-status { grid-template-columns: 1fr; } .privacy { display: none; } }
</style>
