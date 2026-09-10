<script setup>
import { formatBytes, formatDate } from '../utils/format.js'

defineProps({ tasks: { type: Array, default: () => [] }, selectedId: { type: String, default: '' } })
defineEmits(['select'])

const statusText = { QUEUED: '排队中', RUNNING: '分析中', COMPLETED: '已完成', FAILED: '失败' }
const statusType = { QUEUED: 'info', RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger' }
</script>

<template>
  <aside class="panel task-list">
    <div class="section-title">
      <div><span class="eyebrow">HISTORY</span><h2>分析任务</h2></div>
      <span class="task-count">{{ tasks.length }}</span>
    </div>
    <div v-if="!tasks.length" class="empty">上传日志后，任务会显示在这里。</div>
    <button
      v-for="task in tasks"
      :key="task.id"
      class="task-item"
      :class="{ active: task.id === selectedId }"
      @click="$emit('select', task)"
    >
      <div class="task-row">
        <strong>{{ task.name }}</strong>
        <el-tag :type="statusType[task.status]" size="small">{{ statusText[task.status] }}</el-tag>
      </div>
      <div class="task-meta">
        <span>{{ task.files.length }} 个文件</span>
        <span>{{ formatBytes(task.totalBytes) }}</span>
        <span>{{ formatDate(task.createdAtEpochMillis) }}</span>
      </div>
    </button>
  </aside>
</template>

<style scoped>
.task-list { min-width: 0; }
.section-title, .task-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-title h2 { margin: 5px 0 16px; font-size: 19px; }
.eyebrow { color: var(--green); font-size: 11px; font-weight: 800; letter-spacing: .15em; }
.task-count { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 10px; background: #e8f5ef; color: var(--green-dark); font-weight: 800; }
.task-item { width: 100%; padding: 15px; margin-top: 9px; border: 1px solid var(--line); border-radius: 13px; background: #fff; color: var(--text); text-align: left; cursor: pointer; transition: .18s ease; }
.task-item:hover, .task-item.active { border-color: #62b891; background: #f2fbf7; box-shadow: 0 8px 22px rgba(27, 108, 76, .08); }
.task-row strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-meta { display: flex; flex-wrap: wrap; gap: 5px 12px; margin-top: 9px; color: var(--muted); font-size: 11px; }
.empty { color: var(--muted); padding: 28px 8px; text-align: center; }
</style>

