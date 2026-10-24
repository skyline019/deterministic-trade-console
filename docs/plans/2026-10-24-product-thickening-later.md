# 产品示例 · 加厚后期路线图

> **For Claude:** 实施前先读本计划 + `docs/product.md` + `docs/consistency.md`；每波结束跑 `./mvnw -B verify` 与对应 jtest。禁止用运维脚手架冒充领域加厚。

**Goal:** 在不削弱确定性护栏的前提下，用**第二、第三个领域冲突**把样板从「薄产品片」加厚为可持续自学的架构应用教材。

**Architecture:** 继续 MySQL 事实源 + 显式 Application 用例 + Port；新写能力必须复用或扩展现有幂等命名空间与审计分链，禁止旁路改库存。AuthN 保持端口替换；多活/完整 IdP 仍受 ADR 001 约束。

**Tech Stack:** 现有 Java 17 / Spring Boot 3 / MySQL / Redis / Vue 3 / jtest / ArchUnit / Playwright。

**前提（已完成，勿重做）：** 正确性主轴、五缺口、部署剧本、观测演练、读写 P99、预留 TTL、JWT AuthN 夹具、真假路径叙事。

---

## 0. 原则（加厚判据）

每一波交付后必须能回答：

1. 新不变式落在 Domain 还是只在 Adapter？  
2. 是否新增/扩展 Port，还是弄脏了 Application？  
3. 失败是否可命名错误码 + 事务回滚？  
4. 证据是否不靠 Demo（jtest 必选；IT/同源 E2E 按波次）？

**不满足四条 = 虚胖，砍掉。**

### 明确不做（后期仍禁止）

| 项 | 原因 |
|---|---|
| 多活多写 / 跨库 exactly-once | 超出一致性模型（ADR 001） |
| 完整 Keycloak 发行方全家桶 | 教的是端口，不是装 IdP |
| `@Scheduled` 自动过期 | 破坏显式效果纪律 |
| 再叠一类 Compose overlay「像生产」 | 架构训练边际低 |
| 支付网关 / 微服务拆分 | 稀释单仓教材 |

---

## 1. 波次总览

```text
Wave L1  部分确认（PARTIAL）     ── 领域状态机 + 台账分数释放
    │
Wave L2  预留改期（expiresAt）   ── 同订单可变字段 + 幂等冲突矩阵
    │
Wave L3  证据补强（可选）         ── 过期/部分确认同源 E2E；默认 e2e 防误用
    │
Wave L4  AuthN 外接薄片（可选）   ── JWKS 夹具 / 关本地密码开关（仍非完整 IdP）
    │
Stop     或开新仓/新 ADR           ── 读副本、正式 IdP 等另立项
```

**推荐默认只做 L1 → L2**；L3/L4 按需。做完 L2 后重新「评估」再决定是否继续。

---

## 2. Wave L1 — 部分确认（主加厚）

### 产品故事

运营对 `RESERVED` 订单可 **部分确认**：确认数量 `q' < q`，剩余预留继续占用或随策略释放。  
**状态：L1 已落地**（ADR 002；缩量保留 RESERVED，不拆单）。

- **Partial confirm + 余量保持 RESERVED**（同一订单 `quantity` 变为剩余）。  
- 台账：`confirmReservation(sku, q')`；`reserved` 减 `q'`，`available` 不变。  
- 若余量为 0 → 等价全量确认（`CONFIRMED`）。

**备选（更重，勿与 MVP 同波）：** 部分确认后余量自动拆成新 `RESERVED` 订单（新 `request_id`）。留待未来。

### 架构课

- 状态机从「RESERVED 只能整单终态」变为「RESERVED 可缩量」——不变式必须写进 Domain。  
- 幂等：新命名空间或扩展 `order_operations`（如 `target_status=CONFIRMED` + `quantity` 指纹）——同键不同 `q'` → `409`。  
- 审计：`ORDER_PARTIAL_CONFIRMED` + `STOCK_CONFIRMED`（带 quantity）。

### 任务拆解

#### Task L1.1 — 规格与不变式（文档先行）

**Files:**
- Create/Modify: `docs/product.md`（追加「后期：部分确认」）
- Create: `docs/adr/002-partial-confirm.md`（策略选定、非目标）

**Acceptance:** ADR 写清：缩量 vs 拆单；台账公式；幂等键语义。

#### Task L1.2 — 红灯 jtest

**Files:**
- Create: `src/jtest/java/concept/PartialConfirmConcept.java`
- Modify: `ConceptEntrypoint.java`

**Cases（最小集）：**
1. 预占 5，部分确认 2 → reserved=3，订单仍 RESERVED（或 quantity=3），审计两条。  
2. 部分确认 5 → 全量 CONFIRMED。  
3. 部分确认 0 / >剩余 → `ValidationException`。  
4. 同幂等键不同 quantity → `IDEMPOTENCY_CONFLICT`。  
5. 已 CONFIRMED/CANCELLED 再部分确认 → 状态冲突。

