package com.acme.trade.adapter.web;

import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.StoredStateInvalidException;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApiErrorHandler} is the whole HTTP error contract in one catch chain, so the mapping is pinned
 * here rather than repeated in every handler test: status, stable code and message for each semantically
 * distinct failure — including the distinction that a corrupt stored row is a 500, never a caller-blaming 400.
 */
class ApiErrorHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/v1/orders/abc";

    @Test
    void a_stored_row_that_breaks_a_domain_invariant_is_a_server_error() throws Exception {
        Mapped mapped = map(new StoredStateInvalidException(
                "stored order 00000000-0000-0000-0000-000000000abc violates a domain invariant: "
                        + "reserved order expiresAt must be after createdAt", null));

        assertThat(mapped.status()).isEqualTo(500);
        assertThat(mapped.body().path("code").asText()).isEqualTo("STORED_STATE_INVALID");
        // The caller sent nothing invalid, so the response must not blame the request...
        assertThat(mapped.body().path("code").asText()).isNotEqualTo("INVALID_REQUEST");
        // ...and must not leak the row-level detail (ids, timestamps) to the client.
        assertThat(mapped.body().path("message").asText()).doesNotContain("expiresAt", "stored order");
        assertThat(mapped.body().path("path").asText()).isEqualTo(PATH);
    }

    @Test
    void a_domain_validation_error_stays_a_bad_request() throws Exception {
        Mapped mapped = map(new ValidationException("Idempotency-Key must contain 1..128 characters"));

        assertThat(mapped.status()).isEqualTo(400);
        assertThat(mapped.body().path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(mapped.body().path("message").asText()).contains("Idempotency-Key");
    }

    @Test
    void conflicts_keep_their_distinct_codes() throws Exception {
        assertThat(map(new OrderStateConflictException("order is already terminal")).status()).isEqualTo(409);
        assertThat(map(new OrderStateConflictException("order is already terminal")).body().path("code").asText())
                .isEqualTo("ORDER_STATE_CONFLICT");
        assertThat(map(new IdempotencyConflictException("k-1")).body().path("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void lock_infrastructure_failure_is_a_retryable_503_without_internals() throws Exception {
        Mapped mapped = map(new LockUnavailableException("redis down", new IllegalStateException("connection refused")));

        assertThat(mapped.status()).isEqualTo(503);
        assertThat(mapped.body().path("code").asText()).isEqualTo("LOCK_UNAVAILABLE");
        assertThat(mapped.body().path("message").asText())
                .as("infrastructure detail belongs in the log, not in the body")
                .doesNotContain("redis", "connection refused");
    }

    /** Runs a throwing handler through the real filter and renders the response like the servlet container would. */
    private static Mapped map(RuntimeException failure) throws Exception {
        ServerRequest request = mock(ServerRequest.class);
        when(request.path()).thenReturn(PATH);
        HandlerFunction<ServerResponse> handler = ignored -> {
            throw failure;
        };

        ServerResponse response = ApiErrorHandler.filter(request, handler);
        byte[] body = render(response);
        return new Mapped(response.statusCode().value(), JSON.readTree(new String(body, StandardCharsets.UTF_8)));
    }

    private static byte[] render(ServerResponse response) throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ServletOutputStream output = new ServletOutputStream() {
            @Override
            public void write(int value) {
                sink.write(value);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // nothing to do for an in-memory sink
            }
        };
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getMethod()).thenReturn("GET");
        when(servletRequest.getRequestURI()).thenReturn(PATH);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(servletResponse.getOutputStream()).thenReturn(output);

        List<HttpMessageConverter<?>> converters =
                List.of(new MappingJackson2HttpMessageConverter(JSON));
        response.writeTo(servletRequest, servletResponse, () -> converters);
        return sink.toByteArray();
    }

    private record Mapped(int status, JsonNode body) {
    }
}
