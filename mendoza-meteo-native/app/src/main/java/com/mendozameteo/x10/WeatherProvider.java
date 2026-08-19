package com.mendozameteo.x10;

interface WeatherProvider {
    String id();
    String label();
    WeatherClient.Forecast fetch(double latitude, double longitude) throws WeatherException;
}
