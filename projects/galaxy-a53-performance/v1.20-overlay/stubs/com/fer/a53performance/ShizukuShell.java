package com.fer.a53performance;
public final class ShizukuShell {
    public static boolean ready() { return false; }
    public static void request() { }
    public static Result run(String command) { return null; }
    public static final class Result {
        public int code;
        public String err;
        public String out;
        public boolean ok() { return false; }
    }
}
