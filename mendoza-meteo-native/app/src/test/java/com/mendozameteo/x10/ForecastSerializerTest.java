package com.mendozameteo.x10;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public final class ForecastSerializerTest {
    @Test public void roundTripPreservesNormalizedForecast() throws Exception {
        WeatherClient.Forecast original=WeatherTestData.forecast("best",123456789L);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        ForecastSerializer.write(output,original);
        WeatherClient.Forecast restored=ForecastSerializer.read(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(original.providerId,restored.providerId);
        assertEquals(original.timezone,restored.timezone);
        assertEquals(original.fetchedAtMillis,restored.fetchedAtMillis);
        assertEquals(24,restored.hours.size()); assertEquals(7,restored.days.size());
        assertEquals(70,restored.hours.get(5).rainProbability);
        assertEquals(1.0,restored.hours.get(5).precipitation,0.0001);
        assertEquals(WeatherClient.MENDOZA_TZ_ID,restored.timezone);
    }
    @Test public void rejectsTruncatedCache() throws Exception {
        WeatherClient.Forecast original=WeatherTestData.forecast("best",123456789L);
        ByteArrayOutputStream output=new ByteArrayOutputStream();ForecastSerializer.write(output,original);
        byte[] full=output.toByteArray(),truncated=new byte[full.length/2];System.arraycopy(full,0,truncated,0,truncated.length);
        boolean failed=false;try{ForecastSerializer.read(new ByteArrayInputStream(truncated));}catch(IOException expected){failed=true;}assertTrue(failed);
    }
}
