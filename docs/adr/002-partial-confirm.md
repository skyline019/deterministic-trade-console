# ADR 002：部分确认（缩量保留 RESERVED）

状态：Accepted  
日期：2026-10-24

## 背景

Wave L1 加厚：第二个写冲突。全量确认/取消不足以训练「订单数量可变」下的台账与幂等。

## 决策

对 `RESERVED` 订单允许确认数量 `q'`（`1 <= q' <= quantity`）：

| `q'` | 订单结果 | 台账 |
|---|---|---|
| `q' < quantity` | 仍为 `RESERVED`，`quantity := quantity - q'` | `confirmReservation(sku, q')` |
| `q' = quantity` | `CONFIRMED` | 同上（全量） |

- **不拆单**（不新建订单行）。拆单留待未来 ADR。  
- 下单时的 `fingerprint` **不变**（仍绑定原始预占负载），以便 place 幂等重放。  
- `order_operations` 增加 `effect_quantity`：确认/取消影响的数量；同键不同 `effect_quantity` → `409`。  
- 审计：部分 → `ORDER_PARTIAL_CONFIRMED` + `STOCK_CONFIRMED`；全量 → `ORDER_CONFIRMED` + `STOCK_CONFIRMED`。

## 非目标

自动拆子单、部分取消、隐式定时确认。

## 后果

- API：`POST .../confirmation` 可选 `quantity`；缺省 = 全量。  
- Domain：`Order.confirmUnits(Quantity, Instant)`。  
