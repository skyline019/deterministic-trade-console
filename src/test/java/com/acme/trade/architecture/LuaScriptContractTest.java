package com.acme.trade.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LuaScriptContractTest {
    @Test
    void acquisition_is_atomic_and_has_a_ttl() throws IOException {
        String script = resource("redis/acquire-lock.lua");
        assertThat(script).contains("'SET'").contains("'NX'").contains("'PX'")
                .contains("ARGV[1]").contains("ARGV[2]");
    }

    @Test
    void release_compares_owner_before_delete() throws IOException {
        String script = resource("redis/release-lock.lua");
        assertThat(script).contains("'GET'").contains("== ARGV[1]").contains("'DEL'");
    }

    @Test
    void login_bucket_is_atomic_bounded_and_expires_only_on_first_attempt() throws IOException {
        String script = resource("redis/login-throttle.lua");
        assertThat(script).contains("'INCR'").contains("attempts == 1").contains("'PEXPIRE'")
                .contains("'PTTL'").contains("return -ttl")
                .contains("attempts > tonumber(ARGV[1])").contains("ARGV[2]");
        assertThat(script).doesNotContain("'SETEX'", "'FLUSHDB'", "'KEYS'");
    }

    private static String resource(String path) throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
