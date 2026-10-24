package com.acme.trade.application.port;

/** Closing must be idempotent and must never release another owner's token. */
public interface LockHandle extends AutoCloseable {
    @Override
    void close();
}
