package com.acme.trade.application.port;

import com.acme.trade.application.query.OrderPageQuery;
import com.acme.trade.domain.model.Order;
import com.acme.trade.domain.model.OrderId;
import com.acme.trade.domain.model.OrderStatus;
import com.acme.trade.domain.model.RequestId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findByRequestId(RequestId requestId);

    Optional<Order> findById(OrderId orderId);

    List<Order> findPage(OrderPageQuery query, int fetchLimit);

    boolean transitionStatus(OrderId orderId, OrderStatus expected, OrderStatus target, Instant at);

    /**
     * Atomically reduce remaining reserved quantity while staying RESERVED.
     * @return true if a row with {@code expectedQuantity} was updated
     */
    boolean reduceReservedQuantity(OrderId orderId, int expectedQuantity, int newQuantity, Instant at);

    /**
     * Atomically replace expires_at while staying RESERVED, bound to the prior expiry for race safety.
     * @return true if a row with {@code expectedExpiresAt} was updated
     */
    boolean updateExpiresAt(OrderId orderId, Instant expectedExpiresAt, Instant newExpiresAt, Instant at);

    /**
     * RESERVED rows with expires_at &lt;= now, oldest first, capped by limit.
     * Rows whose stored state cannot be materialised are reported in
     * {@link ExpiredReservationScan#unreadable()} rather than aborting the scan.
     */
    ExpiredReservationScan findExpiredReserved(Instant now, int limit);

    /** @throws com.acme.trade.application.exception.DuplicateRequestException on request_id conflict */
    void insert(Order order);
}
