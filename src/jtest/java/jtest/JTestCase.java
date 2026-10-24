package jtest;

record JTestCase(String suite, String name, JTestBody body) {
    String displayName() {
        return suite + "/" + name;
    }
}
