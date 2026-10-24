# 验证记录

日期：2026-10-24（Asia/Shanghai）。WSL2 + Docker Engine 29 + OpenJDK 17 + Anyi HTTP 代理。以下均为本环境**实际执行**结果。

## 后端默认验证

```bash
./mvnw -B verify
```

结果：`BUILD SUCCESS`。

- JUnit / ArchUnit / Lua / Schema / SQL 性能契约：22 passed。
- 自研 jtest：76 checks passed。

## 容器集成（`-Pintegration`）

```bash
./mvnw -B -Pintegration verify
```

结果：`BUILD SUCCESS`。

- `HighConcurrencyIT`：4 passed（两轮 failsafe 执行合计一致）。
- `HttpApiE2eIT`：8 passed（登录/RBAC/幂等/审计验链/限流/分页/HTTP 并发）。
- 合计 failsafe：12 IT scenarios passed。

本轮为打通 IT 做的修复：

| 问题 | 修复 |
|---|---|
| Docker Engine 29 拒绝 Testcontainers 旧 API | `pom.xml` Testcontainers → 1.21.3；daemon `min-api-version: 1.24` |
| `@SpringBootTest` 默认关闭 metrics export → `/actuator/prometheus` 404 | `HttpApiE2eIT` 增加 `@AutoConfigureObservability`；Prometheus Accept 使用 `text/plain` |
| MySQL `TIMESTAMP(6)` 截断纳秒导致审计哈希验链失败 | `AuditEvent.append` 将 `occurredAt` 截断到微秒再入哈希 |
| IT 断言过时的指标名 `trade_order_placement_total` | 改为实际 meter `trade_order_requests_total` |

## Docker Compose + 黑盒脚本

```bash
export BOOTSTRAP_ADMIN_PASSWORD='local-admin-only'
docker compose up --build -d
./scripts/smoke.sh http://127.0.0.1:8080
./scripts/contend.sh http://127.0.0.1:8080
```

结果：

- 镜像多阶段构建成功（npm 使用 `registry.npmmirror.com` + BuildKit 代理参数）。
- 主机端口冲突规避：MySQL `3307→3306`，Redis `6380→6379`；应用仍 `8080`。
- `SMOKE OK`（登录→建库存→下单→重放/冲突→取消→验链→注销）。
- `CONTENTION OK: created=50 out_of_stock=50 available=0 reserved=50`。

## 同源浏览器 E2E（真实 Boot，非 Demo）

Compose 已将 Vue 静态资源打入 Boot JAR。针对 `http://127.0.0.1:8080`：

```bash
./scripts/seed_same_origin_users.sh   # operator/viewer/auditor，密码 e2e-role-secret
cd frontend
npx playwright test -c playwright.same-origin.config.ts
# 或：npm run test:e2e:same-origin
```

结果：Playwright **8 passed**（约 53s）。

- 使用真实 API 英文错误文案（与 Demo 中文文案区分）。
- 覆盖登录失败/注销、三角色、完整业务闭环、失败无副作用、移动导航、会话恢复、审计角色、keyset 分页。

> `npm run test:e2e`（无后缀）会先打印警告再跑 Demo；CI / 文档请用显式 `test:e2e:demo`。不能替代同源证据。

## 运维/体验增强（本轮代码落地）

- Flyway `V1`/`V2` + `docs/migrations.md` 备份回滚剧本。
- `docs/performance.md` + `scripts/k6-p99.js`（P99 陈述协议）。
- `MaintenanceRunner`：`purge-tokens` / `archive-audit` 显式批处理（无 `@Scheduled`）。
- 前端 `domain/errors.ts`：API code → 中文，同源与 Demo 对齐。

## 五缺口补强（同日后续）

后端 `./mvnw -B verify`：**BUILD SUCCESS**（JUnit/ArchUnit 23 + jtest **78** checks，含锁重试 / API 限流 / 检查点验链）。

前端 `npm test`：**27 passed**（含 `session` memory / sessionStorage 模式）。

## 部署级 + 可选 Cookie（同日再后续）

