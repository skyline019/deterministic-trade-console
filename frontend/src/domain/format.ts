import { ApiError } from '../api/types'
import type { OrderStatus, Role } from '../api/types'
import { messageForApiError } from './errors'

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  hour12: false, timeZone: 'Asia/Shanghai',
})

export const numberFormatter = new Intl.NumberFormat('zh-CN')

export function formatDate(value: string): string {
  const timestamp = Date.parse(value)
  return Number.isNaN(timestamp) ? value : dateFormatter.format(timestamp)
}

export function formatFullDate(value: string): string {
  const timestamp = Date.parse(value)
  if (Number.isNaN(timestamp)) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false, timeZone: 'Asia/Shanghai',
  }).format(timestamp)
}

export function shortId(value: string): string {
  return value.length > 12 ? value.slice(0, 8) : value
}

export function shortHash(value: string): string {
  return value.length > 22 ? `${value.slice(0, 12)}…${value.slice(-8)}` : value
}

export function statusLabel(status: OrderStatus): string {
  return { RESERVED: '已预占', CONFIRMED: '已确认', CANCELLED: '已取消' }[status]
}

export function roleLabel(role: Role): string {
  return { ADMIN: '管理员', OPERATOR: '操作员', VIEWER: '只读员', AUDITOR: '审计员' }[role]
}

export function actionLabel(action: string): string {
  const labels: Record<string, string> = {
    ORDER_RESERVED: '订单预占',
    ORDER_CONFIRMED: '订单确认',
    ORDER_CANCELLED: '订单取消',
    STOCK_RESERVED: '库存预占',
    STOCK_CREATED: '库存创建',
    STOCK_ADJUSTED: '库存调整',
    STOCK_RELEASED: '预占释放',
    STOCK_CONFIRMED: '预占核销',
    RESERVATION_CONFIRMED: '预占核销',
    RESERVATION_RELEASED: '预占释放',
    INVENTORY_CREATED: '库存创建',
    INVENTORY_ADJUSTED: '库存调整',
  }
  return labels[action] ?? action
}

export function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

export function newRequestKey(scope: string): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  if (uuid) return `ui-${scope}-${uuid}`
  const values = new Uint32Array(4)
  globalThis.crypto.getRandomValues(values)
  return `ui-${scope}-${Array.from(values, (value) => value.toString(16).padStart(8, '0')).join('')}`
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return messageForApiError(error)
  }
  return error instanceof Error ? error.message : '操作失败，请检查后重试'
}
