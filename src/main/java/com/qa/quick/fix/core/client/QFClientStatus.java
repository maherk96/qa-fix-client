package com.qa.quick.fix.core.client;

import quickfix.SessionID;

/** Client status information */
public record QFClientStatus(
    String clientStreamName,
    boolean tradeSessionConnected,
    boolean quoteSessionConnected,
    SessionID tradeSessionId,
    SessionID quoteSessionId) {

  @Override
  public String toString() {
    return String.format(
        "ClientStatus{name='%s', trade=%s, quote=%s}",
        clientStreamName, tradeSessionConnected, quoteSessionConnected);
  }
}
