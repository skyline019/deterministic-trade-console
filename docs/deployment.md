# 部署级装配（Compose TLS / Redis HA / 观测 / 密钥）

正确性模型不变：MySQL 事实源、Redis fail-closed、幂等与审计哈希链。本节只描述**投递与运维**旋钮。

## 1. 默认 Compose（TLS + 单机 Redis）

```bash
export BOOTSTRAP_ADMIN_PASSWORD='local-admin-only'
docker compose up --build -d
```

| 入口 | 用途 |
|---|---|
| `https://localhost:8443` | Caddy 终止 TLS → app；浏览器 Cookie 模式（`__Host-TradeSession`） |
| `http://127.0.0.1:8080` | 直连 app；脚本/IT 用 Bearer（Cookie 投递在此会被拒，因无可信 TLS） |

首次访问 8443 时信任 Caddy 内部 CA（浏览器提示或 `curl -k`）。

应用环境：`TRADE_COOKIE_SECURE=true`。前端在 `https:` 下自动 `delivery=cookie`，在 `http:` 下保持 Bearer。

## 2. Redis Sentinel（HA overlay）

```bash
docker compose -f docker-compose.yml -f docker-compose.ha.yml up --build -d
# 健康后：
./scripts/ha-failover-drill.sh
```

- Overlay 保留服务名 `redis` 作为主（满足 base `depends_on`），并加 `redis-replica` / `redis-sentinel`。
- App：`SPRING_DATA_REDIS_SENTINEL_MASTER=mymaster`，`NODES=redis-sentinel:26379`。
- 演练：`kill redis` 容器 → Sentinel 将 replica 提升为新 master（地址必须变化）→ 写路径在短暂 503 窗口后恢复；全程不 fail-open。
- 锁/限流在 master 不可达时仍 **fail-closed**（有界重试后 `LOCK_UNAVAILABLE`）。
- 演练后：HA 栈需 `--force-recreate redis redis-replica redis-sentinel app`；若只要日常基线，停掉 replica/sentinel 后 `docker compose up -d --force-recreate redis app`（脚本成功时会打印 hint）。

## 2b. Cookie HTTPS E2E

```bash
docker compose up --build -d   # 含 Caddy :8443
cd frontend && npm run test:e2e:https-cookie
```

断言：登录 JSON 无 accessToken；`__Host-TradeSession` HttpOnly；缺 `X-XSRF-TOKEN` → `CSRF_REJECTED`。

## 3. 观测 profile（METRICS_READ 真 scrape）

```bash
# 推荐：一条龙签发 token、启动 obs、验证指标、打响 TradeInstanceDown
./scripts/obs-alert-drill.sh
# 或手工：
# 1) 登录拿 Bearer（admin/auditor 含 METRICS_READ）写入 ops/secrets/metrics_bearer.token
# 2) docker compose --profile obs up -d
# 3) POST http://127.0.0.1:9090/-/reload
```

- Prometheus `:9090` scrape **`/actuator/prometheus`**（`bearer_token_file`），规则见 `ops/prometheus/alerts.yml`。
- Alertmanager `:9093`（演示 webhook 可无；演练以 Prometheus `api/v1/alerts` firing 为准）。
- 无 token 或 token 无效时 `up{job="deterministic-trade"}==0`，可触发 `TradeInstanceDown`。

## 4. 密钥与 bootstrap 轮换

详见 [secrets.md](secrets.md)。摘要：

| 秘密 | 轮换 |
|---|---|
| `BOOTSTRAP_ADMIN_PASSWORD` | 仅首次创建 admin；经 `ops/secrets/.env` 或 CI/Vault 注入 |
| Bearer / Cookie 会话 | SHA-256；注销删行；`purge-tokens` 清过期 |
| MySQL/Redis 密码 | 演示默认仅本地；生产外接密钥系统后滚动 app |

## 5. Cookie 模式正确性要点

- 登录 `delivery=cookie` → Set-Cookie `__Host-TradeSession`（HttpOnly）+ `TradeXsrf`（JS 可读）；JSON **不含** accessToken。
- 非安全方法必须 `X-XSRF-TOKEN` 与 `TradeXsrf` 常量时间相等，否则 `CSRF_REJECTED`。
- Bearer 头优先；带 Bearer 时不走 CSRF。
- 默认交付仍是 Bearer；Cookie 为显式可选，不削弱库存确定性。
