package com.mendozameteo.x10;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OpenMeteoProviderTest {
    @Test public void bestMatchRequestsProbabilityAndExplicitMendozaUnits(){
        OpenMeteoProvider provider=new OpenMeteoProvider(OpenMeteoProvider.Model.BEST_MATCH,new HttpJsonTransport());
        String endpoint=provider.buildEndpoint(-32.89,-68.84);
        assertTrue(endpoint.startsWith("https://api.open-meteo.com/v1/forecast?"));
        assertTrue(endpoint.contains("timezone=America%2FArgentina%2FMendoza"));
        assertTrue(endpoint.contains("precipitation_probability"));
        assertTrue(endpoint.contains("wind_speed_unit=kmh"));assertTrue(endpoint.contains("precipitation_unit=mm"));
    }
    @Test public void ecmwfDoesNotPretendToHaveProbability(){
        OpenMeteoProvider provider=new OpenMeteoProvider(OpenMeteoProvider.Model.ECMWF,new HttpJsonTransport());
        String endpoint=provider.buildEndpoint(-32.89,-68.84);
        assertTrue(endpoint.startsWith("https://api.open-meteo.com/v1/ecmwf?"));
        assertFalse(endpoint.contains("precipitation_probability"));
    }
}
