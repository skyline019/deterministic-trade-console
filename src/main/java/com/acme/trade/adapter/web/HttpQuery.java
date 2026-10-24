package com.acme.trade.adapter.web;

import com.acme.trade.domain.exception.ValidationException;
import org.springframework.web.servlet.function.ServerRequest;

final class HttpQuery {
    private HttpQuery() {
    }

    static int integer(ServerRequest request, String name, int defaultValue) {
        return request.param(name).map(value -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new ValidationException(name + " must be an integer");
            }
        }).orElse(defaultValue);
    }

    static String required(ServerRequest request, String name) {
        return request.param(name).filter(value -> !value.isBlank())
                .orElseThrow(() -> new ValidationException(name + " query parameter is required"));
    }
}
