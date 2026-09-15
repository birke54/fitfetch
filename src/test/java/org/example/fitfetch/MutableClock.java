package org.example.fitfetch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock a test moves by hand, for code that reads the time more than once. */
public final class MutableClock extends Clock {

    private Instant now;

    /** @param start the time the clock starts at */
    public MutableClock(Instant start) {
        this.now = start;
    }

    /** @param amount how far to move the clock forward */
    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("a MutableClock is always UTC");
    }
}
