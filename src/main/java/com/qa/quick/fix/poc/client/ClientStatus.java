
package com.qa.quick.fix.poc.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import quickfix.SessionID;

/**
 * Client status information
 */
@Data
@AllArgsConstructor
public class ClientStatus {
    private final String clientStreamName;
    private final boolean tradeSessionConnected;
    private final boolean quoteSessionConnected;
    private final SessionID tradeSessionId;
    private final SessionID quoteSessionId;
    
    @Override
    public String toString() {
        return String.format("ClientStatus{name='%s', trade=%s, quote=%s}", 
                           clientStreamName, tradeSessionConnected, quoteSessionConnected);
    }
}