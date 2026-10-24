package com.acme.trade.application.service;

import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.port.UserIdGenerator;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.domain.security.PasswordPolicy;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;

import java.util.Objects;
import java.util.Set;

public final class AdminBootstrapService {
    private final UserRepository users;
    private final PasswordCodec passwords;
    private final UserIdGenerator ids;
    private final TransactionRunner transactions;

    public AdminBootstrapService(UserRepository users, PasswordCodec passwords,
                                 UserIdGenerator ids, TransactionRunner transactions) {
        this.users = Objects.requireNonNull(users, "users");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public void ensureAdmin(String username, String rawPassword) {
        if (users.count() != 0) {
            return;
        }
        PasswordPolicy.validateForCreation(rawPassword);
        String passwordHash = passwords.hash(rawPassword);
        transactions.inTransaction(TransactionMode.WRITE, () -> {
            if (users.count() == 0) {
                users.insert(new UserAccount(ids.newId(), username, passwordHash, true, Set.of(Role.ADMIN)));
            }
            return null;
        });
    }
}
