# ADR 003：预留改期（expiresAt 变更）

状态：Accepted  
日期：2026-10-24

## 背景

Wave L2：`RESERVED` 订单的 `expiresAt` 在下单后固定，运维只能整批 `expire-reservations`。需要显式改期以训练「可变字段 + 与过期竞态 + 独立幂等命名空间」。

## 决策

对仍为 `RESERVED`、且**尚未过期**的订单，允许设置新的 `expiresAt`：

| 约束 | 规则 |
|---|---|
| 下界 | `newExpiresAt > now`（已过期须先 expire/cancel，不可复活） |
| 上界 | `newExpiresAt <= now + trade.reservation-ttl` |
| 状态 | 仍为 `RESERVED`；`fingerprint` / 数量不变 |
| 台账 | 无库存变动 |

- 幂等回执独立表 `order_reschedule_operations`（不复用 `order_operations`，因其 `target_status` 仅 CONFIRMED/CANCELLED）。  
- 指纹：`(orderId, newExpiresAt, reason)`；同键不同指纹 → `409`。  
- 持久化：`UPDATE ... WHERE id=? AND status='RESERVED' AND expires_at=?`（乐观绑定旧过期时刻，与 expire/cancel 竞态最多一个效果）。  
- 审计：`ORDER_RESCHEDULED`（payload 含 previous / next expiresAt）。

## 非目标

自动续期、隐式 `@Scheduled` 扫描、对已过期订单「复活」、改数量。

## 后果

- API：`POST /api/v1/orders/{id}/reschedule`，body `{ "expiresAt", "reason" }`。  
- Domain：`Order.rescheduleExpiresAt(Instant, Instant)`。  
