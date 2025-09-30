package com.qa.quick.fix.poc.poc;

import quickfix.Message;

/**
 * Factory for creating QuickFIX/J messages based on higher-level enums.
 * Allows easy extension for new FIX versions or venues.
 */
public interface FixMessageFactory {
    Message create(FixMessageType messageType);
}

