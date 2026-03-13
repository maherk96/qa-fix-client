package com.qa.quick.fix.core.client;

import java.util.concurrent.ConcurrentHashMap;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * A {@link LogFactory} that delegates all logging to {@link SLF4JLogFactory} while also storing
 * each raw incoming FIX message in a {@link ConcurrentHashMap} keyed by {@link SessionID}, so that
 * {@code QFConnector.fromApp} can read the complete wire string before QuickFIX/J's parser discards
 * duplicate or unrecognised fields.
 *
 * <p><b>Why not a {@code ThreadLocal}?</b><br>
 * In QuickFIX/J, {@link Log#onIncoming(String)} is invoked on the MINA/NIO I/O thread, while
 * {@link quickfix.Application#fromApp} may be dispatched to a separate session-processing thread.
 * A {@code ThreadLocal} would silently return {@code null} in that case. Keying by {@link SessionID}
 * is safe because a single QuickFIX/J session processes messages sequentially — {@code onIncoming}
 * and the corresponding {@code fromApp} are never interleaved for the same session.
 */
class RawMessageCapturingLogFactory implements LogFactory {

  /**
   * Last raw incoming message per session. Written by the I/O thread via {@link
   * CapturingLog#onIncoming(String)}, read and removed by the session processing thread via {@link
   * #takeRawIncoming(SessionID)}.
   */
  private static final ConcurrentHashMap<SessionID, String> RAW_INCOMING =
      new ConcurrentHashMap<>();

  private final SLF4JLogFactory delegate;

  RawMessageCapturingLogFactory(SessionSettings settings) {
    this.delegate = new SLF4JLogFactory(settings);
  }

  @Override
  public Log create(SessionID sessionId) {
    return new CapturingLog(delegate.create(sessionId), sessionId);
  }

  /**
   * Returns and removes the raw incoming message stored for {@code sessionId}, or {@code null} if
   * none is present (e.g. called outside of a {@code fromApp} invocation or for an admin message
   * that was never forwarded to the application layer).
   */
  static String takeRawIncoming(SessionID sessionId) {
    return RAW_INCOMING.remove(sessionId);
  }

  private static final class CapturingLog implements Log {

    private final Log delegate;
    private final SessionID sessionId;

    CapturingLog(Log delegate, SessionID sessionId) {
      this.delegate = delegate;
      this.sessionId = sessionId;
    }

    @Override
    public void onIncoming(String message) {
      RAW_INCOMING.put(sessionId, message);
      delegate.onIncoming(message);
    }

    @Override
    public void onOutgoing(String message) {
      delegate.onOutgoing(message);
    }

    @Override
    public void onEvent(String text) {
      delegate.onEvent(text);
    }

    @Override
    public void onErrorEvent(String text) {
      delegate.onErrorEvent(text);
    }

    @Override
    public void clear() {
      delegate.clear();
    }
  }
}
