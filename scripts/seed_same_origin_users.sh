#!/usr/bin/env sh
set -eu
# Seed operator/viewer/auditor for same-origin Playwright against Compose Boot.
# Password for all three: e2e-role-secret (bcrypt cost 12).
MYSQL_CONTAINER=${MYSQL_CONTAINER:-deterministic-trade-mysql-1}
# $2b$ is accepted by Spring Security BCryptPasswordEncoder.
HASH='$2b$12$yyKaXd9wvfYQXw/6ugEP8.juyTw7jVaTfqHrJ69WRkxegrIpm8D7y'

docker exec -i "$MYSQL_CONTAINER" mysql -utrade -ptrade trade <<SQL
INSERT INTO users (id, username, password_hash, enabled, roles) VALUES
  (UUID(), 'operator', '${HASH}', TRUE, 'OPERATOR'),
  (UUID(), 'viewer', '${HASH}', TRUE, 'VIEWER'),
  (UUID(), 'auditor', '${HASH}', TRUE, 'AUDITOR')
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), enabled = TRUE, roles = VALUES(roles);
SQL
printf 'seeded operator/viewer/auditor\n'
