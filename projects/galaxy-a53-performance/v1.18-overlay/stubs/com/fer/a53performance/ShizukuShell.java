package com.fer.a53performance;
public final class ShizukuShell {
    public static boolean ready() { return false; }
    public static void request() { }
    public static Result run(String command) { return null; }
    public static final class Result {
        public final int code = -1;
        public final String err = "";
        public final String out = "";
        public boolean ok() { return false; }
    }
}
