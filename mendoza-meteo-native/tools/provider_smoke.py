#!/usr/bin/env python3
import json, time, urllib.parse, urllib.request
LAT=-32.89; LON=-68.84; TZ="America/Argentina/Mendoza"
COMMON_CURRENT=["temperature_2m","apparent_temperature","relative_humidity_2m","dew_point_2m","precipitation","snowfall","weather_code","pressure_msl","cloud_cover","visibility","wind_speed_10m","wind_gusts_10m","wind_direction_10m"]
COMMON_HOURLY=["temperature_2m","apparent_temperature","relative_humidity_2m","dew_point_2m","precipitation","rain","showers","snowfall","weather_code","pressure_msl","cloud_cover","visibility","wind_speed_10m","wind_gusts_10m","wind_direction_10m"]
COMMON_DAILY=["temperature_2m_max","temperature_2m_min","precipitation_sum","rain_sum","snowfall_sum","weather_code","wind_speed_10m_max","wind_gusts_10m_max","wind_direction_10m_dominant"]
PROVIDERS=[("best_match","https://api.open-meteo.com/v1/forecast",True),("gfs","https://api.open-meteo.com/v1/gfs",True),("ecmwf","https://api.open-meteo.com/v1/ecmwf",False)]
def once(name,base,probability):
    current=COMMON_CURRENT.copy();hourly=COMMON_HOURLY.copy();daily=COMMON_DAILY.copy()
    if probability: current.insert(4,"precipitation_probability");hourly.insert(4,"precipitation_probability");daily.insert(2,"precipitation_probability_max")
    params={"latitude":f"{LAT:.5f}","longitude":f"{LON:.5f}","timezone":TZ,"forecast_days":"7","temperature_unit":"celsius","wind_speed_unit":"kmh","precipitation_unit":"mm","timeformat":"iso8601","current":",".join(current),"hourly":",".join(hourly),"daily":",".join(daily)}
    req=urllib.request.Request(base+"?"+urllib.parse.urlencode(params),headers={"User-Agent":"MendozaMeteoX10-CI/2","Accept":"application/json"})
    with urllib.request.urlopen(req,timeout=20) as response: payload=json.load(response)
    print(f"PROVIDER_KEYS {name} keys={sorted(payload.keys())}")
    assert payload.get("timezone")==TZ,(name,payload.get("timezone"));assert "hourly" in payload,name;assert len(payload["hourly"]["time"])>=48,name
    if name!="ecmwf": assert "current" in payload and "daily" in payload,name;assert len(payload["daily"]["time"])>=7,name
    if probability: assert "precipitation_probability" in payload["hourly"],name;assert "precipitation_probability_max" in payload["daily"],name
    else: assert "precipitation_probability" not in payload["hourly"],name
    print(f"PROVIDER_SMOKE_OK {name} timezone={payload['timezone']} hourly={len(payload['hourly']['time'])}")
def fetch(provider):
    last=None
    for attempt in range(3):
        try:return once(*provider)
        except Exception as e:last=e;time.sleep(1+attempt)
    raise last
if __name__=="__main__":
    for provider in PROVIDERS:fetch(provider)
