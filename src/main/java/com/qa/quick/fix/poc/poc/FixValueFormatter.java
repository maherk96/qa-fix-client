package com.qa.quick.fix.poc.poc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Utilities to format Java values to FIX-compliant string representations.
 * Avoids repeated formatter allocations and centralizes conversions.
 */
final class FixValueFormatter {
    private static final Logger log = LoggerFactory.getLogger(FixValueFormatter.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private FixValueFormatter() {}

    static String toFixString(Object value) {
        if (value == null) return "";

        if (value instanceof String) return (String) value;

        if (value instanceof Character) return String.valueOf((Character) value);

        if (value instanceof Boolean) return ((Boolean) value) ? "Y" : "N";

        if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();

        if (value instanceof Number) return new BigDecimal(((Number) value).toString()).toPlainString();

        if (value instanceof LocalDateTime) return ((LocalDateTime) value).format(TS);

        if (value instanceof LocalDate) return ((LocalDate) value).format(DATE);

        if (value instanceof LocalTime) return ((LocalTime) value).format(TIME);

        if (value instanceof Date) {
            LocalDateTime ldt = LocalDateTime.ofInstant(((Date) value).toInstant(), ZoneOffset.UTC);
            return ldt.format(TS);
        }

        String s = String.valueOf(value);
        if (s.isEmpty()) {
            log.debug("Value {} formatted to empty string", value.getClass().getSimpleName());
        }
        return s;
    }
}

