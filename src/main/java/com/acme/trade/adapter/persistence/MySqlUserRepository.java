package com.acme.trade.adapter.persistence;

import com.acme.trade.application.port.UserRepository;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MySqlUserRepository implements UserRepository {
    static final RowMapper<UserAccount> USER_MAPPER = (rs, rowNum) -> new UserAccount(
            UUID.fromString(rs.getString("id")), rs.getString("username"), rs.getString("password_hash"),
            rs.getBoolean("enabled"), parseRoles(rs.getString("roles")));

    private final JdbcTemplate jdbc;

    public MySqlUserRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    static Set<Role> parseRoles(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(role -> !role.isEmpty())
                .map(Role::valueOf).collect(Collectors.toSet());
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash, enabled, roles FROM users WHERE username = ?",
                USER_MAPPER, username).stream().findFirst();
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(UserAccount user) {
        String roles = user.roles().stream().map(Role::name).sorted().collect(Collectors.joining(","));
        jdbc.update("INSERT INTO users(id, username, password_hash, enabled, roles) VALUES (?, ?, ?, ?, ?)",
                user.id().toString(), user.username(), user.passwordHash(), user.enabled(), roles);
    }
}
