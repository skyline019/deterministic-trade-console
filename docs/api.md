# HTTP API 契约

本文件是 `ApiRoutes` + `ApiErrorHandler` + 安全过滤器暴露的**唯一**对外契约。改这些类就要改这里；
`docs/consistency.md` 说明为什么响应码是这样（哪些是幂等重放、哪些是竞争失败）。

## 1. 通用约定

| 项 | 约定 |
| --- | --- |
| Base | `/api/v1`（`/actuator/health|info|prometheus` 另行暴露） |
| 媒体类型 | 请求与响应均为 `application/json`（`POST /auth/token` 的 `delivery=cookie` 亦返回 JSON，会话在 Cookie 里） |
| 时间 | RFC 3339 / ISO-8601 UTC 字符串，例如 `2026-01-05T09:30:00Z`；库内 `TIMESTAMP(6)`，毫秒以下精度不承诺 |
| 字段命名 | camelCase；**`null` 字段被省略**（`spring.jackson.default-property-inclusion=non_null`），所以“字段不存在”= 该字段为 null，例如末页没有 `nextCursor`、Cookie 会话没有 `accessToken` |
| 相关性 | 每个响应带 `X-Request-Id`（请求带同名头则沿用，否则服务端生成），并写入日志 MDC |
| 缓存 | `/api/**` 与 `/actuator/**` 响应带 `Cache-Control: no-store` |
| 安全头 | `X-Content-Type-Options`、`X-Frame-Options: DENY`、`Referrer-Policy`、`Permissions-Policy`、`Content-Security-Policy`；HTTPS 下追加 `Strict-Transport-Security` |
| 未认证入口 | 只有 `POST /api/v1/auth/token` 与 `/actuator/health**` 不需要凭据 |

所有 `/api/**` 请求先进 `BearerSecurityFilter`（认证 / CSRF / 安全头）→ `ApiRateLimitFilter`（按 actor 固定窗口）
→ `RouterFunction`（`ApiErrorHandler` 包裹）。因此**错误可能来自过滤器，也可能来自路由**，两者使用同一个
`ErrorResponse` 形状。

## 2. 认证与凭据

两种交付方式，业务接口的形状完全一致：

1. **Bearer（默认）**：`Authorization: Bearer <opaque-token>`；token 只以 SHA-256 摘要形式存 MySQL（`api_tokens`）。
2. **Cookie 会话（可选）**：`POST /auth/token` 带 `{"delivery":"cookie"}`，返回 `HttpOnly` 的 `__Host-TradeSession`
   与非 HttpOnly 的 `TradeXsrf`，JSON 里**不带** `accessToken`（避免 XSS 读到会话密钥）。
   - 要求 `trade.security.cookie.secure=true`（前置 TLS 终结器），否则 `400 INVALID_REQUEST`；
   - 非安全方法（非 GET/HEAD/OPTIONS/TRACE）必须回传 `X-XSRF-TOKEN` 头，值与 `TradeXsrf` 一致，否则
     `403 CSRF_REJECTED`（double-submit）；
   - 没有 Bearer 头且 cookie 未配置为 secure 时不接受 Cookie 凭据（`401 UNAUTHENTICATED`）。

`TRADE_PASSWORD_LOGIN_ENABLED=false` 时 `POST /auth/token` 一律 `403 ACCESS_DENIED`（此时只接受外部 JWT，
见 `docs/secrets.md`）。`/actuator/**` 需要 `METRICS_READ`（`AUDITOR`/`ADMIN`）。

### 权限矩阵

权限在应用服务里显式 `actor.require(...)`，不在路由或注解散布。

| 端点 | 权限 | 默认角色（ADMIN 全有） |
| --- | --- | --- |
| `POST /auth/token`、`GET /auth/me`、`DELETE /auth/token` | — | 任意已认证用户（登录本身免认证） |
| `GET /dashboard` | `INVENTORY_READ` + `ORDER_READ` | OPERATOR / VIEWER / AUDITOR |
| `POST /orders`、`POST /orders/{id}/confirmation`、`/cancellation`、`/reschedule` | `ORDER_WRITE` | OPERATOR |
| `GET /orders`、`GET /orders/{id}` | `ORDER_READ` | OPERATOR / VIEWER / AUDITOR |
| `POST /inventory`、`POST /inventory/{sku}/adjustments` | `INVENTORY_WRITE` | OPERATOR |
| `GET /inventory`、`GET /inventory/{sku}` | `INVENTORY_READ` | OPERATOR / VIEWER / AUDITOR |
| `GET /audit/events`、`GET /audit/verification` | `AUDIT_READ` | AUDITOR |
| `/actuator/**` | `METRICS_READ` | AUDITOR |

## 3. 幂等（`Idempotency-Key`）

