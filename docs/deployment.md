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

镜像构建默认走区域镜像（`NPM_REGISTRY` + `ops/maven/settings.xml` 的 Central 镜像）。在 CI 或任何能直连
public registry 的机器上构建时，用构建参数切回官方源：

```bash
NPM_REGISTRY=https://registry.npmjs.org \
MAVEN_SETTINGS=ops/maven/settings-central.xml \
docker compose build
```

`compose-stack` CI 作业就是这么构建并随后跑 smoke / contend / maintenance / 两套 E2E 的。

应用环境：`TRADE_COOKIE_SECURE=true`。前端在 `https:` 下自动 `delivery=cookie`，在 `http:` 下保持 Bearer。

### 反代信任（默认关闭）

`server.forward-headers-strategy` 默认 **`none`**：`X-Forwarded-For` 由客户端自带，若默认信任，任何人都能伪造来源地址，
从而为每个用户名拿到新的登录限流配额（限流指纹就是请求方地址）。只有当**所有**入站请求都经过可信代理时才显式打开：

```bash
# 仅在 app 端口不对外暴露、且代理覆盖（而不是追加）X-Forwarded-For 时使用
TRADE_FORWARD_HEADERS_STRATEGY=framework
```

配套要求：代理必须 `header_up X-Forwarded-For {remote_host}` 覆盖写（`ops/caddy/Caddyfile` 已如此配置）。
Cookie 的 `Secure`/同源判定直接读 `X-Forwarded-Proto`，与上面的开关无关。

### 数据库迁移与 TLS

- `FLYWAY_BASELINE_ON_MIGRATE` 默认 **false**：库里有表却没有 `flyway_schema_history` 时启动直接失败，
  不会把现状静默标成 V1 并跳过全部迁移；legacy 库显式设 `true` 采纳一次（先备份，见 [migrations.md](migrations.md)）。
- JDBC 串中的 `useSSL=false&allowPublicKeyRetrieval=true` 只是本地/lab 默认值；托管 MySQL 用
  `MYSQL_TLS_PARAMS='sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/run/secrets/mysql-truststore'`
  覆盖，生产不允许明文回落。

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
