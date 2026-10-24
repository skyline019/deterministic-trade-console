# 变更审查清单

## 核心与依赖方向

- [ ] Domain/Application 未新增 Spring、Jakarta、Jackson、JDBC、Redis、Micrometer 或并发执行器依赖。
- [ ] Web 没有绕过 Application 直接调用 Repository/Redis；适配器之间没有隐藏串联。
- [ ] 新对象使用构造器注入，并在 `WiringConfiguration` 或 `SecurityConfiguration` 显式装配。
- [ ] 未用 `@Transactional`、`@Async`、`@Scheduled`、`@Cacheable`、字段注入或注解路由隐藏副作用。
- [ ] 新规则已加入 `rules.yml`，并有对应 ArchUnit/jtest/契约测试，而非只有文字。

## 并发、事务与性能

- [ ] 库存修改仍是原子条件更新，没有改成“查询后扣减”或引入第二份缓存库存。
- [ ] 库存、订单/回执和审计仍在同一个本地事务内提交。
- [ ] 异常没有被事务 lambda 吞掉；唯一键冲突在原事务回滚后才读取重放。
- [ ] 新锁有 TTL，释放比较 owner token；锁粒度不是全局或不必要的 SKU 粗锁。
- [ ] 同键同负载可重放，不同负载冲突；不同命令命名空间不会错误互相占用回执。
- [ ] 终态只有允许迁移；取消释放、确认核销，预占与 available 始终守恒。
- [ ] 查询保持有界：订单使用 keyset 和 `limit+1`，没有 OFFSET 或无界“优化”。
- [ ] 新 SQL 有匹配索引；Dashboard 未退化成 N+1 或多次独立聚合扫描。
- [ ] 指标标签枚举有界，没有 requestId/orderId/userId/异常消息标签。
- [ ] 没有用隐式缓存、异步写入或省略审计换取吞吐。

## 安全与审计

- [ ] 每个新应用操作都检查明确 Permission；前端隐藏按钮没有被当作权限控制。
- [ ] 原始 Token/密码/密码哈希不会进入日志、响应、`toString` 或指标。
- [ ] Token 认证仍检查有效期、用户启用状态；注销立即生效。
- [ ] 登录限流与复位失败路径明确，不能留下未返回但有效的 Token。
- [ ] 审计按聚合分链，事件包含 actor/request/payload/previous hash，并与业务事务共命运。
- [ ] 完整验链没有被最近 N 条查询替代；篡改、断链、乱序和跨聚合输入都失败关闭。

## 测试与运行

- [ ] 新 Concept 在 `ConceptEntrypoint` 显式注册；jtest 未引入 JUnit 注解。
- [ ] 正确路径、失败/回滚、边界、复合/嵌套、权限、联动与高压场景都有断言。
- [ ] 高压测试断言精确结果、库存/回执/审计数量和锁配对，不用不稳定的耗时阈值。
- [ ] `./mvnw verify` 通过，ArchUnit 与 jtest 均有可观察结果。
- [ ] SQL/Lua/HTTP 改动已在有 Docker 的环境执行 `./mvnw -Pintegration verify`；若只编译，明确标记未运行。
- [ ] 前端 `npm test`、`npm run build`、`npm run test:e2e` 通过；Vitest 不错误收集 Playwright 文件。
- [ ] 配置满足 `lock-ttl > transaction-timeout`，BCrypt/Token/登录窗口参数边缘有测试。
- [ ] 文档没有宣称跨进程 exactly-once、跨存储绝对一致、未实测 QPS/P99 或未执行容器 E2E。
