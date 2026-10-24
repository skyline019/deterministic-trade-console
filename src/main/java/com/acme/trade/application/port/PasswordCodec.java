package com.acme.trade.application.port;

public interface PasswordCodec {
    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedHash);
}
