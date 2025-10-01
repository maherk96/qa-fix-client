package com.qa.quick.fix.poc.poc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Generic IFixFieldMapper that sets any tag by formatting the value to a FIX string
 * and using Message#setString(tag, value). Allows per-tag overrides via registerField.
 *
 * Trade-off: you lose compile-time type specificity but gain simplicity and coverage
 * for arbitrary tags, provided values are properly formatted.
 */
public class GenericFixFieldMapper implements IFixFieldMapper {

    private static final Logger log = LoggerFactory.getLogger(GenericFixFieldMapper.class);

    private final Map<String, BiConsumer<Message, Object>> overrides = new HashMap<>();

    @Override
    public void registerField(String tag, BiConsumer<Message, Object> mapper) {
        overrides.put(tag, mapper);
    }

    @Override
    public void setField(Message message, String tag, Object value) {
        if (value == null) {
            log.debug("Skipping null value for tag {}", tag);
            return;
        }

        BiConsumer<Message, Object> override = overrides.get(tag);
        if (override != null) {
            override.accept(message, value);
            return;
        }

        try {
            int tagInt = Integer.parseInt(tag);
            String fixValue = FixValueFormatter.toFixString(value);
            message.setString(tagInt, fixValue);
            log.debug("Set {}={} (generic)", tag, fixValue);
        } catch (NumberFormatException nfe) {
            log.error("Invalid tag '{}': not an integer", tag);
        } catch (Exception e) {
            log.error("Failed to set tag {} with value {}: {}", tag, value, e.getMessage());
        }
    }

    @Override
    public void setAllFields(Message message, Map<String, Object> fields) {
        fields.forEach((tag, val) -> setField(message, tag, val));
    }

    @Override
    public boolean hasMapper(String tag) {
        return overrides.containsKey(tag);
    }
}

