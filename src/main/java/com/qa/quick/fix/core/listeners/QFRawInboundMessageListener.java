package com.qa.quick.fix.core.listeners;

import quickfix.SessionID;

/**
 * Listener for raw incoming FIX message strings, called before QuickFIX/J parses the message into
 * a {@link quickfix.Message} object.
 *
 * <p>Use this when the server sends non-standard repeating groups without the required group-count
 * tag (e.g. multiple {@code 336=} fields in a Trading Session Status response with no preceding
 * {@code 386=}). In that case QuickFIX/J's parser only retains the first occurrence of each
 * duplicate tag; this listener receives the complete, unparsed wire string so nothing is lost.
 */
public interface QFRawInboundMessageListener {

  /**
   * Called with the full raw FIX message string as received from the wire, before any parsing.
   * Fields are delimited by the SOH character ({@code \u0001}).
   *
   * @param sessionId the session on which the message arrived
   * @param rawMessage the complete, unparsed FIX message string
   */
  void onRawMessage(SessionID sessionId, String rawMessage);
}
