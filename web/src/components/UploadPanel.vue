<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createTask } from '../api/tasks.js'

const emit = defineEmits(['created'])
const name = ref('')
const fileList = ref([])
const submitting = ref(false)

async function submit() {
  const files = fileList.value.map((item) => item.raw).filter(Boolean)
  if (!files.length) {
    ElMessage.warning('请先选择 MongoDB 日志文件')
    return
  }
  submitting.value = true
  try {
    const task = await createTask(name.value, files)
    ElMessage.success('任务已创建，正在本机分析')
    fileList.value = []
    name.value = ''
    emit('created', task)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="panel upload-panel">
    <div class="upload-copy">
      <span class="eyebrow">LOCAL ONLY</span>
      <h2>上传 MongoDB 日志</h2>
      <p>文件仅在这台电脑上处理。支持普通文本和 .gz，可一次选择多个文件组成一个任务。</p>
      <el-input v-model="name" maxlength="60" placeholder="任务名称（可选，默认使用文件名）" />
    </div>
    <div class="upload-action">
      <el-upload
        v-model:file-list="fileList"
        drag
        multiple
        :auto-upload="false"
        :limit="20"
      >
        <div class="drop-title">拖入日志，或点击选择文件</div>
        <div class="drop-note">支持 MongoDB JSON、旧版文本日志及 GZip</div>
      </el-upload>
      <el-button type="primary" size="large" :loading="submitting" @click="submit">
        开始分析
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.upload-panel { display: grid; grid-template-columns: 1fr 1.15fr; gap: 28px; align-items: center; }
.upload-copy h2 { margin: 8px 0; font-size: 25px; }
.upload-copy p { margin: 0 0 18px; color: var(--muted); line-height: 1.7; }
.eyebrow { color: var(--green); font-size: 12px; font-weight: 800; letter-spacing: .16em; }
.upload-action { display: grid; grid-template-columns: 1fr auto; gap: 14px; align-items: center; }
.drop-title { font-size: 15px; font-weight: 700; color: var(--text); }
.drop-note { margin-top: 6px; color: var(--muted); font-size: 12px; }
@media (max-width: 900px) {
  .upload-panel { grid-template-columns: 1fr; }
  .upload-action { grid-template-columns: 1fr; }
}
</style>

