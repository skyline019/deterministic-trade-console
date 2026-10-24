package jtest;

/** A named group of deterministic examples, registered without reflection or annotations. */
public interface Concept {
    String name();

    void register(JTestRegistry tests);
}
