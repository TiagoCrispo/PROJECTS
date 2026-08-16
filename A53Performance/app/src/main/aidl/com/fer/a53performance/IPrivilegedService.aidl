package com.fer.a53performance;

interface IPrivilegedService {
    void destroy() = 16777114;
    String exec(String command, long timeoutMs) = 1;
}