**所有写接口都要求 `Idempotency-Key`**，并把它映射到领域 `RequestId`：

- 长度 1..128，不能含空白或控制字符，不能有首尾空格（API 层 `RequestId` 构造即校验 → `400 INVALID_REQUEST`）；
- 语义是**按键去重**，不是“多次请求只生效一次”的魔法：同一键 + **同一内容** → 重放首次结果，返回 `200`
  并带 `Idempotent-Replay: true`（`POST /orders`、`POST /inventory` 首次成功是 `201` + `false`）；
- 同一键 + **不同内容** → `409 IDEMPOTENCY_CONFLICT`（不会覆盖首次结果）；
- 同一键并发写 → Redis 互斥锁，未拿到锁返回 `409 REQUEST_IN_PROGRESS`；
- 键的作用域：订单写入是全局唯一的 `orders.request_id` / `order_operations.request_id`；
  库存写入是 `inventory_operations.request_id`。运维清零/换环境后键可复用。

响应头 `Idempotent-Replay` 出现在 `POST /orders`、`/confirmation`、`/cancellation`、`/reschedule`、`/inventory`、
`/inventory/{sku}/adjustments` 上。

## 4. 分页（键集游标）

`GET /orders?status=&sku=&cursor=&limit=`：

- `limit` 1..100，默认 30；越界或非整数 → `400 INVALID_REQUEST`；
- `cursor` 是**不透明**的 Base64URL 串，只编码 `(createdAt, id)` 位置，不含权限或隐藏状态；解码失败 → `400`；
- 排序固定 `created_at DESC, id DESC`（最新在前），游标翻页不会重复也不会漏新行；
- 响应 `{"items":[...], "nextCursor":"..."}`；末页没有 `nextCursor` 字段（null 被省略）。

`GET /audit/events?type=&id=&limit=` 不使用游标：`limit` 1..500，默认 100，返回**最近 N 条**且按 `sequence` 升序
（即时间正序的尾部窗口）。

## 5. 错误契约

```json
{ "code": "OUT_OF_STOCK", "message": "insufficient stock for SKU-1", "path": "/api/v1/orders" }
```

`code` 是稳定标识（前端 `frontend/src/domain/errors.ts` 按它做文案映射），`message` 仅用于人读与日志，
**不要**对它做逻辑判断。`path` 是请求路径（不含 query）。

| `code` | HTTP | 触发点 | 客户端应做什么 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 | 参数/JSON/游标/幂等键非法，`limit` 越界，Cookie 交付未开 TLS | 修正请求，不重试 |
| `UNAUTHENTICATED` | 401 | 缺凭据、token 过期/被撤销 | 重新登录 |
| `ACCESS_DENIED` | 403 | 角色缺权限；密码登录被关闭 | 换角色或换入口 |
| `CSRF_REJECTED` | 403 | Cookie 会话的非安全方法缺 `X-XSRF-TOKEN` | 前端补头（`TradeXsrf` 值） |
| `NOT_FOUND` | 404 | 订单 / SKU 不存在 | 刷新列表 |
| `OUT_OF_STOCK` | 409 | 预留失败（含 SKU 不存在） | 提示库存不足，不重试 |
| `ORDER_STATE_CONFLICT` | 409 | 订单已终态 / 已过期，迁移被拒 | 刷新订单状态 |
| `RESOURCE_CONFLICT` | 409 | `POST /inventory` 时 SKU 已存在 | 改用 `adjustments` |
| `STOCK_ADJUSTMENT_REJECTED` | 409 | 调整越界（结果 < 0 或超上限） | 修正 `delta` |
| `IDEMPOTENCY_CONFLICT` | 409 | 同键不同内容 | 换键，或复用原内容 |
| `REQUEST_IN_PROGRESS` | 409 | 同键并发请求持锁中 | 用**原键**稍后重试 |
| `LOGIN_RATE_LIMITED` | 429 | 登录尝试超限（`LOGIN_ATTEMPT_LIMIT`/`LOGIN_WINDOW`） | 按 `Retry-After` 退避 |
| `API_RATE_LIMITED` | 429 | 按 actor 的通用限流（`API_ATTEMPT_LIMIT`/`API_WINDOW`） | 按 `Retry-After` 退避 |
| `LOCK_UNAVAILABLE` | 503 | Redis 不可达（fail-closed，不静默跳过） | 退避重试 |
| `PERSISTENCE_UNAVAILABLE` | 503 | MySQL 不可达 | 退避重试 |
| `AUTH_UNAVAILABLE` | 503 | 认证查询期间数据库不可用 | 退避重试 |
| `STORED_STATE_INVALID` | 500 | 库里的行已不满足领域不变量（`MySqlOrderRepository.mapRow` 无法物化） | **不要重试**：需要运维修数据 |
| `INTERNAL_ERROR` | 500 | 未分类异常 | 报 `X-Request-Id` |

