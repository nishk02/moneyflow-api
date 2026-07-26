package com.moneyflow.shared.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Converter(autoApply = true)
public class LocalDateConverter
        implements AttributeConverter<LocalDate, String> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String convertToDatabaseColumn(LocalDate date) {
        return date != null ? date.format(FORMATTER) : null;
    }

    @Override
    public LocalDate convertToEntityAttribute(String value) {
        return value != null ? LocalDate.parse(value, FORMATTER) : null;
    }
}