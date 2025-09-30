package com.qa.quick.fix.poc.poc;// ===== FixFieldMapper.java =====
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;
import quickfix.field.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Maps FIX tags to QuickFIX/J fields using a registry pattern
 */
public class FixFieldMapper implements IFixFieldMapper {
    
    private static final Logger logger = LoggerFactory.getLogger(FixFieldMapper.class);
    private static final DateTimeFormatter FIX_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
    
    private final Map<String, BiConsumer<Message, Object>> fieldMappers;

    public FixFieldMapper() {
        this.fieldMappers = new HashMap<>();
        initializeFieldMappers();
    }

    /**
     * Initialize the field mapping registry
     */
    private void initializeFieldMappers() {
        // Standard FIX 4.4 fields
        registerField(FixTag.ACCOUNT.getTag(), this::setAccount);
        registerField(FixTag.CLORD_ID.getTag(), this::setClOrdID);
        registerField(FixTag.EXEC_ID.getTag(), this::setExecID);
        registerField(FixTag.ORDER_QTY.getTag(), this::setOrderQty);
        registerField(FixTag.ORD_STATUS.getTag(), this::setOrdStatus);
        registerField(FixTag.ORD_TYPE.getTag(), this::setOrdType);
        registerField(FixTag.PRICE.getTag(), this::setPrice);
        registerField(FixTag.SIDE.getTag(), this::setSide);
        registerField(FixTag.SYMBOL.getTag(), this::setSymbol);
        registerField(FixTag.TIME_IN_FORCE.getTag(), this::setTimeInForce);
        registerField(FixTag.TRANSACT_TIME.getTag(), this::setTransactTime);
        registerField(FixTag.EXEC_TYPE.getTag(), this::setExecType);
        
        logger.debug("Initialized {} field mappers", fieldMappers.size());
    }

    /**
     * Register a new field mapper
     */
    @Override
    public void registerField(String tag, BiConsumer<Message, Object> mapper) {
        fieldMappers.put(tag, mapper);
        logger.debug("Registered field mapper for tag: {}", tag);
    }

    /**
     * Set a field on a FIX message
     */
    @Override
    public void setField(Message message, String tag, Object value) {
        if (value == null) {
            logger.debug("Skipping null value for tag: {}", tag);
            return;
        }

        BiConsumer<Message, Object> mapper = fieldMappers.get(tag);
        if (mapper != null) {
            try {
                mapper.accept(message, value);
                logger.debug("Set field {} = {}", tag, value);
            } catch (Exception e) {
                logger.error("Error setting field {} with value '{}': {}", tag, value, e.getMessage());
                // Fallback to setString
                setFieldAsString(message, tag, value);
            }
        } else {
            logger.warn("No specific mapper for tag {}, using string fallback", tag);
            setFieldAsString(message, tag, value);
        }
    }

    /**
     * Set all fields from a map
     */
    @Override
    public void setAllFields(Message message, Map<String, Object> fields) {
        fields.forEach((tag, value) -> setField(message, tag, value));
    }

    /**
     * Fallback method to set field as string
     */
    private void setFieldAsString(Message message, String tag, Object value) {
        try {
            int tagInt = Integer.parseInt(tag);
            message.setString(tagInt, value.toString());
            logger.debug("Set field {} as string = {}", tag, value);
        } catch (NumberFormatException e) {
            logger.error("Invalid tag format: {}", tag);
        } catch (Exception e) {
            logger.error("Failed to set field {} as string: {}", tag, e.getMessage());
        }
    }

    // Individual field setters
    private void setAccount(Message message, Object value) {
        message.setField(new Account(value.toString()));
    }

    private void setClOrdID(Message message, Object value) {
        message.setField(new ClOrdID(value.toString()));
    }

    private void setExecID(Message message, Object value) {
        message.setField(new ExecID(value.toString()));
    }

    private void setOrderQty(Message message, Object value) {
        BigDecimal qty = convertToBigDecimal(value);
        message.setField(new OrderQty(qty.doubleValue()));
    }

    private void setOrdStatus(Message message, Object value) {
        char status = getCharValue(value);
        message.setField(new OrdStatus(status));
    }

    private void setOrdType(Message message, Object value) {
        char type = getCharValue(value);
        message.setField(new OrdType(type));
    }

    private void setPrice(Message message, Object value) {
        BigDecimal price = convertToBigDecimal(value);
        message.setField(new Price(price.doubleValue()));
    }

    private void setSide(Message message, Object value) {
        char side = getCharValue(value);
        message.setField(new Side(side));
    }

    private void setSymbol(Message message, Object value) {
        message.setField(new Symbol(value.toString()));
    }

    private void setTimeInForce(Message message, Object value) {
        char tif = getCharValue(value);
        message.setField(new TimeInForce(tif));
    }

    private void setTransactTime(Message message, Object value) {
        LocalDateTime dateTime = convertToLocalDateTime(value);
        message.setField(new TransactTime(dateTime));
    }

    private void setExecType(Message message, Object value) {
        char execType = getCharValue(value);
        message.setField(new ExecType(execType));
    }

    // Type conversion utilities
    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cannot convert to BigDecimal: " + value);
            }
        }
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + value);
    }

    private char getCharValue(Object value) {
        if (value instanceof Character) {
            return (Character) value;
        }
        String str = value.toString();
        if (str.length() != 1) {
            throw new IllegalArgumentException("Expected single character, got: " + str);
        }
        return str.charAt(0);
    }

    private LocalDateTime convertToLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof String) {
            String str = (String) value;
            
            // Try to parse ISO format first
            try {
                return LocalDateTime.parse(str);
            } catch (Exception e) {
                // Try FIX format: yyyyMMdd-HH:mm:ss.SSS
                try {
                    return LocalDateTime.parse(str, FIX_DATE_TIME);
                } catch (Exception e2) {
                    logger.warn("Cannot parse datetime '{}', using current time", str);
                    return LocalDateTime.now();
                }
            }
        }
        
        // Default to current time
        return LocalDateTime.now();
    }

    /**
     * Get all registered field tags
     */
    public java.util.Set<String> getRegisteredTags() {
        return java.util.Collections.unmodifiableSet(fieldMappers.keySet());
    }

    /**
     * Check if a tag has a registered mapper
     */
    @Override
    public boolean hasMapper(String tag) {
        return fieldMappers.containsKey(tag);
    }
}
