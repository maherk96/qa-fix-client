package com.qa.quick.fix.core.listeners;

import quickfix.Message;
import quickfix.SessionID;

/**
 * Interface for listening to incoming FIX application messages. 
 * Implementations of this interface can process messages received from a QuickFIX/J session.
 */
public interface QFInboundMessageListener {

    /**
     * Called when an incoming FIX application message is received.
     *
     * @param sessionId The ID of the QuickFIX/J session from which the message was received.
     * @param message   The QuickFIX/J {@link Message} object representing the incoming application message.
     */
    void onMessage(SessionID sessionId, Message message);
}