`429` 响应带 `Retry-After`（秒）。`5xx` 与 `429` 都是“重试可能成功”，`4xx` 都是“请求本身要改”，唯一例外是
`STORED_STATE_INVALID`：请求没错，服务端存储坏了，重试永远不会好——这类行由
`V7__order_timestamp_invariants.sql` 的 CHECK 约束挡在写入侧，历史脏行需要人工修（见 `docs/migrations.md`）。

## 6. 端点参考

### 6.1 `POST /api/v1/auth/token` — 登录（免认证）

```json
{ "username": "admin", "password": "…", "delivery": "cookie" }
```

`delivery` 省略等价于 `"bearer"`。响应 `200`：

```json
{ "accessToken": "…", "tokenType": "Bearer", "expiresAt": "2026-01-05T17:30:00Z",
  "actor": { "id": "…", "username": "admin", "roles": ["ADMIN"] } }
```

Cookie 交付时 `accessToken` 缺失、`tokenType` 为 `"Cookie"`，并 `Set-Cookie` 两个 Cookie。
状态码：`200`；`400`（body 缺失 / 非法 `delivery` / cookie 交付未开 secure）；`401`（用户名或密码错误，
统一文案，不区分账号是否存在）；`403`（密码登录被关闭）；`429 LOGIN_RATE_LIMITED`；`503 AUTH_UNAVAILABLE`。
登录节流以客户端 IP + 用户名计数，`X-Forwarded-For` 默认**不**采信（`TRADE_FORWARD_HEADERS_STRATEGY=none`）。

### 6.2 `GET /api/v1/auth/me`

返回 `{"id","username","roles":[...]}`。`200`；`401`/`403` 同全局。

### 6.3 `DELETE /api/v1/auth/token` — 登出

删除当前 token 记录（Bearer）或清空两个 Cookie（Cookie 会话）。`204`，无 body。

### 6.4 `GET /api/v1/dashboard`

```json
{ "skuCount": 3, "availableStock": 120, "reservedStock": 7, "totalOrders": 42,
  "reservedOrders": 4, "confirmedOrders": 30, "cancelledOrders": 8 }
```

### 6.5 `POST /api/v1/orders` — 预留（下单）

请求头 `Idempotency-Key` 必需。请求体：

```json
{ "sku": "SKU-1", "quantity": 2 }
```

响应 `201`（首次）/ `200`（重放），带 `Location: /api/v1/orders/{id}` 与 `Idempotent-Replay`：

```json
{ "id": "…", "requestId": "order-1", "sku": "SKU-1", "quantity": 2, "status": "RESERVED",
  "createdAt": "2026-01-05T09:30:00Z", "updatedAt": "2026-01-05T09:30:00Z",
  "expiresAt": "2026-01-05T10:30:00Z", "version": 0 }
```

`expiresAt = createdAt + trade.reservation-ttl`（默认 `1h`），到期由 `expire-reservations` 走与手动取消相同的
账本（`docs/migrations.md`）。终态订单的 `expiresAt` 保留原值。

状态码：`400`（body/SKU/数量/幂等键非法）；`409 OUT_OF_STOCK`、`IDEMPOTENCY_CONFLICT`、
`REQUEST_IN_PROGRESS`；`503 LOCK_UNAVAILABLE` / `PERSISTENCE_UNAVAILABLE`。

### 6.6 `GET /api/v1/orders/{id}`

`200` 返回单个订单（形状同上）；`404 NOT_FOUND`；`500 STORED_STATE_INVALID`（该行已不满足领域不变量）。

### 6.7 `GET /api/v1/orders`

查询参数 `status`（`RESERVED|CONFIRMED|CANCELLED`）、`sku`、`cursor`、`limit`，见第 4 节。

```json
{ "items": [ { "id": "…", "status": "RESERVED", "…": "…" } ], "nextCursor": "eyJ…" }
```

### 6.8 `POST /api/v1/orders/{id}/confirmation` — 确认（支持部分确认）

```json
{ "reason": "customer paid", "quantity": 1 }
```

`quantity` 省略/null = 确认全部剩余；部分确认后订单**仍为 `RESERVED`**、`quantity` 收敛为剩余量
（ADR 002）。响应 `200` + `Idempotent-Replay`。状态码：`400`（数量越界/缺失）；`404`；`409 ORDER_STATE_CONFLICT`
（非 `RESERVED`）；`409 IDEMPOTENCY_CONFLICT`；`409 REQUEST_IN_PROGRESS`。

### 6.9 `POST /api/v1/orders/{id}/cancellation` — 取消

```json
{ "reason": "customer changed mind" }
```

一次性释放**全部**剩余预留，不接受 `quantity`（带了就 `400`）。`200` + `Idempotent-Replay`。