**Step:** 先注册空概念失败 → 再实现。

#### Task L1.3 — Domain + Application

**Files（预期）:**
- Modify: `Order.java` / `OrderStatus`（若需 `partialConfirm` 行为）
- Modify: `OrderTransitionService` 或新建 `PartialConfirmService`
- Modify: `InventoryRepository.confirmReservation`（已有则复用）
- Modify: `OrderOperation` 校验（绑定 confirmed quantity）

**约束:** 不直接 JDBC 改 `reserved`；必须经现有台账方法。

#### Task L1.4 — API + 前端薄片

**Files:**
- Modify: `OrderHandler` / `ApiRoutes`（如 `POST .../confirmation` 带 optional `quantity`）
- Modify: `frontend` Orders 操作（部分确认输入）
- Modify: Demo gateway 对齐行为（仍非证据）

#### Task L1.5 — 验证

Run: `./mvnw -B verify`  
Expected: 新 jtest 全绿；ArchUnit 不破。  
Optional: IT 一条部分确认。

---

## 3. Wave L2 — 预留改期（expiresAt 变更）

**状态：L2 已落地**（ADR 003；独立 `order_reschedule_operations`；条件更新绑定旧 `expires_at`）。

### 产品故事

对仍为 `RESERVED` 且未过期的订单，允许 **延长/缩短 `expiresAt`**（在策略上限内，如 ≤ `trade.reservation-ttl` 从现在起算的上限）。

### 架构课

- 「可变字段」与终态指纹冲突：幂等键绑定 `(orderId, newExpiresAt)` 或独立 `order_operations` 类型 `EXTEND`。  
- 与 `expire-reservations` 竞态：改期与过期谁先提交——条件更新 `WHERE status=RESERVED AND expires_at=?` 或 version 乐观锁。  
- **禁止**改期绕过审计。

### 任务拆解

#### Task L2.1 — ADR + 故障矩阵行

**Files:** `docs/adr/003-reservation-reschedule.md`；`docs/consistency.md` 故障表追加一行。

#### Task L2.2 — jtest 红绿

**Cases:**
1. 改期成功，审计 `ORDER_RESCHEDULED`。  
2. 目标时间 ≤ now → 拒绝。  
3. 已过期订单改期 → 拒绝（须先走 expire/cancel）。  
4. 与 expire 并发：最多一个效果。

#### Task L2.3 — 服务 + API + 控制台字段

**Files:** Application 服务；Handler；UI 显示/编辑 `expiresAt`。

#### Task L2.4 — verify

---

## 4. Wave L3 — 证据补强（可选，低架构课）

**状态：已落地。**

| 项 | 目的 |
|---|---|
| 同源 E2E：部分确认 / 改期 | 防回归；非新领域 |
| `test:e2e` 警告 + CI 用 `test:e2e:demo` | 防误引 Demo 证据 |
| `scripts/expire-reservations.sh` | 过期运维冒烟（ops one-shot） |
| verification / product 总表 L3 行 | 文档对齐 |

**不要**为了 L3 引入新中间件。

---

## 5. Wave L4 — AuthN 外接薄片（可选）

**状态：已落地**（纯 JDK RS256 + 静态 JWKS JSON；无 nimbus / Keycloak）。

1. `JwksJwtAccessTokenAuthenticator`（`mode=jwks` + `jwks-json`）。  
2. `trade.security.password-login.enabled=false` → `POST /auth/token` → 403。  
3. jtest `JwksAuthn`：kid 轮换一正一反 + 禁密码登录。

**仍不做：** 真 Keycloak、刷新令牌轮换产品、设备绑定、JWKS HTTP 自动拉取。

---

## 6. 依赖与顺序

```text
L1.1 ADR ──► L1.2 jtest ──► L1.3 domain/app ──► L1.4 API/UI ──► L1.5 verify
                              │
                              └──► L2 可与 L1.4 后并行文档，但实现须串行（同碰 Order）
L3 / L4 不阻塞 L1–L2；可丢弃
```

---

## 7. 每波 Definition of Done

- [ ] ADR 或 product.md 更新策略  
- [ ] jtest 覆盖主成功路径 + ≥2 失败/冲突  
- [ ] `./mvnw -B verify` 绿  
- [ ] 无新的 `@Scheduled` / 无 Domain→JWT 依赖  
- [ ] review/verification 各增一行诚实边界  

---

## 8. 停损与再评估

完成 **L1+L2** 后执行一次「评估」：

- 若目标仍是架构自学 → **停止扩仓**，转向阅读/默写故障矩阵或换项目迁移技能。  
- 若目标变为「准生产交易台」→ 新开 ADR（读副本、正式 IdP），**不要**在本计划上继续打补丁。

---

## 9. 建议的下一句用户指令

- `开始落地 Wave L1` — 只做部分确认  
- `开始落地 L1+L2` — 两波连续（更长）  
- `只要规划，先不写码` — 已完成（本文）  
