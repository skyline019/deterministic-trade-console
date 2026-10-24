package com.acme.trade.adapter.web;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.application.result.OrderTransitionResult;
import com.acme.trade.application.result.PlaceOrderResult;
import com.acme.trade.application.service.OrderQueryService;
import com.acme.trade.application.service.OrderRescheduleService;
import com.acme.trade.application.service.OrderTransitionService;
import com.acme.trade.application.usecase.PlaceOrderUseCase;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.OrderCursor;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.Quantity;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Plain handler bean, registered by an explicit RouterFunction. */
public final class OrderHandler {
    private final PlaceOrderUseCase placeOrder;
    private final OrderQueryService queries;
    private final OrderTransitionService transitions;
    private final OrderRescheduleService reschedules;

    public OrderHandler(PlaceOrderUseCase placeOrder, OrderQueryService queries,
                        OrderTransitionService transitions, OrderRescheduleService reschedules) {
        this.placeOrder = Objects.requireNonNull(placeOrder, "placeOrder");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.reschedules = Objects.requireNonNull(reschedules, "reschedules");
    }

    public ServerResponse place(ServerRequest request) throws Exception {
        PlaceOrderHttpRequest body = request.body(PlaceOrderHttpRequest.class);
        if (body == null) {
            throw new ValidationException("JSON request body is required");
        }
        PlaceOrderResult result = placeOrder.place(PlaceOrderCommand.of(ActorRequest.actor(request),
                request.headers().firstHeader("Idempotency-Key"), body.sku(), body.quantity()));
        OrderResponse response = OrderResponse.from(result.order());
        return ServerResponse.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/orders/" + response.id()))
                .header("Idempotent-Replay", Boolean.toString(result.replayed()))
                .contentType(MediaType.APPLICATION_JSON).body(response);
    }

    public ServerResponse get(ServerRequest request) {
        OrderId id = OrderId.of(request.pathVariable("id"));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .body(OrderResponse.from(queries.get(ActorRequest.actor(request), id)));
    }

    public ServerResponse list(ServerRequest request) {
        Optional<OrderStatus> status = request.param("status").map(value -> {
            try {
                return OrderStatus.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new ValidationException("unknown order status");
            }
        });
        Optional<Sku> sku = request.param("sku").map(Sku::of);
        Optional<OrderCursor> cursor = request.param("cursor").map(OrderCursor::parse);
        int limit = HttpQuery.integer(request, "limit", 30);
        var page = queries.list(ActorRequest.actor(request), new OrderPageQuery(status, sku, cursor, limit));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(OrderPageResponse.from(page));
    }

    public ServerResponse confirm(ServerRequest request) throws Exception {
        OrderTransitionHttpRequest body = requireBody(request);
        Quantity quantity = body.quantity() == null ? null : new Quantity(body.quantity());
        OrderTransitionResult result = transitions.confirm(ActorRequest.actor(request),
                new RequestId(request.headers().firstHeader("Idempotency-Key")),
                OrderId.of(request.pathVariable("id")), quantity, body.reason());
        return ServerResponse.ok().header("Idempotent-Replay", Boolean.toString(result.replayed()))
                .contentType(MediaType.APPLICATION_JSON).body(OrderResponse.from(result.order()));
    }

    public ServerResponse cancel(ServerRequest request) throws Exception {
        OrderTransitionHttpRequest body = requireBody(request);
        if (body.quantity() != null) {
            throw new ValidationException("cancellation does not accept quantity; cancel releases all remaining units");
        }
        OrderTransitionResult result = transitions.transition(ActorRequest.actor(request),
                new RequestId(request.headers().firstHeader("Idempotency-Key")),
                OrderId.of(request.pathVariable("id")), OrderStatus.CANCELLED, body.reason());
        return ServerResponse.ok().header("Idempotent-Replay", Boolean.toString(result.replayed()))
                .contentType(MediaType.APPLICATION_JSON).body(OrderResponse.from(result.order()));
    }

    public ServerResponse reschedule(ServerRequest request) throws Exception {
        OrderRescheduleHttpRequest body = request.body(OrderRescheduleHttpRequest.class);
        if (body == null) {
            throw new ValidationException("JSON request body is required");
        }
        if (body.expiresAt() == null) {
            throw new ValidationException("expiresAt is required");
        }
        OrderTransitionResult result = reschedules.reschedule(ActorRequest.actor(request),
                new RequestId(request.headers().firstHeader("Idempotency-Key")),
                OrderId.of(request.pathVariable("id")), body.expiresAt(), body.reason());
        return ServerResponse.ok().header("Idempotent-Replay", Boolean.toString(result.replayed()))
                .contentType(MediaType.APPLICATION_JSON).body(OrderResponse.from(result.order()));
    }

    private static OrderTransitionHttpRequest requireBody(ServerRequest request) throws Exception {
        OrderTransitionHttpRequest body = request.body(OrderTransitionHttpRequest.class);
        if (body == null) {
            throw new ValidationException("JSON request body is required");
        }
        return body;
    }
}