### 6.10 `POST /api/v1/orders/{id}/reschedule` — 改约到期时间

```json
{ "expiresAt": "2026-01-05T12:00:00Z", "reason": "extend hold" }
```

要求 `expiresAt` 晚于当前时间且不超过 `reservation-ttl` 允许的最晚时刻；已过期或非 `RESERVED` 的订单拒绝。
`200` + `Idempotent-Replay`；`400`；`409 ORDER_STATE_CONFLICT`（ADR 003）。

### 6.11 `POST /api/v1/inventory` — 建 SKU 并初始化库存

```json
{ "sku": "SKU-1", "initialStock": 100, "reason": "seed" }
```

`201`（首次）/ `200`（重放）→ `InventoryOperationResponse`：

```json
{ "requestId": "inv-1", "sku": "SKU-1", "operation": "CREATE", "delta": 100, "reason": "seed",
  "resultingAvailable": 100, "actorId": "…", "createdAt": "2026-01-05T09:30:00Z", "replayed": false }
```

`409 RESOURCE_CONFLICT` 表示 SKU 已存在（用 `adjustments` 改数量）。

### 6.12 `POST /api/v1/inventory/{sku}/adjustments` — 调整可售库存

```json
{ "delta": -5, "reason": "damaged" }
```

`200` → 同上结构（`operation: "ADJUST"`、`delta` 为带符号值）。`409 STOCK_ADJUSTMENT_REJECTED` 同时覆盖
“SKU 不存在”和“结果越界（< 0 或超上限）”——这是**刻意保留的单一错误码**，不要拆成 404。

### 6.13 `GET /api/v1/inventory`、`GET /api/v1/inventory/{sku}`

单项：`{"sku","available","reserved","version","updatedAt"}`；列表返回同样对象的数组（无分页，SKU 数量有限）。
`404 NOT_FOUND` 仅针对单 SKU。

### 6.14 `GET /api/v1/audit/events?type=&id=&limit=`

`type` ∈ `ORDER|INVENTORY`，`id` 为聚合 id（订单 uuid 或 SKU），两者必填。返回按 `sequence` 升序的最近 N 条：

```json
[ { "id": "…", "aggregateType": "ORDER", "aggregateId": "…", "sequence": 1, "action": "ORDER_RESERVED",
    "actorId": "…", "actorName": "admin", "requestId": "order-1", "occurredAt": "…",
    "payloadJson": "{\"sku\":\"SKU-1\",…}", "previousHash": "…", "eventHash": "…" } ]
```

`payloadJson` 是**字符串**（原始 JSON 文本），不是嵌套对象——它参与哈希链，不能重排字段。

### 6.15 `GET /api/v1/audit/verification?type=&id=&trustedCheckpointHash=`

```json
{ "valid": true, "checkedEvents": 3, "message": "full chain verified through sequence 3" }
```

读取覆盖 `audit_events` + `audit_events_archive`，因此归档后仍校验整链（INV-014）。带
`trustedCheckpointHash`（64 位小写 SHA-256）时只校验该 checkpoint 之后的**后缀**，checkpoint 不存在返回
`{"valid":false,"checkedEvents":0,"message":"trusted checkpoint hash not found"}`（`200`，不是错误）。
非法哈希 → `400 INVALID_REQUEST`。`message` 仅人读。

## 7. 示例

```bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/token" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"local-admin-only"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

curl -fsS -X POST "$BASE/api/v1/inventory" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-inv-1' \
  -d '{"sku":"SKU-1","initialStock":10,"reason":"seed"}'

# 201 + Idempotent-Replay: false；用同一个键重发 → 200 + Idempotent-Replay: true
curl -sS -D- -o /dev/null -X POST "$BASE/api/v1/orders" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-order-1' \
  -d '{"sku":"SKU-1","quantity":2}'

curl -fsS "$BASE/api/v1/orders?status=RESERVED&limit=10" -H "Authorization: Bearer $TOKEN"
```

## 8. 一致性

- 成功写入的响应体来自**事务内**的状态；`Idempotent-Replay: true` 表示这次没有产生新的状态迁移。
- 并发竞争导致的失败一律是 `409`（或 `503 LOCK_UNAVAILABLE`），**不会**出现 `200` + 半成品。
- 审计事件与业务写入同事务提交：`GET /orders/{id}` 能看到的状态，一定已经有对应的审计事件。
- 契约由谁钉住：`src/jtest`（无框架的概念测试，含幂等/部分确认/改约）、
  `src/test/java/com/acme/trade/adapter/web/ApiErrorHandlerTest`（状态码 + `code` + 不外泄内部细节）、
  `scripts/smoke.sh` 与 `scripts/ci-maintenance-check.sh`（真实 Compose 栈上的 HTTP 断言）、以及本文件；
  改行为要同时改这些地方。
