package com.acme.trade.adapter.web;

import com.acme.trade.application.service.InventoryService;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.model.RequestId;
import com.acme.trade.domain.model.Sku;
import com.acme.trade.domain.model.StockLevel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Objects;

public final class InventoryHandler {
    private final InventoryService inventory;

    public InventoryHandler(InventoryService inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public ServerResponse create(ServerRequest request) throws Exception {
        CreateInventoryHttpRequest body = request.body(CreateInventoryHttpRequest.class);
        if (body == null) {
            throw new ValidationException("JSON request body is required");
        }
        var result = inventory.create(ActorRequest.actor(request),
                new RequestId(request.headers().firstHeader("Idempotency-Key")), Sku.of(body.sku()),
                StockLevel.of(body.initialStock()), body.reason());
        return ServerResponse.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", Boolean.toString(result.replayed()))
                .contentType(MediaType.APPLICATION_JSON).body(InventoryOperationResponse.from(result));
    }

    public ServerResponse adjust(ServerRequest request) throws Exception {
        AdjustInventoryHttpRequest body = request.body(AdjustInventoryHttpRequest.class);
        if (body == null || body.delta() == null) {
            throw new ValidationException("JSON body with a numeric delta is required");
        }
        var result = inventory.adjust(ActorRequest.actor(request),
                new RequestId(request.headers().firstHeader("Idempotency-Key")),
                Sku.of(request.pathVariable("sku")), body.delta(), body.reason());
        return ServerResponse.ok().header("Idempotent-Replay", Boolean.toString(result.replayed()))
                .contentType(MediaType.APPLICATION_JSON).body(InventoryOperationResponse.from(result));
    }

    public ServerResponse get(ServerRequest request) {
        var result = inventory.get(ActorRequest.actor(request), Sku.of(request.pathVariable("sku")));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(InventoryResponse.from(result));
    }

    public ServerResponse list(ServerRequest request) {
        var result = inventory.list(ActorRequest.actor(request)).stream().map(InventoryResponse::from).toList();
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }
}
