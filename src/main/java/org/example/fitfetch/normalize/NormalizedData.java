package org.example.fitfetch.normalize;

import java.util.List;
import java.util.Objects;

/**
 * What normalization extracts from one job description.
 *
 * @param seniority the level the job is pitched at
 * @param signals   its requirements, never empty: a description with nothing to
 *                  extract has no usable answer
 */
public record NormalizedData(Seniority seniority, List<Signal> signals) {

    public NormalizedData {
        Objects.requireNonNull(seniority, "seniority");
        if (signals == null || signals.isEmpty()) {
            throw new IllegalArgumentException("signals must not be empty");
        }
        signals = List.copyOf(signals);
    }
}
