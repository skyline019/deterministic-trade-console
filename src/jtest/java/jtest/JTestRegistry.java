package jtest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JTestRegistry {
    private final String suite;
    private final List<JTestCase> cases = new ArrayList<>();
    private final Set<String> names = new HashSet<>();

    JTestRegistry(String suite) {
        this.suite = Objects.requireNonNull(suite, "suite");
    }

    public void test(String name, JTestBody body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
        if (name.isBlank() || !names.add(name)) {
            throw new IllegalArgumentException("jtest case names must be non-blank and unique: " + name);
        }
        cases.add(new JTestCase(suite, name, body));
    }

    List<JTestCase> cases() {
        return List.copyOf(cases);
    }
}
