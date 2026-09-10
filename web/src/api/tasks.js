async function request(url, options) {
  const response = await fetch(url, options)
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) {
    throw new Error(body?.message || `请求失败（HTTP ${response.status}）`)
  }
  return body
}

export function createTask(name, files) {
  const form = new FormData()
  if (name?.trim()) form.append('name', name.trim())
  files.forEach((file) => form.append('files', file))
  return request('/api/tasks', { method: 'POST', body: form })
}

export function fetchTasks() {
  return request('/api/tasks')
}

export function deleteTask(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}`, { method: 'DELETE' })
}

export function fetchTask(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}`)
}

export function fetchSummary(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/summary`)
}

export function fetchSlowQueries(taskId, params = {}) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  })
  return request(`/api/tasks/${encodeURIComponent(taskId)}/slow-queries?${query}`)
}

export function fetchSlowQuery(taskId, queryId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/slow-queries/${encodeURIComponent(queryId)}`)
}

export function fetchSlowQueryPoints(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/slow-query-points`)
}
