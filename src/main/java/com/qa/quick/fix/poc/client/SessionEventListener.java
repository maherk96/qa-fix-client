
package com.qa.quick.fix.poc.client;

import quickfix.SessionID;

public interface SessionEventListener {
    void onLogon(SessionID sessionId);
    void onLogout(SessionID sessionId);
    void onReject(SessionID sessionId, String reason);
    void onError(SessionID sessionId, String error);
}