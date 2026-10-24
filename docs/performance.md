# 性能证据协议（连接池 / 锁等待 / P99）

高压 jtest / IT / `contend.sh` 证明的是**结果确定性**，不是延迟 SLA。本协议规定如何陈述可重复的性能证据。

## 1. 固定配置（写入报告头）

| 旋钮 | 配置项 | 默认 | 含义 |
|---|---|---|---|
| DB 池 | `DB_POOL_SIZE` / `spring.datasource.hikari.maximum-pool-size` | 32 | 并发写上限参考 |
| 取连接等待 | `DB_CONNECTION_TIMEOUT_MS` | 2000 | 池耗尽时快速失败 |
| 事务墙钟 | `TRADE_TX_TIMEOUT_SECONDS` | 5 | 单笔写事务上限 |
| 锁 TTL | `TRADE_LOCK_TTL` | 10s | Redis 请求锁租约 |
| Redis I/O | `REDIS_TIMEOUT` | 2s | 锁/限流超时 → fail-closed |
| 锁重试 | `TRADE_LOCK_RETRY_ATTEMPTS` / `BACKOFF` | 3 / 50ms | 仅 Redis I/O；争用不重试 |
| API 限流 | `API_ATTEMPT_LIMIT` / `API_WINDOW` | 300 / 1m | 按 actor；Redis 故障 fail-closed |

报告必须抄录：CPU/内存、MySQL/Redis 版本、上述旋钮、`k6` 版本与脚本路径。

## 2. 场景矩阵

| ID | 场景 | 工具 | 成功判据 |
|---|---|---|---|
| P-READ | 已登录读 dashboard+inventory+orders | `scripts/k6-p99.js` | `http_req_failed<1%`，读路径 `p(99)<阈值` |
| P-WRITE | 已登录下单（大库存、唯一幂等键） | `scripts/k6-p99-write.js` | `http_req_failed<2%`，写路径 `p(99)<阈值`；**不**替代 contend |
| P-CONTEND | 100 请求抢 50 库存 | `scripts/contend.sh` | created=50 / 409=50 / 台账守恒 |
| P-IT | 容器 HTTP 并发 | `HighConcurrencyIT` / `HttpApiE2eIT` | 断言精确结果（无耗时阈值） |

**禁止**：用 P-IT / P-CONTEND 的通过冒充 P99；用 P-WRITE 冒充争用正确性；用单机 k6 冒充多地域容量。

## 3. 读路径 P99（示例）

```bash
export BOOTSTRAP_ADMIN_PASSWORD=local-admin-only
# Compose 已健康
k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD="$BOOTSTRAP_ADMIN_PASSWORD" \
  -e P99_MS=250 scripts/k6-p99.js
```

## 3b. 写路径 P99（示例）

```bash
k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD="$BOOTSTRAP_ADMIN_PASSWORD" \
  -e P99_MS=500 -e VUS=1 -e DURATION=45s -e THINK=0.3 scripts/k6-p99-write.js
```

- setup 种大库存 SKU；每个迭代独立 `Idempotency-Key`，避免重放短路把延迟测「假快」。
- 默认 `API_ATTEMPT_LIMIT=300/min` ≈ 5 rps；探针须 `VUS×(1/THINK)` 低于该值，否则大量 429 会污染失败率（≠锁/DB 问题）。
- 阈值默认宽于读路径（写含 Redis 锁 + DB 事务）；按硬件调整。
- 若大量 `LOCK_UNAVAILABLE` / 5xx：先查 Redis、池；若 429：降 VU 或临时提高限流。

阈值按硬件调整；失败时先查 Hikari 等待、Redis timeout、锁争用，而不是盲目加大 VU。

## 4. 锁等待观察

- 应用日志：`LOCK_UNAVAILABLE` / `PERSISTENCE_UNAVAILABLE` 比率。
- MySQL：`SHOW GLOBAL STATUS LIKE 'Threads_running'`；慢查询。
- Redis：`SLOWLOG`；锁 key 前缀争用。

陈述模板：

> 在 {硬件}、池={N}、TTL={T}、VU={V}、持续={D} 下，读路径 p99={x}ms，写路径 p99={w}ms，错误率={y}%，contend 结果守恒。

## 5. 非目标

- 支付级认证、跨机房、线性扩展证明。
- 在 CI 默认门禁绑定绝对 P99（环境噪声大）；CI 保留正确性套件，P99 作为发布前手工/夜间任务。
- 正式 IdP / 多活：见 [adr/001-idp-and-multi-active.md](adr/001-idp-and-multi-active.md)。
