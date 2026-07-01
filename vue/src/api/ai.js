import request from '@/utils/request'

const AI_REQUEST_CONFIG = { timeout: 20000 }

export function generateCatalog(data) {
  return request.post('/ai/catalog/generate', data, AI_REQUEST_CONFIG)
}

export function qualityCheckBooks() {
  return request.get('/ai/books/quality-check', AI_REQUEST_CONFIG)
}

export function analyzeBorrow(range) {
  return request.get('/ai/borrow/analyze', {
    params: { range },
    timeout: AI_REQUEST_CONFIG.timeout
  })
}

export function ragChat(data) {
  return request.post('/ai/rag/chat', data, AI_REQUEST_CONFIG)
}

export function agentPlan(data) {
  return request.post('/ai/agent/plan', data, AI_REQUEST_CONFIG)
}

export function agentConfirm(data) {
  return request.post('/ai/agent/confirm', data)
}

export function listKnowledge() {
  return request.get('/ai/knowledge/list')
}

export function saveKnowledge(data) {
  return request.post('/ai/knowledge/save', data)
}

export function seedDefaultKnowledge() {
  return request.post('/ai/knowledge/seed-default')
}

export function deleteKnowledge(id) {
  return request.delete(`/ai/knowledge/${id}`)
}

export function listAgentTasks(limit = 10) {
  return request.get('/ai/agent/tasks', {
    params: { limit }
  })
}

export function getAgentTask(taskLogId) {
  return request.get(`/ai/agent/tasks/${taskLogId}`)
}

export function cancelAgentTask(taskLogId) {
  return request.post(`/ai/agent/tasks/${taskLogId}/cancel`)
}
