package jtest;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tiny gtest-like runner. Deliberately sequential: output order is reproducible. */
public final class JTestRunner {
    private JTestRunner() {
    }

    public static int run(List<Concept> concepts, PrintStream out) {
        Objects.requireNonNull(concepts, "concepts");
        Objects.requireNonNull(out, "out");
        List<JTestCase> cases = new ArrayList<>();
        for (Concept concept : concepts) {
            JTestRegistry registry = new JTestRegistry(concept.name());
            concept.register(registry);
            cases.addAll(registry.cases());
        }

        out.println("[==========] Running " + cases.size() + " jtest concept checks.");
        int failed = 0;
        for (JTestCase test : cases) {
            out.println("[ RUN      ] " + test.displayName());
            try {
                test.body().run();
                out.println("[       OK ] " + test.displayName());
            } catch (Throwable failure) {
                failed++;
                out.println("[  FAILED  ] " + test.displayName());
                failure.printStackTrace(out);
            }
        }
        out.println("[==========] " + cases.size() + " checks ran.");
        if (failed == 0) {
            out.println("[  PASSED  ] " + cases.size() + " checks.");
        } else {
            out.println("[  FAILED  ] " + failed + " of " + cases.size() + " checks.");
        }
        return failed;
    }
}
