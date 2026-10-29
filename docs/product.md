# 产品示例规格（Deterministic Trade Console）

状态：已落地  
目标：把正确性实验室加厚为**可感知的产品示例**，压力落在 Domain / Application / Port，而非再叠运维脚手架。

## 一句话产品

单仓「确定性库存交易台」：运营预占/确认/取消；过期预留由运维显式释放回台账；审计师验链。Demo 仅为本地演示开关，不作证据。

## 主用户旅程

1. 登录（Bearer 默认；HTTPS 可选 Cookie）  
2. 建库存 → 下单（预留带 `expiresAt`）→ 全量/部分确认或取消  
3. 过期未终态：运维 `expire-reservations`（同取消台账路径）  
4. 审计验链  

## 本轮能力切片

| ID | 能力 | 状态 |
|---|---|---|
| A | 产品叙事与真假路径 | 已完成（README/`product.md`；Demo 显式） |
| B | 预留 TTL 过期释放 | 已完成（V3 + `expire-reservations` + jtest） |
| C | AuthN 端口 + JWT 适配 | 已完成（端口 + 可选 HS256 + jtest） |
| D | 前端主路径 | 已完成（默认真 HTTP；侧栏/横幅标 Demo） |
| L1 | 部分确认（缩量保留 RESERVED） | 已完成（[ADR 002](adr/002-partial-confirm.md) + V4 + jtest + UI） |
| L2 | 预留改期（expiresAt） | 已完成（[ADR 003](adr/003-reservation-reschedule.md) + V5 + jtest + UI） |
| L3 | 证据补强 | 已完成（Demo E2E 显式命名；同源/Demo 部分确认+改期；`expire-reservations.sh`） |
| L4 | JWKS AuthN 薄片 | 已完成（RS256 静态 JWKS + kid 轮换 jtest；可关密码登录） |

## 非目标

多活、完整 IdP 发行方、支付网关、隐式 `@Scheduled` 过期扫描、拆子单、已过期订单复活、真 Keycloak / 刷新令牌产品。

## 加厚后期

见 [plans/2026-10-24-product-thickening-later.md](plans/2026-10-24-product-thickening-later.md)：

- L1–L4 已完成；按计划停损——勿在本仓继续打补丁冒充准生产。  

## 架构约束

- 过期释放 = 取消语义（`CANCELLED` + `releaseReservation`），独立幂等键 `expire-{orderId}`。  
- 截止时刻是硬边界：`expires_at <= now` 时确认与改期都拒绝（`ORDER_STATE_CONFLICT` / 校验失败），只有取消/过期释放能释放台账，结果不依赖 sweep 是否已运行（INV-013）。  
- 部分确认：同订单行缩量，不拆单；`order_operations.effect_quantity` 进入幂等指纹（ADR 002）。  
- 改期：独立 `order_reschedule_operations`；条件更新绑定旧 `expires_at`（ADR 003）。  
- JWT 只做 AuthN → `Actor`（HS256 或静态 JWKS/RS256）；RBAC 仍在 Application。  
- 禁止 Domain 依赖 JWT/OIDC SDK。  