- Compose：Caddy `:8443` TLS；`docker-compose.ha.yml` Sentinel；`--profile obs` Prometheus/Alertmanager；`docs/deployment.md`。
- Cookie：`delivery=cookie` + `__Host-TradeSession` + Double-submit CSRF；默认 Bearer；HTTPS 前端自动切 Cookie。
- 后端 `./mvnw -B verify`：**BUILD SUCCESS**（ArchUnit 23 + jtest **79**，含 `CookieAuthDelivery`）。
- 前端 `npm test`：**27 passed**。
- 密钥外接：`ops/secrets/.env.example` + `docs/secrets.md`（env-only，不入镜像）。

代码落地摘要：

| 缺口 | 落地 |
|---|---|
| Redis fail-closed 可用性 | `RetryingLockManager`（默认 3×50ms，仅 I/O；争用不重试） |
| 通用限流 / tracing / 告警 | `ApiRateLimitFilter` + `X-Request-Id`/MDC + `ops/prometheus-alerts.yml` |
| 超大活跃审计链 | `CHAIN_CHECKPOINT` + `trustedCheckpointHash` + `checkpoint-audit` |
| XSS / sessionStorage | `VITE_SESSION_PERSIST=memory`；CSP `object-src 'none'` |

### Cookie HTTPS E2E（实测）

前置：Compose 含 Caddy `:8443`（自签证书；Playwright `ignoreHTTPSErrors`）。

```bash
cd frontend && npm run test:e2e:https-cookie
```

结果：Playwright **2 passed**（约 1.9m）。

- 登录 JSON 无 `accessToken`；会话不进 JS 可读存储。
- 缺 `X-XSRF-TOKEN` → 拒绝；匹配 Double-submit → 写路径通过。

### Redis Sentinel HA 演练（实测）

```bash
docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
./scripts/ha-failover-drill.sh
```

结果：`HA_FAILOVER_OK`。

| 项 | 值 |
|---|---|
| `master_before` | `172.19.0.3:6379`（服务 `redis`） |
| `master_after` | `172.19.0.4:6379`（原 replica 提升；**地址必须变化**） |
| 故障后下单 | `place_attempts=1`（201/200） |
| 正确性 | 窗口内可 503；恢复后写成功；全程不 fail-open |

演练后需 `docker compose … up -d redis`（或 `--force-recreate`）恢复原主，再按需切回非 HA Compose。

### P99 实测（目标硬件，按 `docs/performance.md`）

硬件/栈（WSL2）：

- CPU：8 逻辑核；内存：11 Gi
- Docker 29.1.3；MySQL 8.4.13；Redis 7.4.11；k6 v0.54.0
- Compose：`app:8080`，MySQL `3307→3306`，Redis `6380→6379`
- 旋钮默认：`DB_POOL_SIZE=32`，`TRADE_LOCK_TTL=10s`，`REDIS_TIMEOUT=2s`，`P99_MS=500`

主跑（协议场景 P-READ）：

```bash
k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD=local-admin-only \
  -e P99_MS=500 -e VUS=10 -e DURATION=45s scripts/k6-p99.js
```

结果（阈值全绿，含显式 p(99)）：

| 指标 | 值 |
|---|---|
| `http_req_failed` | 0%（0 / 21581） |
| `checks` | 100% |
| `http_reqs` | ≈528/s |
| `http_req_duration` avg / med / p(95) / **p(99)** / max | 3.85 ms / 3.07 ms / 7.88 ms / **9.9 ms** / 367 ms |
| 门禁 | `p(99)<500` 通过 |

陈述模板填充：

> 在 WSL2 8c/11Gi、池=32、TTL=10s、VU=10、持续=45s 下，读路径 p99=9.9 ms，错误率=0%，contend 此前已守恒。

说明：打在当时已健康的 Compose 读路径；若应用镜像未 `--build`，不含本轮过滤器增量。过滤器为 O(1) Redis INCR + 头处理。复核用上文 `k6 run …` 命令即可。

### 观测 METRICS_READ scrape + 告警演练（实测）

```bash
./scripts/obs-alert-drill.sh
```

结果：`OBS_ALERT_DRILL_OK`。

| 项 | 值 |
|---|---|
| scrape | `/actuator/prometheus` + `ops/secrets/metrics_bearer.token`（admin/`METRICS_READ`） |
| `up{job="deterministic-trade"}` | 1 |
| 业务指标 | `trade_order_requests_total` 经 Prometheus 可见 |
| 告警 | 停 app → `TradeInstanceDown` **firing** → 拉起恢复 |

### 写路径 P99（实测，协议 P-WRITE）

