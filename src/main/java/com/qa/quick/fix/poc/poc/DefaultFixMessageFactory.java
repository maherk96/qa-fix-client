package com.qa.quick.fix.poc.poc;

import quickfix.Message;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

/**
 * Default FIX 4.4 message factory. Extend or replace for other versions.
 */
public final class DefaultFixMessageFactory implements FixMessageFactory {

    @Override
    public Message create(FixMessageType messageType) {
        return switch (messageType) {
            case NEW_ORDER_SINGLE -> new NewOrderSingle();
            case EXECUTION_REPORT -> new ExecutionReport();
            default -> throw new IllegalArgumentException("Unsupported message type: " + messageType);
        };
    }
}

