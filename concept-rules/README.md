# Concept Rules

先定义必须成立的概念、不变量与失败语义，再选择框架；不是按 Controller/Service/Repository 包名堆砌代码。

## 核心概念

| Concept | 含义 | 代码位置 |
|---|---|---|
| Pure Domain | 值校验、库存/订单状态、权限、审计哈希与指纹只依赖 JDK | `domain/` |
| Use Case | 下单、库存、迁移、认证、查询与验链的显式编排 | `application/` |
| Port | 仓储、回执、锁、事务、ID、时钟相关协作的最小接口 | `application/port/` |
| Adapter | JDBC、Redis Lua、HTTP、安全与指标协议实现 | `adapter/` |
| Composition Root | 两个配置类集中构造对象图，无字段注入 | `config/` |
| Explicit Effect | 锁、事务、路由、异常和外部 I/O 顺序可审查 | 各 Application Service |
| Command Receipt | 每个命令命名空间把请求键绑定到唯一负载和结果 | `*_operations` / `orders` |
| Aggregate Audit | 每个订单/SKU 独立 sequence 与哈希链 | `domain/audit`、`audit_events` |
| Bounded Query | Keyset、组合索引、固定指标标签与有界时间线 | 查询仓储与 observability |
| Gateway UI | Vue 通过同一类型化接口选择真实 HTTP 或显式 Demo | `frontend/src/api` |

## 审查顺序

1. 阅读 `rules.yml` 的 AR/INV 与 `review-checklist.md`，先确认改动会破坏哪个概念。
2. 审 Application：权限、锁、事务、外部 I/O、回滚和重放顺序必须可见。
3. 审 SQL/Lua：条件更新不能拆成先查再改；唯一键和 CHECK 仍是最终防线；释放锁必须比较 owner。
4. 审安全/审计：只保存 Token 哈希，审计与业务共事务，失败不得留下半效果。
5. 审 Web/配置/前端：不得跨层调用、隐式装配、Cookie 身份回退或把 Demo 当真实后端。
6. 最后执行对应测试层，并如实记录“已运行”与“仅编译/需 Docker”。

规则必须由测试执行；仅写在本目录而没有护栏约束的规则，不应标记为“已强制执行”。
