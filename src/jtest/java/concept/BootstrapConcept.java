package concept;

import com.acme.trade.application.port.PasswordCodec;
import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import com.acme.trade.application.port.UserRepository;
import com.acme.trade.application.service.AdminBootstrapService;
import com.acme.trade.domain.exception.ValidationException;
import com.acme.trade.domain.security.Role;
import com.acme.trade.domain.security.UserAccount;
import jtest.Concept;
import jtest.JTestRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectThrows;
import static jtest.JExpect.expectTrue;

public final class BootstrapConcept implements Concept {
    @Override
    public String name() {
        return "AdminBootstrap";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("first_boot_creates_only_a_hashed_enabled_admin", () -> {
            FakeUsers users = new FakeUsers();
            FakePasswords passwords = new FakePasswords();
            FakeTransactions transactions = new FakeTransactions();
            AdminBootstrapService service = new AdminBootstrapService(users, passwords,
                    () -> UUID.fromString("00000000-0000-4000-8000-000000000001"), transactions);

            service.ensureAdmin("admin", "bootstrap-secret");

            expectEquals(1, users.values.size());
            UserAccount admin = users.values.get(0);
            expectEquals("admin", admin.username());
            expectEquals(Set.of(Role.ADMIN), admin.roles());
            expectTrue(admin.enabled());
            expectEquals("encoded:bootstrap-secret", admin.passwordHash());
            expectTrue(!admin.passwordHash().equals("bootstrap-secret"));
            expectEquals(1, transactions.writes);
            expectEquals(0, transactions.reads);
        });

        tests.test("empty_initial_password_is_rejected_before_hash_or_insert", () -> {
            FakeUsers users = new FakeUsers();
            FakePasswords passwords = new FakePasswords();
            AdminBootstrapService service = new AdminBootstrapService(users, passwords,
                    UUID::randomUUID, new FakeTransactions());

            expectThrows(ValidationException.class, () -> service.ensureAdmin("admin", ""));
            expectThrows(ValidationException.class, () -> service.ensureAdmin("admin", null));

            expectEquals(0, users.values.size());
            expectEquals(0, passwords.hashes);
        });

        tests.test("restart_with_existing_users_never_requires_a_default_password", () -> {
            FakeUsers users = new FakeUsers();
            users.count = 1;
            FakePasswords passwords = new FakePasswords();
            FakeTransactions transactions = new FakeTransactions();
            AdminBootstrapService service = new AdminBootstrapService(users, passwords,
                    UUID::randomUUID, transactions);

            service.ensureAdmin("admin", null);
            service.ensureAdmin("admin", "");

            expectEquals(0, users.values.size());
            expectEquals(0, passwords.hashes);
            expectEquals(0, transactions.writes);
        });

        tests.test("user_committed_between_bootstrap_checks_prevents_duplicate_insert", () -> {
            FakeUsers users = new FakeUsers();
            users.counts = new int[]{0, 1};
            FakePasswords passwords = new FakePasswords();
            FakeTransactions transactions = new FakeTransactions();
            AdminBootstrapService service = new AdminBootstrapService(users, passwords,
                    UUID::randomUUID, transactions);

            service.ensureAdmin("admin", "bootstrap-secret");

            expectEquals(0, users.values.size());
            expectEquals(1, passwords.hashes);
            expectEquals(1, transactions.writes);
        });
    }

    private static final class FakeUsers implements UserRepository {
        private final List<UserAccount> values = new ArrayList<>();
        private long count;
        private int[] counts;
        private int countCalls;

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return values.stream().filter(user -> user.username().equals(username)).findFirst();
        }

        @Override
        public long count() {
            if (counts != null && countCalls < counts.length) {
                return counts[countCalls++];
            }
            countCalls++;
            return count;
        }

        @Override
        public void insert(UserAccount user) {
            values.add(user);
            count++;
        }
    }

    private static final class FakePasswords implements PasswordCodec {
        private int hashes;

        @Override
        public String hash(String rawPassword) {
            hashes++;
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String encodedHash) {
            return encodedHash.equals(hash(rawPassword));
        }
    }

    private static final class FakeTransactions implements TransactionRunner {
        private int writes;
        private int reads;

        @Override
        public <T> T inTransaction(TransactionMode mode, Supplier<T> action) {
            if (mode == TransactionMode.WRITE) {
                writes++;
            } else {
                reads++;
            }
            return action.get();
        }
    }
}
