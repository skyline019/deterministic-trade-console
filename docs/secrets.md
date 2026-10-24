# 密钥管理体系外接

应用**不内嵌** Vault/SOPS/云 KMS SDK。秘密只经环境变量进入进程；外接系统负责注入与轮换。

## 边界

| 层 | 职责 |
|---|---|
| 密钥系统（Vault / SOPS / CI Secrets / 云 SM） | 存储、审计、轮换、下发 |
| Compose / K8s | `env_file` / Secret 挂载为环境变量 |
| deterministic-trade | 只读 `BOOTSTRAP_*`、DB/Redis 密码等；DB 存 Token **哈希** |

非目标：应用内拉取 Vault API、热重载密钥、多租户密钥命名空间。

## 本地 Compose

```bash
cp ops/secrets/.env.example ops/secrets/.env
# 编辑 .env 后：
docker compose up --build -d
```

`ops/secrets/.env` 已在 `.gitignore` 模式中覆盖（仓库根 `.env` + 建议不提交 `ops/secrets/.env`）。  
未提供文件时仍可用 shell 导出的 `BOOTSTRAP_ADMIN_PASSWORD`（演示默认 `local-admin-only`）。

## 生产接线示例（任选其一）

1. **CI**：流水线从密钥库取密 → `export` → `docker compose` / Helm `--set`。
2. **Vault Agent**：sidecar 写 `ops/secrets/.env` 或 K8s Secret；app 无 Vault 客户端。
3. **SOPS**：解密 `secrets.enc.yaml` → env；提交密文、不提交明文。

## 轮换剧本

1. 在密钥系统生成新值。
2. 更新注入源 → 滚动重启 app（及 MySQL/Redis 若改库密）。
3. 可选：`trade.maintenance.command=purge-tokens` 清旧会话。
4. 验证：Bearer smoke +（HTTPS）Cookie 登录；HA 栈则再跑 `scripts/ha-failover-drill.sh`。

Bootstrap 密码仅用于**空库首次**创建 admin；之后改用户密码应走运维 SQL/专用命令，勿依赖重复 bootstrap。
