import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/types'
import {
  actionLabel, errorMessage, formatFullDate, newRequestKey, prettyPayload, roleLabel, shortHash, statusLabel,
} from './format'

describe('console formatting', () => {
  it('keeps domain states explicit', () => {
    expect(statusLabel('RESERVED')).toBe('已预占')
    expect(actionLabel('ORDER_CONFIRMED')).toBe('订单确认')
    expect(roleLabel('AUDITOR')).toBe('审计员')
  })

  it('formats timestamps in the operations timezone', () => {
    expect(formatFullDate('2026-01-01T00:00:00Z')).toContain('2026')
    expect(formatFullDate('not-a-date')).toBe('not-a-date')
  })

  it('creates scoped high-entropy request keys', () => {
    const first = newRequestKey('order')
    const second = newRequestKey('order')
    expect(first).toMatch(/^ui-order-/)
    expect(first).not.toBe(second)
    expect(first.length).toBeLessThanOrEqual(64)
  })

  it('does not hide malformed audit payloads', () => {
    expect(prettyPayload('{"b":2,"a":1}')).toContain('"a": 1')
    expect(prettyPayload('not-json')).toBe('not-json')
    expect(shortHash('a'.repeat(64))).toHaveLength(21)
  })

  it('maps Boot API error codes to the same Chinese copy as Demo', () => {
    expect(errorMessage(new ApiError('valid credentials required', 401, 'UNAUTHENTICATED')))
      .toContain('用户名或密码错误')
    expect(errorMessage(new ApiError('insufficient stock or unknown sku: X', 409, 'OUT_OF_STOCK')))
      .toBe('可售库存不足')
    expect(errorMessage(new ApiError('adjustment would violate stock or capacity bounds', 409, 'STOCK_ADJUSTMENT_REJECTED')))
      .toContain('库存调整越界')
  })
})
