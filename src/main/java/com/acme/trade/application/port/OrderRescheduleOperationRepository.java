package com.acme.trade.application.port;

import com.acme.trade.domain.model.OrderRescheduleOperation;
import com.acme.trade.domain.model.RequestId;

import java.util.Optional;

public interface OrderRescheduleOperationRepository {
    Optional<OrderRescheduleOperation> findByRequestId(RequestId requestId);

    /** @throws com.acme.trade.application.exception.DuplicateRequestException on request_id conflict */
    void insert(OrderRescheduleOperation operation);
}
