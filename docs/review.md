# 设计审查记录

## 已处理的高风险问题

- **Redis/MySQL 双库存会引入跨存储原子性缺口。** 已改为 MySQL 单一事实源，Redis 不保存库存。
- **事务内吞掉唯一键异常可能提交裸库存变化。** 当前异常先离开事务模板，回滚后才重放胜方。
- **只依赖 TTL 锁不能证明不超卖。** 原子条件更新、唯一键和 CHECK 独立兜底，并有刻意绕锁的竞态 IT。
- **终态迁移可能留下回执或重复释放。** 回执先占位，条件状态更新与台账更新同事务，失败整体回滚。
- **过期锁句柄可能误删新 owner。** Lua 比较随机 owner token，close 幂等。
- **同键不同负载若直接重放会返回错误结果。** 三类命令都绑定各自负载/目标并返回冲突。
- **无认证管理接口。** 已移除旧 PUT 模型；当前创建、调整、下单、迁移都需要 Bearer 和 Application RBAC。
- **Token 明文与撤销延迟。** 数据库只存 SHA-256 哈希；每次认证检查有效期、用户启用状态，注销立即删除。
- **登录成功但 Redis 复位失败可能留下孤儿 Token。** reset 已移到 Token 写事务之前。
- **全局审计链会形成写热点。** 已按订单/SKU 分链，审计与业务同事务，支持完整篡改检测。
- **隐式代理和组件扫描增加审查成本。** 显式事务、函数式路由、集中装配，ArchUnit 禁止回退。

## 本轮深化的验证

- jtest 覆盖值对象极值、完整订单状态矩阵、Role×Permission 与角色×实际操作、复合工作流、同键跨命名空间、故障注入回滚、唯一键 winner/loser、同时间戳游标、1000 事件验链、固定指标标签及多组高压竞争。
- ArchUnit 扩展到包循环、线程/系统时钟、服务字段不可变、适配器隔离、无 ThreadLocal / SecurityContext / `jakarta.servlet.http.Cookie`（AR-011；可选 `__Host-` 为头解析投递）、显式路由、审计只追加端口和凭据防 `toString` 泄露。
- SQL/Lua/Schema 契约覆盖 owner-checked release、登录窗口、组合索引、Dashboard 扫描次数和无 OFFSET keyset。
- Testcontainers 增加真实 HTTP + MySQL + Redis 的登录/注销、RBAC、限流、完整业务闭环、审计篡改、失败矩阵、分页和 HTTP 并发。
- Vue 增加 HTTP 错误/重试契约与 Playwright 登录、角色、完整业务、失败无副作用和移动布局。

## 本轮已补的运维/体验缺口

- **Flyway**：`db/migration/V1`+`V2`（含 `audit_events_archive`）；`docs/migrations.md` 备份/前滚/回滚剧本。
- **性能协议**：Hikari/Redis/锁超时可配置；`docs/performance.md` + `scripts/k6-p99.js`（陈述模板，非认证级）。
- **显式批处理**：`trade.maintenance.command=purge-tokens|archive-audit`（`MaintenanceRunner`，禁止 `@Scheduled`）；脚本 `purge-expired-tokens.sh` / `archive-audit.sh`。归档按**整链安静聚合**搬迁，避免切断哈希链。
- **前端错误码中文映射**：`domain/errors.ts`，同源与 Demo 文案对齐。

## 本轮可用性 / 观测 / 威胁模型补强

- **Redis fail-closed 不变**：`RetryingLockManager` 仅对 I/O 失败做有界重试（默认 3×50ms），争用（空 Optional）不重试；读路径仍不依赖 Redis。
- **通用 API 限流**：认证后按 actor 固定窗口（`trade.security.api-attempt-limit` / `api-window`），Redis 故障同样 fail-closed → `LOCK_UNAVAILABLE`。
- **关联追踪**：`CorrelationIdFilter` 传播 `X-Request-Id` 并写入 MDC；完整 OTel 导出仍是可选外部装配，非应用内隐式代理。
- **告警样例**：`ops/prometheus-alerts.yml`（由 Prometheus 加载，非 `@Scheduled`）。
- **审计检查点**：`CHAIN_CHECKPOINT` + `verify?trustedCheckpointHash=`；运维 `checkpoint-audit`（`scripts/checkpoint-audit.sh`）。
- **浏览器会话**：默认 Bearer + `sessionStorage`；HTTPS 可选 Cookie（`__Host-TradeSession`）；`VITE_SESSION_PERSIST=memory` 仅影响 Bearer；CSP `object-src 'none'`。

## 部署级与浏览器根治（可选 Cookie）

- **Compose TLS**：Caddy `:8443`；`TRADE_COOKIE_SECURE=true`；见 `docs/deployment.md`。
- **Redis HA**：`docker-compose.ha.yml` + `scripts/ha-failover-drill.sh`。
- **观测**：`--profile obs` + `METRICS_READ` scrape；`scripts/obs-alert-drill.sh`（真指标 + `TradeInstanceDown`）。
- **可选 Cookie**：`delivery=cookie` + `__Host-TradeSession` + Double-submit；HTTPS E2E：`npm run test:e2e:https-cookie`。
- **密钥外接**：应用只读环境变量；`ops/secrets/.env.example` + `docs/secrets.md`（Vault/SOPS/CI 注入，无应用内 SDK）。
- **写路径延迟协议**：`scripts/k6-p99-write.js` + `docs/performance.md` P-WRITE（≠ contend）。
- **IdP / 多活**：仅边界 ADR `docs/adr/001-idp-and-multi-active.md`（本仓不装完整栈）。

## 明确保留的生产化事项

- **[中] Redis 仍是写路径依赖。** Sentinel + 演练脚本提高信心，不改变 fail-closed。
- **[低] XSS。** Cookie 模式会话密钥出 JS；XSRF cookie 仍可读；须无 XSS。
- **[说明] Demo / 本机 P99 / 演练通过 ≠ 支付级或跨地域容量证明。**
- **[运维] HA 演练后拓扑。** `kill redis` 后易出现双主；脚本尾部给出 recreate / 回基线命令，勿把演练现场当稳态。
- **[范围] 正式 IdP / 多活多写。** 见 ADR 001；非本样板实现目标。

## 产品示例加厚

- 预留 TTL + 显式 `expire-reservations`（禁止第二套取消路径）。
- `AccessTokenAuthenticator`：opaque 默认，可选 JWT → Actor（HS256 或静态 JWKS/RS256；可关密码登录）。
- README/`docs/product.md` 区分真系统与 Demo。

## 闭环结论

部署级、观测、读写 P99、产品切片（TTL/JWT/叙事）均已对齐（见 `docs/verification.md`）。正确性护栏仍在。仍不能宣传支付级认证或跨地域多活。
