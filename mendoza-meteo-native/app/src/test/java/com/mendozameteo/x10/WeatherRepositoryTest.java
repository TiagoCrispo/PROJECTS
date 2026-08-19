package com.mendozameteo.x10;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class WeatherRepositoryTest {
    @Test public void primarySuccessIsLiveAndNotDegraded(){
        FakeStore store=new FakeStore();FakeProvider primary=FakeProvider.success("primary");
        WeatherRepository repository=new WeatherRepository(store,()->true,Arrays.asList(primary,FakeProvider.success("fallback")));
        WeatherRepository.Result result=repository.load("local",-32.89,-68.84);
        assertTrue(result.isSuccess());assertEquals(WeatherRepository.Origin.LIVE,result.origin);assertFalse(result.degradedProvider);assertEquals("primary",result.forecast.providerId);assertEquals(1,result.attemptedProviders);assertNotNull(store.saved);
    }
    @Test public void invalidPrimaryFallsBackWithoutInventingSuccess(){
        FakeStore store=new FakeStore();WeatherRepository repository=new WeatherRepository(store,()->true,Arrays.asList(FakeProvider.failure("primary",WeatherException.Kind.INVALID_DATA),FakeProvider.success("gfs")));
        WeatherRepository.Result result=repository.load("local",-32.89,-68.84);assertTrue(result.isSuccess());assertTrue(result.degradedProvider);assertEquals("gfs",result.forecast.providerId);assertEquals(2,result.attemptedProviders);
    }
    @Test public void allProvidersFailThenUsableCacheWins(){
        FakeStore store=new FakeStore();WeatherClient.Forecast cached=WeatherTestData.forecast("cached",System.currentTimeMillis()-2L*60L*60L*1000L);store.cached=new ForecastStore.Entry(cached,ForecastFreshness.State.STALE,2L*60L*60L*1000L);
        WeatherRepository repository=new WeatherRepository(store,()->true,Arrays.asList(FakeProvider.failure("primary",WeatherException.Kind.TIMEOUT),FakeProvider.failure("gfs",WeatherException.Kind.NETWORK)));
        WeatherRepository.Result result=repository.load("local",-32.89,-68.84);assertTrue(result.isSuccess());assertEquals(WeatherRepository.Origin.CACHE,result.origin);assertEquals(ForecastFreshness.State.STALE,result.freshness);assertFalse(result.safeForAlerts());assertEquals("cached",result.forecast.providerId);
    }
    @Test public void offlineSkipsProvidersAndUsesCache(){
        FakeStore store=new FakeStore();store.cached=new ForecastStore.Entry(WeatherTestData.forecast("cached",System.currentTimeMillis()),ForecastFreshness.State.FRESH,1000L);FakeProvider provider=FakeProvider.success("should-not-run");WeatherRepository repository=new WeatherRepository(store,()->false,Arrays.asList(provider));
        WeatherRepository.Result result=repository.load("local",-32.89,-68.84);assertTrue(result.isSuccess());assertEquals(0,provider.calls);assertEquals(WeatherException.Kind.OFFLINE,result.failure.kind);
    }
    @Test public void noNetworkAndNoCacheReturnsExplicitFailure(){
        FakeStore store=new FakeStore();WeatherRepository repository=new WeatherRepository(store,()->false,new ArrayList<>());WeatherRepository.Result result=repository.load("local",-32.89,-68.84);assertFalse(result.isSuccess());assertNull(result.forecast);assertEquals(WeatherException.Kind.OFFLINE,result.failure.kind);
    }
    private static final class FakeProvider implements WeatherProvider{
        private final String id;private final WeatherException.Kind failure;int calls;
        static FakeProvider success(String id){return new FakeProvider(id,null);}static FakeProvider failure(String id,WeatherException.Kind failure){return new FakeProvider(id,failure);}FakeProvider(String id,WeatherException.Kind failure){this.id=id;this.failure=failure;}
        public String id(){return id;}public String label(){return id;}public WeatherClient.Forecast fetch(double lat,double lon)throws WeatherException{calls++;if(failure!=null)throw new WeatherException(failure,"forced");return WeatherTestData.forecast(id,System.currentTimeMillis());}
    }
    private static final class FakeStore implements ForecastStore{ForecastStore.Entry cached;WeatherClient.Forecast saved;public void save(String slot,WeatherClient.Forecast forecast){saved=forecast;}public Entry load(String slot,double lat,double lon,long now){return cached;}}
}
