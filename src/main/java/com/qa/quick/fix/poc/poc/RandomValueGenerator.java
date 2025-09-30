package com.qa.quick.fix.poc.poc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles generation of random values for FIX message fields
 */
public class RandomValueGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(RandomValueGenerator.class);
    
    private final Random random;

    /**
     * Default constructor using ThreadLocalRandom for production
     */
    public RandomValueGenerator() {
        this.random = ThreadLocalRandom.current();
    }

    /**
     * Constructor with injectable Random for deterministic testing
     */
    public RandomValueGenerator(Random random) {
        this.random = random;
    }

    /**
     * Get random item from a list variable
     */
    @SuppressWarnings("unchecked")
    public String randomFrom(String varName, Map<String, Object> vars, String defaultValue) {
        if (vars == null) {
            logger.warn("Variables map is null, using default value: {}", defaultValue);
            return defaultValue;
        }

        Object varValue = vars.get(varName);
        if (varValue == null) {
            if (defaultValue != null) {
                logger.debug("Variable '{}' not found, using default: {}", varName, defaultValue);
                return defaultValue;
            }
            throw new TemplateResolutionException("Variable not found: " + varName, "randomFrom:" + varName, varName);
        }

        if (varValue instanceof List) {
            List<Object> list = (List<Object>) varValue;
            if (list.isEmpty()) {
                if (defaultValue != null) {
                    logger.debug("Variable '{}' is empty list, using default: {}", varName, defaultValue);
                    return defaultValue;
                }
                throw new TemplateResolutionException("Variable list is empty: " + varName, "randomFrom:" + varName, varName);
            }
            
            Object randomItem = list.get(random.nextInt(list.size()));
            return randomItem.toString();
        } else {
            throw new TemplateResolutionException("Variable is not a list: " + varName, "randomFrom:" + varName, varName);
        }
    }

    /**
     * Generate random price from a range variable
     */
    public BigDecimal randomPrice(String varName, Map<String, Object> vars, BigDecimal defaultValue) {
        return randomDecimal(varName, vars, defaultValue, 2);
    }

    /**
     * Generate random quantity from a range variable
     */
    public BigDecimal randomQuantity(String varName, Map<String, Object> vars, BigDecimal defaultValue) {
        return randomDecimal(varName, vars, defaultValue, 0);
    }

    /**
     * Generate random integer from a range variable
     */
    public int randomInt(String varName, Map<String, Object> vars, Integer defaultValue) {
        BigDecimal result = randomDecimal(varName, vars, 
            defaultValue != null ? BigDecimal.valueOf(defaultValue) : BigDecimal.ONE, 0);
        return result.intValue();
    }

    /**
     * Generate random decimal from a range variable
     */
    @SuppressWarnings("unchecked")
    private BigDecimal randomDecimal(String varName, Map<String, Object> vars, BigDecimal defaultValue, int scale) {
        if (vars == null) {
            logger.warn("Variables map is null, using default value: {}", defaultValue);
            return defaultValue;
        }

        Object varValue = vars.get(varName);
        if (varValue == null) {
            if (defaultValue != null) {
                logger.debug("Variable '{}' not found, using default: {}", varName, defaultValue);
                return defaultValue;
            }
            throw new TemplateResolutionException("Variable not found: " + varName, "randomDecimal:" + varName, varName);
        }

        if (varValue instanceof Map) {
            Map<String, Object> range = (Map<String, Object>) varValue;
            
            BigDecimal min = parseDecimal(range.get("min"), BigDecimal.ONE);
            BigDecimal max = parseDecimal(range.get("max"), BigDecimal.valueOf(100));
            
            if (min.compareTo(max) > 0) {
                throw new TemplateResolutionException(
                    String.format("Invalid range: min (%s) > max (%s) for variable: %s", min, max, varName),
                    "randomDecimal:" + varName, varName);
            }

            // Generate random decimal between min and max
            double randomDouble = min.doubleValue() + 
                (random.nextDouble() * (max.subtract(min).doubleValue()));
                
            return BigDecimal.valueOf(randomDouble).setScale(scale, RoundingMode.HALF_UP);
        } else {
            throw new TemplateResolutionException("Variable is not a range object: " + varName, "randomDecimal:" + varName, varName);
        }
    }

    /**
     * Parse object to BigDecimal with fallback
     */
    private BigDecimal parseDecimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException e) {
                logger.warn("Cannot parse '{}' as decimal, using default: {}", value, defaultValue);
                return defaultValue;
            }
        }
        
        logger.warn("Cannot convert '{}' to decimal, using default: {}", value, defaultValue);
        return defaultValue;
    }
}