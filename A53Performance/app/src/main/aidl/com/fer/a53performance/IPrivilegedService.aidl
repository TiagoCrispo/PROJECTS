package com.fer.a53performance;

interface IPrivilegedService {
    void destroy() = 16777114;
    int setPeakRefreshRate(float value) = 1;
    int setMinRefreshRate(float value) = 2;
    int setLowPower(boolean enabled) = 3;
    int setRestrictBackground(boolean enabled) = 4;
    int forceStopPackage(String packageName) = 5;
    String listProcessNames() = 6;
}
