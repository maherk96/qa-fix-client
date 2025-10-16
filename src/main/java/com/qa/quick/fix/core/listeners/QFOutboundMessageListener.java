package com.qa.quick.fix.core.listeners;

import quickfix.Message;
import quickfix.SessionID;

/**
 * Interface for listening to outgoing FIX application messages. 
 * Implementations of this interface can process messages sent from a QuickFIX/J session.
 */
public interface QFOutboundMessageListener {

    /**
     * Called when an outgoing FIX application message is sent.
     *
     * @param sessionId The ID of the QuickFIX/J session from which the message was sent.
     * @param message   The QuickFIX/J {@link Message} object representing the outgoing application message.
     */
    void onOutgoingMessage(SessionID sessionId, Message message);
}