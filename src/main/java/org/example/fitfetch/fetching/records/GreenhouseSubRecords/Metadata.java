package org.example.fitfetch.fetching.records.GreenhouseSubRecords;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One custom metadata field from a Greenhouse job's {@code metadata} array.
 *
 * <p>These are board-defined key/value pairs; {@code value} is left as
 * {@link Object} because its runtime type depends on {@code valueType}
 * (for example a {@link String}, a number, a boolean, or a list for
 * {@code "multi_select"}).
 *
 * @param id        the metadata field's identifier
 * @param name      the field's display name
 * @param value     the field's value, whose concrete type is indicated by
 *                  {@code valueType}; may be {@code null}
 * @param valueType the Greenhouse value-type discriminator, e.g. {@code "short_text"},
 *                  {@code "single_select"}, {@code "yes_no"} ({@code value_type})
 */
public record Metadata(
        Long id,
        String name,
        Object value,
        @JsonProperty("value_type") String valueType
) {}