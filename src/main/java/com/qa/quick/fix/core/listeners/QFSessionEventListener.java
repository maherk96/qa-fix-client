package com.qa.quick.fix.core.listeners;

import quickfix.SessionID;

/**
 * Interface for listening to QuickFIX/J session-level events. Implementations of this interface can
 * react to events such as session logon, logout, and message rejection.
 */
public interface QFSessionEventListener {

  /**
   * Called when a QuickFIX/J session successfully logs on.
   *
   * @param sessionId The ID of the QuickFIX/J session that logged on.
   */
  void onLogon(SessionID sessionId);

  /**
   * Called when a QuickFIX/J session logs out.
   *
   * @param sessionId The ID of the QuickFIX/J session that logged out.
   */
  void onLogout(SessionID sessionId);

  /**
   * Called when an administrative message (e.g., a reject message) is received for a QuickFIX/J
   * session, indicating a rejection.
   *
   * @param sessionId The ID of the QuickFIX/J session that received the reject.
   * @param reason A string describing the reason for the rejection.
   */
  void onReject(SessionID sessionId, String reason);
}
