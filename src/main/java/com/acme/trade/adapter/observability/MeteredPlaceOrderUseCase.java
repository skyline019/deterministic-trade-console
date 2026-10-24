package com.acme.trade.adapter.observability;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.exception.IdempotencyConflictException;
import com.acme.trade.application.exception.InsufficientStockException;
import com.acme.trade.application.exception.LockUnavailableException;
import com.acme.trade.application.exception.RequestInProgressException;
import com.acme.trade.application.result.PlaceOrderResult;
import com.acme.trade.application.usecase.PlaceOrderUseCase;
import com.acme.trade.domain.exception.AccessDeniedException;
import com.acme.trade.domain.exception.ValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Explicit observability decorator with pre-registered, bounded-cardinality meters. */
public final class MeteredPlaceOrderUseCase implements PlaceOrderUseCase {
    private enum Outcome {
        CREATED, REPLAYED, OUT_OF_STOCK, DENIED, IDEMPOTENCY_CONFLICT,
        REQUEST_IN_PROGRESS, VALIDATION_ERROR, LOCK_UNAVAILABLE, ERROR
    }

    private final PlaceOrderUseCase delegate;
    private final Map<Outcome, Counter> counters;
    private final Map<Outcome, Timer> timers;

    public MeteredPlaceOrderUseCase(PlaceOrderUseCase delegate, MeterRegistry metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(metrics, "metrics");
        counters = new EnumMap<>(Outcome.class);
        timers = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("trade.order.requests")
                    .tag("outcome", outcome.name()).register(metrics));
            timers.put(outcome, Timer.builder("trade.order.duration")
                    .tag("outcome", outcome.name()).publishPercentileHistogram().register(metrics));
        }
    }

    @Override
    public PlaceOrderResult place(PlaceOrderCommand command) {
        long started = System.nanoTime();
        Outcome outcome = Outcome.ERROR;
        try {
            PlaceOrderResult result = delegate.place(command);
            outcome = result.replayed() ? Outcome.REPLAYED : Outcome.CREATED;
            return result;
        } catch (InsufficientStockException exception) {
            outcome = Outcome.OUT_OF_STOCK;
            throw exception;
        } catch (AccessDeniedException exception) {
            outcome = Outcome.DENIED;
            throw exception;
        } catch (IdempotencyConflictException exception) {
            outcome = Outcome.IDEMPOTENCY_CONFLICT;
            throw exception;
        } catch (RequestInProgressException exception) {
            outcome = Outcome.REQUEST_IN_PROGRESS;
            throw exception;
        } catch (ValidationException exception) {
            outcome = Outcome.VALIDATION_ERROR;
            throw exception;
        } catch (LockUnavailableException exception) {
            outcome = Outcome.LOCK_UNAVAILABLE;
            throw exception;
        } catch (RuntimeException exception) {
            outcome = Outcome.ERROR;
            throw exception;
        } finally {
            counters.get(outcome).increment();
            timers.get(outcome).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
