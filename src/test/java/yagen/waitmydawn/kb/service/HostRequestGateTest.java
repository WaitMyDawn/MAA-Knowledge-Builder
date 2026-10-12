package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class HostRequestGateTest {
    private static final String PAGE="https://www.mcmod.cn/item/1.html";
    @Test void subdomainsShareSpacingButOtherSitesRemainIndependent() {
        var time=new AtomicLong(10_000);
        var gate=new HostRequestGate(time::get,time::addAndGet);
        assertTrue(gate.await(PAGE,800));
        assertTrue(gate.await("https://wiki.biligame.com/mc/test",800));
        assertEquals(10_000,time.get());
        assertTrue(gate.await("https://search.mcmod.cn/s?key=test",800));
        assertEquals(10_800,time.get());
    }
    @Test void cooldownSkipsNetworkWithoutSleepingAndCannotBeShortened() {
        var time=new AtomicLong(10_000);
        var gate=new HostRequestGate(time::get,time::addAndGet);
        gate.coolDown(PAGE,3_600_000);
        gate.coolDown(PAGE,30_000);
        assertFalse(gate.await("https://search.mcmod.cn/s?key=test",800));
        assertEquals(10_000,time.get());
        time.addAndGet(3_599_999);
        assertFalse(gate.await(PAGE,800));
        time.incrementAndGet();
        assertTrue(gate.await(PAGE,800));
    }
    @Test void interruptionCancelsWaitWithoutSendingAnotherRequest() {
        var gate=new HostRequestGate(()->10_000,millis->{ throw new InterruptedException(); });
        assertTrue(gate.await(PAGE,800));
        try { assertThrows(java.util.concurrent.CancellationException.class,()->gate.await(PAGE,800)); }
        finally { assertTrue(Thread.interrupted()); }
    }
    @Test void retryAfterSupportsSecondsHttpDatesAndMalformedHeaders() {
        var now=Instant.parse("2026-10-11T00:00:00Z");
        assertEquals(3_600_000,HostRequestGate.retryAfterMillis("3600",now));
        assertEquals(3_600_000,HostRequestGate.retryAfterMillis("Sun, 11 Oct 2026 01:00:00 GMT",now));
        assertEquals(0,HostRequestGate.retryAfterMillis("Sat, 10 Oct 2026 01:00:00 GMT",now));
        assertEquals(0,HostRequestGate.retryAfterMillis("invalid",now));
        assertEquals(0,HostRequestGate.retryAfterMillis("-3",now));
    }
}