```bash
k6 run -e BASE_URL=http://127.0.0.1:8080 -e PASSWORD=local-admin-only \
  -e P99_MS=500 -e VUS=1 -e DURATION=45s -e THINK=0.3 scripts/k6-p99-write.js
```

| 指标 | 值 |
|---|---|
| `http_req_failed` | 0%（0 / 143） |
| `checks` | 100% |
| `http_req_duration` p(99) | **50.6 ms**（门禁 `<500` 通过） |
| 说明 | 须低于 `API_ATTEMPT_LIMIT`（300/min）；高 VU 无 THINK 会 429 污染失败率 |

### AR-011 / IdP·多活

- `rules.yml` + ArchUnit 文案：可选 `__Host-` 头解析投递；禁 ThreadLocal / SecurityContext / `jakarta.servlet.http.Cookie`。
- 正式 IdP / 多活：**仅** `docs/adr/001-idp-and-multi-active.md` 边界，本仓不装完整栈。

## 产品示例加厚（同日再后续）

规格：[product.md](product.md)。

| 切片 | 落地 |
|---|---|
| A 真假路径 | README 产品叙事；默认 HTTP；Demo 显式 `dev:demo` / `test:e2e:demo` |
| B 预留 TTL | Flyway `V3`；`expire-reservations` → 同取消台账；jtest `ReservationExpiry` |
| C AuthN/JWT | `AccessTokenAuthenticator` + 可选 HS256；jtest `JwtAuthn` |
| D 前端 | `expiresAt` 类型；Demo 种子带过期；侧栏标明 HTTP/DEMO |
| L1 部分确认 | ADR 002；Flyway `V4` `effect_quantity`；`confirmUnits` / `reduceReservedQuantity`；jtest `PartialConfirm`；确认弹窗可缩量 |
| L2 预留改期 | ADR 003；Flyway `V5` `order_reschedule_operations`；`rescheduleExpiresAt` / `updateExpiresAt`；jtest `ReservationReschedule`；改期弹窗 |
| L3 证据补强 | `test:e2e:demo` 为 CI 显式 Demo；`test:e2e` 打印警告；Demo/同源 E2E 增部分确认+改期；`scripts/expire-reservations.sh` |
| L4 JWKS 薄片 | `mode=jwks` + 静态 `jwks-json` RS256；可关密码登录；jtest `JwksAuthn`（kid 轮换） |

后端 `./mvnw -B verify`：**BUILD SUCCESS**（ArchUnit 23 + jtest **97** checks，含 JwksAuthn×3）。  
前端 `npm test`：**29 passed**；`npm run test:e2e:demo`：**10 passed**（含 partial/reschedule；仅 `console.spec.ts`）。  
同源 E2E / expire 脚本：需 Compose 手工跑（CI 不跑）。

## 学习闭环结论（同日收口）

| 层级 | 状态 |
|---|---|
| 确定性库存 / 审计链 / RBAC | 契约 + jtest + IT + contend 已证 |
| 五缺口（锁重试 / 限流·追踪·告警 / 检查点 / XSS 会话） | 代码 + verify 已证 |
| P99 读路径 | 本机 **9.9 ms**（≠ 跨地域容量） |
| P99 写路径 | 本机 **50.6 ms**（VU=1，受限于流） |
| Cookie HTTPS + CSRF | E2E **2 passed** |
| Redis Sentinel HA | 演练 **addr 切换 + 写恢复**；演练后须 recreate |
| 观测告警 | METRICS_READ 真 scrape + `TradeInstanceDown` 演练 OK |
| 密钥外接 | env-only；无应用内 Vault SDK |
| IdP / 多活 | ADR 边界 only |

**刻意不宣称 / 不继续扩的边界：**

- Redis 仍是写路径依赖（fail-closed，非去 Redis）。
- Cookie 模式下 XSS 仍可偷 XSRF；CSP 收敛风险，不能当支付级认证。
- Demo / 本机 P99 / 单机 HA / 单机告警演练 ≠ 多活、合规或容量证明。

基线 Compose：`mysql` + `redis` + `app` + `caddy`；obs profile 可叠加；HA 按需再叠 `docker-compose.ha.yml`。

## 清理说明

可再生内容（`target/`、`frontend/node_modules/`、`test-results/`、Playwright 报告）可按需删除；源码、锁文件、`scripts/` 与本文保留。Compose 数据卷：`docker compose down` 保留，`down -v` 清空。
