package com.acme.trade.application.port;

import com.acme.trade.domain.model.OrderOperation;
import com.acme.trade.domain.model.RequestId;

import java.util.Optional;

public interface OrderOperationRepository {
    Optional<OrderOperation> findByRequestId(RequestId requestId);

    /** @throws com.acme.trade.application.exception.DuplicateRequestException on request_id conflict */
    void insert(OrderOperation operation);
}
