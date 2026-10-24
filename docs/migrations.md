# Schema 演进与备份/回滚剧本

## 现状

- **真相源**：`src/main/resources/db/migration/V*.sql`（Flyway）。
- `spring.sql.init.mode=never`；应用启动时自动 `flyway migrate`。
- `schema.sql` 仅为 HighConcurrencyIT 的兼容镜像，**不要**在此单独演进。
- `baseline-on-migrate=true`：若库中已有表但无 `flyway_schema_history`（旧 Compose 卷），Flyway 会把当前库标为 baseline V1，再应用 V2+。

## 新增迁移

1. 新增 `V{n}__short_name.sql`（只前进，不改已发布文件）。
2. 本地：`docker compose down -v && docker compose up --build -d` 或对已有库启动 app 观察 migrate。
3. 更新 `SchemaContractTest` 如有新的不变量。
4. 同步 `schema.sql` 镜像（IT 用），保持与最新 V* 语义一致。

## 备份（执行迁移前）

```bash
# Compose 默认主机端口 3307
TS=$(date -u +%Y%m%dT%H%M%SZ)
docker exec deterministic-trade-mysql-1 \
  mysqldump -uroot -proot-demo-only --single-transaction --routines --triggers trade \
  > "backup-trade-${TS}.sql"
ls -lh "backup-trade-${TS}.sql"
```

生产替换账号/主机，并校验校验和与恢复演练。

## 前滚

```bash
# 应用启动即 migrate；或显式：
docker compose run --rm -e BOOTSTRAP_ADMIN_PASSWORD=local-admin-only app \
  java -jar /app/app.jar
# 查看历史：
docker exec -i deterministic-trade-mysql-1 mysql -utrade -ptrade trade \
  -e "SELECT installed_rank,version,description,success FROM flyway_schema_history ORDER BY installed_rank"
```

## 回滚剧本

Flyway Community **不自动 undo**。约定：

1. **停写**：扩缩容前摘流量 / `docker compose stop app`。
2. **确认备份**：上节 dump 可 restore。
3. **选择**：
   - **数据可丢的本地卷**：`docker compose down -v && docker compose up --build -d`
   - **需保留数据**：恢复 dump，再部署**不含坏迁移**的应用版本；或手写反向 DDL（必须评审）后 `flyway repair`。
4. **恢复**：

```bash
docker exec -i deterministic-trade-mysql-1 mysql -uroot -proot-demo-only trade < backup-trade-YYYYMMDD.sql
docker compose up -d app
curl -fsS http://127.0.0.1:8080/actuator/health
```

5. **记录**：迁移版本、备份文件名、操作人、时间写入变更单。

## 禁止事项

- 修改已发布的 `V1`/`V2` 内容（checksum 会失败）。
- 在生产对存量库重新打开 `spring.sql.init.mode=always`。
- 无备份执行破坏性 DDL。
