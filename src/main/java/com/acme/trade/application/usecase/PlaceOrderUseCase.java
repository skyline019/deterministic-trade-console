package com.acme.trade.application.usecase;

import com.acme.trade.application.command.PlaceOrderCommand;
import com.acme.trade.application.result.PlaceOrderResult;

public interface PlaceOrderUseCase {
    PlaceOrderResult place(PlaceOrderCommand command);
}
