package com.acme.trade.application.port;

import com.acme.trade.domain.model.InventoryOperation;
import com.acme.trade.domain.model.RequestId;

import java.util.Optional;

public interface InventoryOperationRepository {
    Optional<InventoryOperation> findByRequestId(RequestId requestId);

    /** @throws com.acme.trade.application.exception.DuplicateRequestException on request_id conflict */
    void insert(InventoryOperation operation);
}
