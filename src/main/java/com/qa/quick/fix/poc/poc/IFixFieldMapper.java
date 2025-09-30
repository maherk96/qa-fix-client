package com.qa.quick.fix.poc.poc;

import quickfix.Message;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Abstraction for mapping resolved values to QuickFIX/J fields.
 * Enables plugging different implementations per FIX version or venue.
 */
public interface IFixFieldMapper {

    void registerField(String tag, BiConsumer<Message, Object> mapper);

    void setField(Message message, String tag, Object value);

    void setAllFields(Message message, Map<String, Object> fields);

    boolean hasMapper(String tag);
}

