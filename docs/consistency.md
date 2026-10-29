# 一致性模型与故障边界

## 事实源与承诺

**MySQL 是库存、订单、命令回执、用户/Token 哈希和审计的事实源。Redis 只提供请求级 TTL 互斥与登录固定窗口计数。**

防超卖依赖 InnoDB 条件更新和约束，不依赖分布式锁永不过期：

```sql
UPDATE inventory
SET available_quantity = available_quantity - ?,
    reserved_quantity = reserved_quantity + ?
WHERE sku = ? AND available_quantity >= ?;
```

库存、订单/回执和审计在同一个本地 `READ_COMMITTED` 事务内提交。系统承诺的是这套边界内的原子性与可重放，不承诺外部调用方角度的 exactly-once。

## 双台账守恒

对单次预占数量 `q`：

| 操作 | available | reserved | 订单 |
|---|---:|---:|---|
| 预占 | `-q` | `+q` | `RESERVED` |
| 取消 | `+q` | `-q` | `CANCELLED` |
| 确认 | 不变 | `-q` | `CONFIRMED` |

每个 SKU 满足 `available >= 0`、`reserved >= 0`、`available + reserved <= 100000000`。终态迁移用 `WHERE id=? AND status='RESERVED'`，因此并发确认/取消只有一个能提交。手工调整只改变 available，并同时检查非负与含预占的容量上限。

## 幂等与竞态

- 下单：`orders.request_id` 唯一；订单保存 SKU/数量指纹。
- 库存命令：`inventory_operations.request_id` 唯一，并绑定 SKU、类型、delta、原因。
- 状态迁移：`order_operations.request_id` 唯一，并绑定订单、目标状态、原因。
- 三个命名空间相互独立，同一个字符串可以合法用于不同业务操作。
- 同键同负载返回原回执或当前订单；同键不同负载返回 `409 IDEMPOTENCY_CONFLICT`。
- 唯一键异常必须离开原事务模板，待败方回滚后再读取胜方；不能在事务 lambda 内捕获后继续提交。

Redis 锁显著减少同键冲突，但不是正确性的最后防线。锁 key 为请求命名空间，不让所有 SKU 或订单共享一把全局锁。

## 故障矩阵

| 故障 / 边缘 | 确定结果 | 防线 |
|---|---|---|
| 库存更新后，订单/回执/审计插入失败 | 整个事务回滚 | 本地 DB 事务 |
| 两节点绕过 Redis，同键并发 | 最多一个效果，败方回滚后重放或冲突 | 唯一键 + 事务 |
| 同 SKU 不同键并发 | 条件更新决定成功集合，不超卖 | InnoDB 行级原子更新 |
| 同订单确认/取消竞争 | 只有一个状态与台账效果 | 条件状态更新 + 回滚 |
| 锁句柄过期后释放 | 不删除新 owner 的锁 | Lua 比较 owner token |
| 锁释放失败 | 不覆盖业务结果，锁由 TTL 清理 | 幂等 close + TTL |
| DB 提交后响应丢失 | 同键重试返回原效果 | 回执 / 指纹 |
| 进程在提交后、解锁前崩溃 | TTL 内可能 `REQUEST_IN_PROGRESS`，之后可重放 | TTL + 幂等读取 |
| Redis 不可用 | 写命令/登录/API 限流 fail-closed，不绕过互斥；锁 I/O 有界重试后仍失败 | `LockUnavailableException` / `RetryingLockManager` |
| RESERVED 超过 `expires_at` | 运维 `expire-reservations` 按取消释放台账；非隐式 `@Scheduled` | 同 `OrderTransitionService` + 幂等键 `expire-{orderId}` |
| 改期与 expire/cancel 并发 | 最多一个效果：条件更新 `status=RESERVED AND expires_at=?` 或终态迁移 | ADR 003；败方 `ORDER_STATE_CONFLICT` |
| 对已过期订单改期 | 拒绝；不可绕过 expire/cancel「复活」 | Domain `isExpiredAt` + Application 校验 |
| 对已过期订单确认 | 拒绝（`ORDER_STATE_CONFLICT`），与 sweep 是否已运行无关 | `OrderTransitionService.confirm` 在事务内校验 `isExpiredAt`（INV-013） |
| 审计归档后继续追加 | 读取跨越 `audit_events` 与 `audit_events_archive`，sequence 从归档 tip 继续，验链仍覆盖已归档前缀 | `MySqlAuditRepository` UNION 读取 + 有界 copy/delete（INV-014） |
| 归档与并发追加交错 | 复制集合与删除集合都由复制前的 `MAX(sequence)` 界定，窗口内新事件留在在线表 | `MySqlMaintenanceStore` 有界删除 |
| 维护批次中单个订单失败 | 该订单记入 `SweepReport`，其余订单继续，批次不会整体回滚 | `MaintenanceService` 逐项隔离（INV-015） |
| 超长活跃审计链 | 可选 `CHAIN_CHECKPOINT`；验链可用外带信任的 checkpoint hash 只验后缀 | `AuditChainVerifier.verifySinceTrusted` |
| 登录限流复位失败 | 不持久化未返回的 Token | reset 先于 Token 事务 |
| Token 过期、撤销或用户禁用 | 后续请求 401 | 数据库 active actor 查询 |
| 审计任意绑定字段被改 | 验链失败 | 连续 sequence、前序哈希、内容 SHA-256 |
| 同键风暴中尚未出现 winner | 允许 `REQUEST_IN_PROGRESS`，调用方稍后重试 | 不把 BUSY 伪装成成功 |

## 审计链一致性

订单链和库存链分别验证，不能混成一个全局序列。一个预占产生 `ORDER_RESERVED` 与 `STOCK_RESERVED`，二者与业务事务同生共死；取消/确认同样产生两条分链事件。

`findRecent` 是读取最近 N 条的展示查询；完整验证必须读取该聚合全部事件，并检查第一条 previous hash、连续 sequence、每个绑定字段与后序链接。空链有明确结果，不把“没读到”当成“已证明有很多事件”。

归档（`trade.maintenance.command=archive-audit`）只搬运整条聚合链，绝不切断它：读取同时覆盖在线表与归档表，归档后新的追加会从归档 tip 的 sequence+1 继续，`verify` 仍校验包含已归档前缀的完整链（`checkedEvents` 不为 0）。归档表被外部删除属于**可检测**的篡改，`verify` 会失败而不是返回"无事件即通过"。

运维可对长链追加 `CHAIN_CHECKPOINT`（显式 `trade.maintenance.command=checkpoint-audit`）。增量验链要求调用方**外带信任**该检查点的 `eventHash`（`trustedCheckpointHash` 查询参数）；检查点本身不能自我证明前缀未被篡改。

## 不承诺的能力

- 不承诺跨数据库、跨区域或外部支付系统的原子事务。
- 不承诺网络调用 exactly-once；客户端必须保存并复用幂等键。
- 不把单节点 Redis 锁或其续期当作库存正确性证明。
- 不用高并发正确性测试替代 QPS、P99、连接池容量或锁等待压测。
- 不存在公开的无认证库存重置 PUT；创建与调整均是受 RBAC 和审计约束的命令。
