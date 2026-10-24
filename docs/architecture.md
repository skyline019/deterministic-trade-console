# 架构说明

## 依赖方向

```text
Vue 3 / HTTP Client
        │ same-origin JSON + Bearer
        ▼
Functional Routes / Handlers / Bearer Filter
        ▼
Application Use Cases + Query Services + Ports ─────► Pure Domain
        ▼                                            （仅 JDK）
JDBC / Redis Lua / Transaction / Security Adapters
        ▼
MySQL（事实源） + Redis（互斥与登录限流）
```

`WiringConfiguration` 与 `SecurityConfiguration` 是组合根。Domain 不知道 Application；Application 不知道 Spring、JDBC、Redis 或 Web；Adapter 实现 Port，而不是让核心依赖框架。

## 显式效果

| 常见隐式做法 | 本项目 | 审查收益 |
|---|---|---|
| `@Service/@Repository/@RestController` 扫描 | 普通 Java 类 + 显式 `@Bean` | 对象图集中可追踪 |
| 字段 `@Autowired` | 构造器注入 | 可离线实例化与故障注入 |
| `@Transactional` 代理 | `TransactionRunner` / `TransactionTemplate` | 提交、回滚、只读边界可见 |
| `@ControllerAdvice` | 显式路由错误过滤器 | HTTP 映射顺序固定 |
| 注解参数校验 | Domain 值对象 + 应用校验 | HTTP 与核心测试使用同一规则 |
| `@Async/@Scheduled/@Cacheable` | 禁止隐式业务副作用 | 不用后台任务掩盖账本变化 |

保留的注解用于 Boot 入口和配置装配。jtest 不使用 JUnit 注解或引擎，套件在 `ConceptEntrypoint` 显式注册。

## 命令边界

### 下单

1. `Actor` 在 Application 再次检查 `ORDER_WRITE`。
2. 以 `requestId` 获取 Redis owner-token TTL 锁，粒度不是 SKU。
3. 开启 `READ_COMMITTED` 写事务，读取订单回执。
4. 原子更新 `available/reserved`，插入订单（含 `expiresAt`）和两条聚合审计。
5. 唯一键败方完整回滚后，在只读事务中重放胜方。
6. `finally` 中 owner-checked 解锁；释放失败不覆盖已提交结果。

### 库存创建/调整与订单终态

库存回执和订单迁移回执有独立唯一键命名空间。迁移先把请求身份写入 `order_operations`，再执行条件状态更新和台账更新；同订单不同请求键的并发败方会回滚自己的回执，不留下“无订单效果的回执”。取消增加 `available`、减少 `reserved`；确认只减少 `reserved`。

过期释放：运维 one-shot `expire-reservations` 对 `expires_at <= now` 的 `RESERVED` 调用同一 `OrderTransitionService`（幂等键 `expire-{orderId}`），禁止第二套取消路径。

### 认证

凭据校验使用 BCrypt；未知用户也执行 dummy-hash 比较。登录成功顺序为：验证 → Redis 限流复位 → Token 数据库事务 → 返回原始 Token。复位失败不会留下“数据库有效但客户端未收到”的 Token。BCrypt 和 Redis I/O 不占用数据库写事务。

`AccessTokenAuthenticator` 端口：默认 opaque DB Token；可选 HS256 JWT（`trade.security.jwt.*`）映射为 `Actor`。Filter 只做 AuthN；权限仍在 Application。无 SecurityContext ThreadLocal；Cookie 为可选投递（`__Host-TradeSession` + CSRF）。

## 审计与查询性能

- 审计按 `(aggregate_type, aggregate_id)` 分链，不使用全局 sequence 或全局锁。
- 同一 SKU 的审计由其库存行更新串行化；同一订单的状态条件更新提供相同保护。
- 订单查询使用 `(created_at, id)` 降序 keyset，取 `limit + 1` 判断下一页，无 `OFFSET`。
- 有游标、状态、SKU、状态+SKU 四类索引；组合过滤不依赖内存分页。
- Dashboard 对 inventory 与 orders 各做一次聚合扫描，再 `CROSS JOIN` 两个单行结果。
- 订单指标的 outcome 标签集合固定，不把 requestId、orderId 或异常消息放入标签。
- `findChain` 为完整验链必须读取整个聚合；`findRecent` 仅用于有界时间线，不能冒充完整验证。

## 前端边界

Vue 通过同一个 `TradeGateway` 接口使用两种适配器：

- `httpGateway`：相对 URL、Bearer 或 HTTPS Cookie+CSRF、Idempotency-Key、无缓存、10 秒超时、401 会话失效事件。
- `demoGateway`：显式 Demo 模式，仅在页面内模拟命令与权限，不作为真实安全/性能证据。

Session：默认 Bearer + `sessionStorage`；HTTPS 下可选 Cookie（`__Host-TradeSession` HttpOnly + Double-submit CSRF）。`VITE_SESSION_PERSIST=memory` 仅影响 Bearer 持久化。前端按角色隐藏控件只改善体验，真实权限由后端决定。Docker 将 `frontend/dist` 复制到 Boot `static`；Compose 另有 Caddy `:8443` TLS。详见 `docs/deployment.md`。

写路径依赖 Redis 锁与限流（fail-closed + 有界重试；可选 Sentinel）；读路径与验链只走 MySQL。请求关联靠显式 `X-Request-Id`。

## 验证分层

1. `src/jtest/java`：领域矩阵、失败回滚、复合调用、认证/审计、高压确定性。
2. `src/test/java`：13 条 ArchUnit 护栏及 Lua、Schema、SQL 性能契约。
3. `src/it/java`：真实 MySQL/Redis 的应用竞态与随机端口 HTTP E2E。
4. `frontend/src/**/*.test.ts`：Gateway、失败路径和格式化单元测试。
5. `frontend/e2e`：Playwright 浏览器业务闭环、权限与移动布局。
6. `scripts/smoke.sh` / `contend.sh`：运行中服务的黑盒验证。

只有实际执行的层才能标记通过；容器 IT 编译成功不等于 Docker E2E 已运行。
