import request from '@/utils/request'

export function generateCatalog(data) {
  return request.post('/ai/catalog/generate', data)
}

export function qualityCheckBooks() {
  return request.get('/ai/books/quality-check')
}

export function analyzeBorrow(range) {
  return request.get('/ai/borrow/analyze', {
    params: { range }
  })
}

export function ragChat(data) {
  return request.post('/ai/rag/chat', data)
}

export function agentPlan(data) {
  return request.post('/ai/agent/plan', data)
}

export function agentConfirm(data) {
  return request.post('/ai/agent/confirm', data)
}
