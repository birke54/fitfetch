package org.example.fitfetch.fetching;

import org.example.fitfetch.ats.AtsName;

import java.time.Duration;

/**
 * Signals that a request to an ATS would have to wait longer than
 * {@code max-wait} for its turn, because the provider has asked for a pause
 * longer than that.
 *
 * <p>This is about the whole provider, not the slug being fetched: every
 * request to it would wait just as long. So the caller should end that ATS's
 * fetch cycle, keeping what it has fetched so far, rather than move on to the
 * next slug.
 *
 * <p>Deliberately not a {@link org.springframework.web.client.RestClientException}.
 * An ATS catches those per slug and carries on, which here would race through
 * every remaining slug failing each one instantly.
 *
 * @see AtsRateLimiter
 */
public class AtsThrottledException extends RuntimeException {

    private final AtsName ats;
    private final Duration wait;

    /**
     * @param ats  the provider that is paused
     * @param wait how long the request would have had to wait
     */
    public AtsThrottledException(AtsName ats, Duration wait) {
        super(ats.stringValue() + " is paused for another " + wait.toSeconds() + "s after a 429");
        this.ats = ats;
        this.wait = wait;
    }

    /** @return the provider that is paused */
    public AtsName ats() {
        return ats;
    }

    /** @return how long the request would have had to wait */
    public Duration waitRequired() {
        return wait;
    }
}
