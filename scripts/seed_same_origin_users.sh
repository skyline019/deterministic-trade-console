#!/usr/bin/env sh
set -eu
# Seed operator/viewer/auditor for same-origin Playwright against Compose Boot.
# Password for all three: e2e-role-secret (bcrypt cost 12).
# $2b$ is accepted by Spring Security BCryptPasswordEncoder.
HASH='$2b$12$yyKaXd9wvfYQXw/6ugEP8.juyTw7jVaTfqHrJ69WRkxegrIpm8D7y'

SEED_SQL="INSERT INTO users (id, username, password_hash, enabled, roles) VALUES
  (UUID(), 'operator', '${HASH}', TRUE, 'OPERATOR'),
  (UUID(), 'viewer', '${HASH}', TRUE, 'VIEWER'),
  (UUID(), 'auditor', '${HASH}', TRUE, 'AUDITOR')
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), enabled = TRUE, roles = VALUES(roles);"

if [ -n "${MYSQL_CONTAINER:-}" ]; then
  # Explicit override (non-Compose MySQL, or a differently named container).
  printf '%s' "$SEED_SQL" | docker exec -i "$MYSQL_CONTAINER" \
    mysql -utrade -ptrade trade
else
  # Default: resolve the service through Compose so the script does not depend on the generated
  # container name (Compose renames it per project/instance).
  printf '%s' "$SEED_SQL" | docker compose exec -T mysql sh -c \
    'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
fi
printf 'seeded operator/viewer/auditor\n'
