
package com.qa.quick.fix.poc.client;

import quickfix.Message;
import quickfix.SessionID;

public interface MessageListener {
    void onMessage(SessionID sessionId, Message message);
}