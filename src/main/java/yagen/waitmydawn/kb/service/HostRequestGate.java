package yagen.waitmydawn.kb.service;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 同一站点共享间隔和冷却；等待不持有全局锁，以免阻塞其他站点。 */
final class HostRequestGate {
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Map<String,Long> next=new HashMap<>(), cooldown=new HashMap<>();
    HostRequestGate() { this(System::currentTimeMillis,Thread::sleep); }
    HostRequestGate(LongSupplier clock,Sleeper sleeper) { this.clock=clock; this.sleeper=sleeper; }

    boolean await(String url,long interval) {
        String host=site(url);
        while(true) {
            BuildCancellation.check();
            long wait;
            synchronized(this) {
                long now=clock.getAsLong();
                if(cooldown.getOrDefault(host,0L)>now) return false;
                wait=next.getOrDefault(host,0L)-now;
                if(wait<=0) { next.put(host,now+interval); return true; }
            }
            try { sleeper.sleep(Math.min(wait,200)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("Web request cancelled"); }
        }
    }
    synchronized void coolDown(String url,long millis) {
        cooldown.merge(site(url),clock.getAsLong()+millis,Math::max);
    }
    static String site(String url) {
        String host=URI.create(url).getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("mcmod.cn") || host.endsWith(".mcmod.cn") ? "mcmod.cn" : host;
    }
    static long retryAfterMillis(String value,Instant now) {
        if(value==null || value.isBlank()) return 0;
        try { return Math.multiplyExact(Math.max(0,Long.parseLong(value.strip())),1000L); }
        catch(NumberFormatException e) {
            try { return Math.max(0,Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
            catch(java.time.DateTimeException | ArithmeticException ignored) { return 0; }
        } catch(ArithmeticException e) { return Duration.ofDays(1).toMillis(); }
    }
}
