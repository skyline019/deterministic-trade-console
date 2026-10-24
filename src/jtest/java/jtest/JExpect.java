package jtest;

import java.util.Objects;

public final class JExpect {
    private JExpect() {
    }

    public static void expectTrue(boolean condition) {
        expectTrue(condition, "expected condition to be true");
    }

    public static void expectTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void expectFalse(boolean condition) {
        expectTrue(!condition, "expected condition to be false");
    }

    public static void expectEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void expectEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void expectEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void expectSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected the same instance");
        }
    }

    public static <T extends Throwable> T expectThrows(Class<T> type, JTestBody body) {
        try {
            body.run();
        } catch (Throwable actual) {
            if (type.isInstance(actual)) {
                return type.cast(actual);
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + actual, actual);
        }
        throw new AssertionError("expected " + type.getName() + " but nothing was thrown");
    }
}
