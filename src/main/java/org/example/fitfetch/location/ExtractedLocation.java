package org.example.fitfetch.location;

/**
 * One location expression as the extraction step sees it, before any policy is
 * applied.
 *
 * <p>A single {@code location.name} field can yield several of these: the
 * Greenhouse payload routinely carries lists, separated by semicolons, ampersands
 * or the word "or", and one observed string enumerates fifteen of them.
 *
 * <p>This record is the contract between extraction and {@link LocationPolicy}.
 * It describes the text only &mdash; it knows nothing about the search origin,
 * the home state, or which countries the user may work in.
 *
 * @param raw           the verbatim element this was extracted from, retained so
 *                      a later rule change can be replayed over stored rows
 * @param kind          what shape the expression takes
 * @param specifier     the geographic part, or {@code null} for
 *                      {@link LocationKind#REMOTE_BARE},
 *                      {@link LocationKind#SENTINEL} and
 *                      {@link LocationKind#UNPARSEABLE}
 * @param specifierType the granularity of {@code specifier}, or {@code null}
 *                      when there is no specifier
 */
public record ExtractedLocation(
        String raw,
        LocationKind kind,
        String specifier,
        SpecifierType specifierType
) {

    /** @return an extraction with no specifier, for the kinds that take none */
    public static ExtractedLocation of(String raw, LocationKind kind) {
        return new ExtractedLocation(raw, kind, null, null);
    }

    /** @return {@code true} if a non-blank specifier is present */
    public boolean hasSpecifier() {
        return specifier != null && !specifier.isBlank();
    }
}
