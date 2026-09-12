package org.example.fitfetch.location;

import java.util.Objects;
import java.util.Optional;

/**
 * Turns an {@link ExtractedLocation} into a {@link LocationInput} by applying
 * the rules that are specific to <em>this</em> user: where they search from, and
 * which state they live in.
 *
 * <p>This is deliberately the only place that knows about the search origin. The
 * extraction step describes what a posting says; this decides what that means.
 * Keeping the split means the origin can be reconfigured &mdash; or the user can
 * move &mdash; by re-running a pure function, without re-prompting a model or
 * invalidating a single cached interpretation.
 *
 * <p>The rules, in the order the switch applies them:
 *
 * <ol>
 *   <li>A genuine address or city geocodes as itself.</li>
 *   <li>Bare {@code "Remote"} resolves to the origin, so it is always in range.</li>
 *   <li>{@code "Remote - X"} uses X, unless X is the United States, in which
 *       case the origin is used instead.</li>
 *   <li>Empty, {@code "N/A"} or a placeholder resolves to the origin.</li>
 *   <li>Anything unusable, including a specifier the {@link Gazetteer} rejects,
 *       becomes {@link Resolution#UNDEFINED} and never matches.</li>
 *   <li>A bare country with no remote marker is treated as rule 3 would treat it.</li>
 *   <li>A bare state with no remote marker follows the same shape one level down.</li>
 *   <li>{@code "Remote - X"} where X is the home state resolves to the origin,
 *       because those roles really are available here. Other US states resolve
 *       to their own centroid and fall outside the radius, which is correct:
 *       a remote-Connecticut role is not open to someone in Seattle.</li>
 * </ol>
 *
 * <p>Non-US countries resolving to national centroids is load-bearing rather
 * than incidental. Canada's centroid sits in Nunavut and India's in Madhya
 * Pradesh, so those rows fall outside any sane radius &mdash; which is the right
 * outcome, since work authorization makes the roles unavailable anyway.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class LocationPolicy {

    private final String origin;
    private final String homeState;

    /**
     * @param origin    the search origin, used verbatim as the geocode query for
     *                  every resolution that redirects there. Prefer a full
     *                  street address over a city name: it geocodes to distance
     *                  zero, so no radius setting can exclude a row that was
     *                  redirected here on purpose
     * @param homeState the user's state, as a postal abbreviation
     *                  ({@code "WA"}) or ISO 3166-2 code ({@code "US-WA"}); may
     *                  be {@code null} to disable rules 6b and 7
     */
    public LocationPolicy(String origin, String homeState) {
        this.origin = Objects.requireNonNull(origin, "origin");
        if (origin.isBlank()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
        this.homeState = homeState;
    }

    /**
     * Applies the policy to one extracted location.
     *
     * @param extracted one location expression as the extractor saw it
     * @return the resolved input; never {@code null}, and
     *         {@link Resolution#UNDEFINED} rather than an exception when the
     *         extraction cannot be used
     */
    public LocationInput apply(ExtractedLocation extracted) {
        Objects.requireNonNull(extracted, "extracted");
        String raw = extracted.raw() == null ? "" : extracted.raw();

        return switch (extracted.kind()) {
            case SENTINEL -> new LocationInput(raw, Resolution.EMPTY_DEFAULT, origin, null);
            case UNPARSEABLE -> LocationInput.undefined(raw);
            case REMOTE_BARE -> new LocationInput(raw, Resolution.REMOTE_BARE, origin, null);
            case REMOTE_SPECIFIER -> remoteWithSpecifier(raw, extracted);
            case PLACE -> barePlace(raw, extracted);
        };
    }

    /** Rules 3, 3b and 7: a remote marker carrying a geographic qualifier. */
    private LocationInput remoteWithSpecifier(String raw, ExtractedLocation extracted) {
        if (!extracted.hasSpecifier() || extracted.specifierType() == null) {
            // A remote marker whose qualifier the extractor could not pin down is
            // still a remote job; degrade to rule 2 rather than discarding it.
            return new LocationInput(raw, Resolution.REMOTE_BARE, origin, null);
        }
        String specifier = extracted.specifier();

        return switch (extracted.specifierType()) {
            case COUNTRY -> {
                Optional<String> code = Gazetteer.countryCode(specifier);
                if (code.isEmpty()) {
                    yield LocationInput.undefined(raw);           // rule 5: hallucination guard
                }
                yield Gazetteer.US.equals(code.get())
                        ? new LocationInput(raw, Resolution.REMOTE_IN_US, origin, Gazetteer.US)
                        : new LocationInput(raw, Resolution.REMOTE_ELSEWHERE, specifier, code.get());
            }
            case MACRO_REGION ->
                    new LocationInput(raw, Resolution.REMOTE_REGION, specifier, null);
            case STATE -> {
                Optional<String> state = Gazetteer.usStateCode(specifier);
                if (state.isEmpty()) {
                    yield LocationInput.undefined(raw);
                }
                yield Gazetteer.isHomeState(specifier, homeState)
                        ? new LocationInput(raw, Resolution.REMOTE_IN_US, origin, state.get())
                        : new LocationInput(raw, Resolution.REMOTE_ELSEWHERE, specifier, state.get());
            }
            case CITY, ADDRESS ->
                    new LocationInput(raw, Resolution.REMOTE_ELSEWHERE, specifier, null);
        };
    }

    /** Rules 1, 6 and 6b: a place named with no remote marker attached. */
    private LocationInput barePlace(String raw, ExtractedLocation extracted) {
        if (!extracted.hasSpecifier() || extracted.specifierType() == null) {
            return LocationInput.undefined(raw);
        }
        String specifier = extracted.specifier();

        return switch (extracted.specifierType()) {
            case COUNTRY -> {
                Optional<String> code = Gazetteer.countryCode(specifier);
                if (code.isEmpty()) {
                    yield LocationInput.undefined(raw);
                }
                yield Gazetteer.US.equals(code.get())
                        ? new LocationInput(raw, Resolution.COUNTRY_US, origin, Gazetteer.US)
                        : new LocationInput(raw, Resolution.COUNTRY_OTHER, specifier, code.get());
            }
            case STATE -> {
                Optional<String> state = Gazetteer.usStateCode(specifier);
                if (state.isEmpty()) {
                    yield LocationInput.undefined(raw);
                }
                // A bare home-state label means an onsite role somewhere the user
                // already is, so it belongs in range rather than at the state
                // centroid, which sits 100 miles from Seattle for Washington.
                yield Gazetteer.isHomeState(specifier, homeState)
                        ? new LocationInput(raw, Resolution.PLACE, origin, state.get())
                        : new LocationInput(raw, Resolution.STATE_OTHER, specifier, state.get());
            }
            // A bare macro-region is treated as rules 6 and 6b treat a bare
            // country or state: exactly as if a remote marker had been present.
            case MACRO_REGION ->
                    new LocationInput(raw, Resolution.REMOTE_REGION, specifier, null);
            case CITY, ADDRESS ->
                    new LocationInput(raw, Resolution.PLACE, specifier, null);
        };
    }
}
