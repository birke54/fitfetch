package org.example.fitfetch.location.records;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The model's schema-constrained answer, before it is mapped onto the pipeline's
 * own types.
 *
 * <p>Kept deliberately loose: {@code kind} and {@code specifierType} arrive as
 * strings rather than enums so that a model returning an unexpected value
 * produces a single unusable entry rather than failing deserialization of the
 * whole response.
 *
 * @param analysis  the model's working. Declared first in the schema on purpose:
 *                  generation is autoregressive, so a field written before the
 *                  answer acts as a scratchpad, which is as close to
 *                  chain-of-thought as strict structured output allows
 * @param locations one entry per location expression found in the label
 */
public record ExtractionPayload(
        String analysis,
        List<Item> locations
) {

    /**
     * One extracted location expression, as the model reports it.
     *
     * @param raw           the exact substring this came from
     * @param kind          {@code PLACE}, {@code REMOTE_BARE},
     *                      {@code REMOTE_SPECIFIER}, {@code SENTINEL} or
     *                      {@code UNPARSEABLE}
     * @param specifier     the geographic part, if any
     * @param specifierType {@code ADDRESS}, {@code CITY}, {@code STATE},
     *                      {@code COUNTRY} or {@code MACRO_REGION}
     *                      ({@code specifier_type})
     */
    public record Item(
            String raw,
            String kind,
            String specifier,
            @JsonProperty("specifier_type") String specifierType
    ) {
    }
}
