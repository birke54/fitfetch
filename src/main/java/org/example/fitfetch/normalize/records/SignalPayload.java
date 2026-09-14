package org.example.fitfetch.normalize.records;

import java.util.List;

/**
 * The model's answer as the schema shapes it, before it is checked.
 *
 * @param seniority one of the {@code Seniority} labels, or anything else if the
 *                  model strayed from the schema
 * @param signals   the extracted requirements
 */
public record SignalPayload(String seniority, List<Item> signals) {

    /**
     * @param classification one of the {@code SignalClassification} labels
     * @param text           the requirement as one sentence
     */
    public record Item(String classification, String text) {
    }
}
