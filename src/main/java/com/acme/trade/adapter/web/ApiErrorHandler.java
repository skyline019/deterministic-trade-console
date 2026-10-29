package com.acme.trade.adapter.web;

import com.acme.trade.application.exception.AuthenticationException;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RateLimitExceededException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.exception.ResourceConflictException;
import com.acme.trade.application.exception.ResourceNotFoundException;
import com.acme.trade.application.exception.StockAdjustmentRejectedException;
import com.acme.trade.application.exception.StoredStateInvalidException;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.OrderStateConflictException;
import com.acme.trade.domain.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/** Single deterministic exception-to-HTTP mapping; no @ControllerAdvice discovery. */
public final class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    private ApiErrorHandler() {
    }

    public static ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        try {
            return next.handle(request);
        } catch (ValidationException exception) {
            return response(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
        } catch (HttpMessageNotReadableException exception) {
            return response(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "malformed JSON request body");
        } catch (AuthenticationException exception) {
            return response(request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "valid credentials required");
        } catch (AccessDeniedException exception) {
            return response(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", exception.getMessage());
        } catch (RateLimitExceededException exception) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ErrorResponse(exception.code(), exception.getMessage(), request.path()));
        } catch (InsufficientStockException exception) {
            return response(request, HttpStatus.CONFLICT, "OUT_OF_STOCK", exception.getMessage());
        } catch (OrderStateConflictException exception) {
            return response(request, HttpStatus.CONFLICT, "ORDER_STATE_CONFLICT", exception.getMessage());
        } catch (ResourceConflictException exception) {
            return response(request, HttpStatus.CONFLICT, "RESOURCE_CONFLICT", exception.getMessage());
        } catch (StockAdjustmentRejectedException exception) {
            return response(request, HttpStatus.CONFLICT, "STOCK_ADJUSTMENT_REJECTED", exception.getMessage());
        } catch (IdempotencyConflictException exception) {
            return response(request, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage());
        } catch (RequestInProgressException exception) {
            return response(request, HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", exception.getMessage());
        } catch (ResourceNotFoundException exception) {
            return response(request, HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
        } catch (StoredStateInvalidException exception) {
            // The caller sent nothing invalid: a stored row no longer satisfies a domain invariant.
            log.error("Stored state violates a domain invariant for {}", request.path(), exception);
            return response(request, HttpStatus.INTERNAL_SERVER_ERROR, "STORED_STATE_INVALID",
                    "stored state is inconsistent; operator attention required");
        } catch (LockUnavailableException exception) {
            log.warn("Lock infrastructure unavailable for {}", request.path(), exception);
            return response(request, HttpStatus.SERVICE_UNAVAILABLE, "LOCK_UNAVAILABLE", "please retry later");
        } catch (DataAccessException exception) {
            log.error("Database unavailable for {}", request.path(), exception);
            return response(request, HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE", "please retry later");
        } catch (Exception exception) {
            log.error("Unhandled request failure for {}", request.path(), exception);
            return response(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal server error");
        }
    }

    private static ServerResponse response(ServerRequest request, HttpStatus status, String code, String message) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(code, message, request.path()));
    }
}
