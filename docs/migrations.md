# Schema 演进与备份/回滚剧本

## 现状

- **真相源**：`src/main/resources/db/migration/V*.sql`（Flyway）。
- `spring.sql.init.mode=never`；应用启动时自动 `flyway migrate`。
- 不存在第二份 schema：`HighConcurrencyIT` 直接跑 Flyway（`baselineOnMigrate(false)`），因此 V1..Vn 都被容器测试真实执行；旧的 `schema.sql` 镜像已删除，别再引入。
- `MigrationIT`（CI `mysql-redis-concurrency` 作业）把"迁移应该产出什么"钉死：V1..V6 表面（`orders.expires_at`、`order_operations.effect_quantity`、`audit_events_archive`、`order_reschedule_operations`、`idx_audit_aggregate_occurred`、`idx_orders_expire_reserved`）、重复 migrate 为 0、以及**非空无历史库必须失败**而不是被静默 baseline。
- `baseline-on-migrate=false`（默认）：库中已有表但没有 `flyway_schema_history` 时启动**失败**，而不是把现状静默标成 V1 并跳过全部迁移。legacy 库改用 `FLYWAY_BASELINE_ON_MIGRATE=true` 显式采纳一次，并在采纳前做下面的备份。

## 新增迁移

1. 新增 `V{n}__short_name.sql`（只前进，不改已发布文件）。
2. 本地：`docker compose down -v && docker compose up --build -d` 或对已有库启动 app 观察 migrate。
3. 更新 `SchemaContractTest` 如有新的不变量。
4. IT/压测覆盖：`HighConcurrencyIT` 与 `-Pintegration` 使用真实迁移，无需同步任何镜像文件。

## 审计归档与验链（archive-audit）

归档只搬运整条聚合链，读取同时覆盖 `audit_events` 与 `audit_events_archive`，归档后新的追加会接着归档 tip 继续，`GET /api/v1/audit/verification` 仍校验包含已归档前缀的整条链（`checkedEvents` 为链长，而不是 0）。

```bash
# 1) 归档足够安静（默认 30 天）的整条链，单批最多 batch-size 条聚合
docker compose run --rm -e TRADE_MAINTENANCE_COMMAND=archive-audit app
# 2) 归档后必须仍然可验：对任一已归档聚合执行 verify，期望 valid=true 且 checkedEvents>0
# 3) 归档表只做追加保留；若需导出冷存储，先 mysqldump audit_events_archive 再考虑删除
```

`MAINTENANCE_OK command=archive-audit affected=N` 中的 N 是**整链搬运成功**的聚合数。删除归档行属于可检测的篡改：`verify` 会失败，而不是把缺失的历史当成"无事件即通过"。

## 维护批次的部分失败

`expire-reservations` / `checkpoint-audit` 逐项隔离失败，输出形如
`MAINTENANCE_OK command=expire-reservations affected=9 attempted=10 failed=1`，
失败原因以 WARN 记录（仅异常类型与消息，不含 payload）。出现 `failed>0` 表示该批次有订单/链路在查询与写入之间发生了竞争，
下一轮会自动重试；进程仍以 0 退出，以免 cron 任务被单个热点订单永久卡住。

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
