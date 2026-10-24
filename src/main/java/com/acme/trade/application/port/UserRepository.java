package com.acme.trade.application.port;

import com.acme.trade.domain.security.UserAccount;

import java.util.Optional;

public interface UserRepository {
    Optional<UserAccount> findByUsername(String username);

    long count();

    void insert(UserAccount user);
}
