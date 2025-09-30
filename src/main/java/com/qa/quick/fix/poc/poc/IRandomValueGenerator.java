package com.qa.quick.fix.poc.poc;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Abstraction for random value generation to make it injectable and testable.
 */
public interface IRandomValueGenerator {
    String randomFrom(String varName, Map<String, Object> vars, String defaultValue);

    BigDecimal randomPrice(String varName, Map<String, Object> vars, BigDecimal defaultValue);

    BigDecimal randomQuantity(String varName, Map<String, Object> vars, BigDecimal defaultValue);

    int randomInt(String varName, Map<String, Object> vars, Integer defaultValue);
}

