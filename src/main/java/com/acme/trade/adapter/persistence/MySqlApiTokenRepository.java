package com.acme.trade.adapter.persistence;

import com.acme.trade.application.port.ApiTokenRepository;
import com.acme.trade.domain.security.Actor;
import com.acme.trade.domain.security.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class MySqlApiTokenRepository implements ApiTokenRepository {
    private final JdbcTemplate jdbc;

    public MySqlApiTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(String tokenHash, UserAccount user, Instant createdAt, Instant expiresAt) {
        jdbc.update("INSERT INTO api_tokens(token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                tokenHash, user.id().toString(), Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    @Override
    public Optional<Actor> findActiveActor(String tokenHash, Instant now) {
        return jdbc.query("""
                        SELECT u.id, u.username, u.roles
                        FROM api_tokens t JOIN users u ON u.id = t.user_id
                        WHERE t.token_hash = ? AND t.expires_at > ? AND u.enabled = TRUE
                        """,
                (rs, rowNum) -> new Actor(rs.getString("id"), rs.getString("username"),
                        MySqlUserRepository.parseRoles(rs.getString("roles"))), tokenHash, Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public boolean delete(String tokenHash) {
        return jdbc.update("DELETE FROM api_tokens WHERE token_hash = ?", tokenHash) == 1;
    }
}
