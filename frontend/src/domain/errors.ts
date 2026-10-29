import { ApiError } from '../api/types'

/** Stable API codes → console copy. Keeps Demo and same-origin Boot aligned. */
const CODE_MESSAGES: Record<string, string> = {
  UNAUTHENTICATED: '用户名或密码错误，或会话已失效',
  ACCESS_DENIED: '当前角色没有执行该操作的权限',
  LOGIN_RATE_LIMITED: '登录尝试过于频繁，请稍后重试',
  API_RATE_LIMITED: '请求过于频繁，请稍后重试',
  CSRF_REJECTED: '跨站请求被拒绝，请刷新页面后重试',
  INVALID_REQUEST: '请求参数无效，请检查后重试',
  OUT_OF_STOCK: '可售库存不足',
  ORDER_STATE_CONFLICT: '订单已处于终态，无法再迁移',
  RESOURCE_CONFLICT: '资源冲突（例如 SKU 已存在）',
  STOCK_ADJUSTMENT_REJECTED: '库存调整越界或台账不一致',
  IDEMPOTENCY_CONFLICT: '幂等键已用于不同请求内容',
  REQUEST_IN_PROGRESS: '相同请求正在处理中，请稍后使用原幂等键重试',
  NOT_FOUND: '目标资源不存在',
  LOCK_UNAVAILABLE: '互斥锁暂时不可用，请稍后重试',
  PERSISTENCE_UNAVAILABLE: '数据库暂时不可用，请稍后重试',
  STORED_STATE_INVALID: '服务端存储的订单数据不一致，请联系运维处理',
  AUTH_UNAVAILABLE: '认证服务暂时不可用，请稍后重试',
  INTERNAL_ERROR: '服务内部错误',
  INVALID_RESPONSE: '服务返回了无效响应',
  TIMEOUT: '请求超时，请稍后重试',
  NETWORK_ERROR: '无法连接交易服务',
  HTTP_ERROR: '请求失败',
}

export function messageForApiError(error: ApiError): string {
  const mapped = CODE_MESSAGES[error.code]
  if (mapped) {
    return mapped
  }
  if (error.message && error.message.trim()) {
    return error.message
  }
  return '操作失败，请检查后重试'
}
