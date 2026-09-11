package org.example.fitfetch.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.example.fitfetch.ats.AtsName;

/**
 * JPA converter that persists {@link AtsName} as its
 * {@link AtsName#stringValue() canonical string} (e.g. {@code "Greenhouse"})
 * rather than the enum constant name or ordinal.
 *
 * <p>Applied to {@link FetchedJob#getAts()}; keeps the {@code ats_name} column
 * value stable and human-readable, decoupled from the enum's Java name.
 */
@Converter
public class AtsNameConverter implements AttributeConverter<AtsName, String> {

    @Override
    public String convertToDatabaseColumn(AtsName attribute) {
        return attribute == null ? null : attribute.stringValue();
    }

    @Override
    public AtsName convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AtsName.fromStringValue(dbData);
    }
}
