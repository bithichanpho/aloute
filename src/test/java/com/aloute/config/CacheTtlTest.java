package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CacheTtlTest {
    @Test void redisJitterNeverExtendsFreshnessAndSpreadsExpirations() {
        var observed=new HashSet<Duration>();
        for(int i=0;i<100;i++) {
            Duration ttl=CacheConfig.jitter(Duration.ofSeconds(30));observed.add(ttl);
            assertTrue(ttl.compareTo(Duration.ofSeconds(27))>=0);
            assertTrue(ttl.compareTo(Duration.ofSeconds(30))<=0);
        }
        assertTrue(observed.size()>1);
        assertEquals(Duration.ZERO,CacheConfig.jitter(Duration.ZERO));
    }
}
