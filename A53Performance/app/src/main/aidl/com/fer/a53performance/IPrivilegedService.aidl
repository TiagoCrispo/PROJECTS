package com.fer.a53performance;

interface IPrivilegedService {
    void destroy() = 16777114;
    int setPeakRefreshRate(float value) = 1;
    int setMinRefreshRate(float value) = 2;
    int setLowPower(boolean enabled) = 3;
    int setRestrictBackground(boolean enabled) = 4;
    int forceStopPackage(String packageName) = 5;
    String listRunningUserPackages() = 6;
    float getPeakRefreshRate() = 7;
    float getMinRefreshRate() = 8;
    int getLowPower() = 9;
    int getRestrictBackground() = 10;
    String listSensitiveUserPackages() = 11;
    int ping() = 12;
}
