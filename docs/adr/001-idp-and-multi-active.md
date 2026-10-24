# ADR 001：正式 IdP 与多活（边界，未实现）

状态：Accepted（边界约定）  
日期：2026-10-24

## 背景

学习样板已具备本地 Bearer/Cookie、单库 MySQL 事实源、可选单 Sentinel。评估要求区分「可教的下一步」与「本仓库要装的完整栈」。

## 决策

**本仓库不引入完整 IdP（Keycloak/OIDC AS）或多活多写。** 仅固定若外接时必须遵守的边界，避免将来把身份或复制策略偷偷做进 Domain。

## IdP 外接边界

| 保留在本应用 | 外接到 IdP |
|---|---|
| `Actor` + `Permission` / `Role` 授权检查 | 认证、密码、MFA、联邦登录 |
| Token **哈希**落库与注销（或改为仅验证 JWT/`sub`） | 签发、刷新、轮换、JWKS |
| 可选 `__Host-` Cookie 投递与 CSRF | Session 产品策略、设备绑定 |
| Application RBAC | IdP 角色映射表（claim → `Role`） |

禁止：Domain 依赖 OIDC SDK；在 Filter 里用 SecurityContext ThreadLocal 回潮；把 IdP 当库存正确性依赖。

最小外接形态（未来）：`AuthN` 端口验证 Bearer/JWT → 映射 `Actor`；现有 `AuthService` 密码登录可关。

## 多活边界

| 承诺不变 | 多活不承诺（除非新 ADR） |
|---|---|
| 单 MySQL 事实源上的条件更新防超卖 | 跨区域同步写、双主 MySQL |
| Redis fail-closed 互斥 | 跨 DC 锁、共享 Redis 脑裂自动修好业务 |
| 客户端幂等键重放 | 跨库 exactly-once |

可选演进阶梯（需单独立项）：

1. **读副本**：MySQL 只读副本仅服务查询；写仍单主。  
2. **同城主备**：RPO/RTO 由运维切换；应用无感知或只读降级。  
3. **多活多写**：需全局幂等与冲突协议——**超出当前一致性模型**，不得伪装成「再叠一个 compose」。

Sentinel 单 quorum 演练只证明本机 fail-closed 恢复，不满足多活定义。

## 后果

- 文档与评估可指向本 ADR，避免把「未装 Keycloak」当成缺口。  
- 若立项 IdP/多活，先改本 ADR 状态并开新验证协议，再写代码。
