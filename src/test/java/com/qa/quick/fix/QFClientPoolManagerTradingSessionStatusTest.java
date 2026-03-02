package com.qa.quick.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.qa.quick.fix.cfg.ClientDefinition;
import com.qa.quick.fix.cfg.ConnectionDetails;
import com.qa.quick.fix.cfg.ConnectionEnvironment;
import com.qa.quick.fix.cfg.FixClientConfiguration;
import com.qa.quick.fix.cfg.SessionConfig;
import com.qa.quick.fix.core.listeners.QFSessionEventListener;
import com.qa.quick.fix.core.pool.QFClientPoolManager;
import com.qa.quick.fix.exceptions.QFClientPoolException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.TradSesReqID;
import quickfix.field.TradingSessionID;

/**
 * Unit tests for the opt-in TradingSessionStatusRequest (35=g) feature on logon.
 *
 * <p>Usage recap:
 *
 * <pre>{@code
 * QFClientPoolManager pool = new QFClientPoolManager(config, env, clients)
 *     .withTradingSessionStatusOnLogon(true);   // opt in
 * pool.startAll();
 * }</pre>
 *
 * Callers that do NOT call {@code withTradingSessionStatusOnLogon(true)} see zero behaviour change.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QFClientPoolManager – TradingSessionStatusRequest on logon")
class QFClientPoolManagerTradingSessionStatusTest {

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Builds a pool manager with the minimal configuration needed to pass constructor validation
   * without touching the file system or starting any real FIX connection.
   */
  private QFClientPoolManager buildManager(String clientName) throws QFClientPoolException {
    SessionConfig trade = new SessionConfig("SENDER", "TARGET");

    ClientDefinition clientDef = new ClientDefinition();
    clientDef.setTradeSession(trade);

    ConnectionEnvironment connEnv = new ConnectionEnvironment();
    connEnv.setTrade(new ConnectionDetails("localhost", "9999"));

    FixClientConfiguration config = new FixClientConfiguration();
    config.setConnections(Map.of("TEST_ENV", connEnv));
    config.setClients(Map.of(clientName, clientDef));

    return new QFClientPoolManager(config, "TEST_ENV", Set.of(clientName));
  }

  /**
   * Reflectively invokes the private {@code wrapWithTradingSessionStatus} method so we can test
   * the wrapper's behaviour in isolation without starting a real connector.
   */
  private QFSessionEventListener wrap(
      QFClientPoolManager manager, QFSessionEventListener delegate, String clientName)
      throws Exception {
    Method method =
        QFClientPoolManager.class.getDeclaredMethod(
            "wrapWithTradingSessionStatus", QFSessionEventListener.class, String.class);
    method.setAccessible(true);
    return (QFSessionEventListener) method.invoke(manager, delegate, clientName);
  }

  // ── tests ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("withTradingSessionStatusOnLogon is fluent – returns the same manager instance")
  void fluentSetter_returnsSameInstance() throws Exception {
    QFClientPoolManager manager = buildManager("CLIENT_A");
    assertThat(manager.withTradingSessionStatusOnLogon(true)).isSameAs(manager);
    assertThat(manager.withTradingSessionStatusOnLogon(false)).isSameAs(manager);
  }

  @Test
  @DisplayName("Flag OFF (default): delegate fires, Session.sendToTarget is never called")
  void flagDisabled_delegateFires_noSendToTarget(@Mock QFSessionEventListener delegate)
      throws Exception {
    // flag defaults to false — do NOT call withTradingSessionStatusOnLogon
    QFClientPoolManager manager = buildManager("CLIENT_A");
    SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
    QFSessionEventListener wrapped = wrap(manager, delegate, "CLIENT_A");

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      wrapped.onLogon(sessionId);

      verify(delegate).onLogon(sessionId);
      mockedSession.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("Flag ON: delegate fires first, then Session.sendToTarget is called exactly once")
  void flagEnabled_delegateCalledFirst_thenSendToTarget(@Mock QFSessionEventListener delegate)
      throws Exception {
    QFClientPoolManager manager =
        buildManager("CLIENT_A").withTradingSessionStatusOnLogon(true);
    SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
    QFSessionEventListener wrapped = wrap(manager, delegate, "CLIENT_A");

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession
          .when(() -> Session.sendToTarget(any(Message.class), eq(sessionId)))
          .thenReturn(true);

      wrapped.onLogon(sessionId);

      // delegate must be called before sendToTarget
      InOrder order = inOrder(delegate);
      order.verify(delegate).onLogon(sessionId);
      mockedSession.verify(() -> Session.sendToTarget(any(Message.class), eq(sessionId)));
    }
  }

  @Test
  @DisplayName("Flag ON: sent message carries non-blank TradSesReqID and correct TradingSessionID")
  void flagEnabled_messageFieldsAreCorrect(@Mock QFSessionEventListener delegate)
      throws Exception {
    QFClientPoolManager manager =
        buildManager("CLIENT_A").withTradingSessionStatusOnLogon(true);
    // Use distinct comp IDs so the "SENDER->TARGET" assertion is unambiguous
    SessionID sessionId = new SessionID("FIX.4.4", "MY_SENDER", "MY_TARGET");
    QFSessionEventListener wrapped = wrap(manager, delegate, "CLIENT_A");

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession
          .when(() -> Session.sendToTarget(messageCaptor.capture(), eq(sessionId)))
          .thenReturn(true);

      wrapped.onLogon(sessionId);

      Message sent = messageCaptor.getValue();
      // Tag 335 – TradSesReqID: must be a non-blank unique string (UUID)
      assertThat(sent.isSetField(TradSesReqID.FIELD)).isTrue();
      assertThat(sent.getString(TradSesReqID.FIELD)).isNotBlank();
      // Tag 336 – TradingSessionID: formatted as senderCompID->targetCompID
      assertThat(sent.isSetField(TradingSessionID.FIELD)).isTrue();
      assertThat(sent.getString(TradingSessionID.FIELD)).isEqualTo("MY_SENDER->MY_TARGET");
    }
  }

  @Test
  @DisplayName("Flag ON: two logons produce two distinct TradSesReqID values (UUID uniqueness)")
  void flagEnabled_eachLogonGetsUniqueReqId(@Mock QFSessionEventListener delegate)
      throws Exception {
    QFClientPoolManager manager =
        buildManager("CLIENT_A").withTradingSessionStatusOnLogon(true);
    SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
    QFSessionEventListener wrapped = wrap(manager, delegate, "CLIENT_A");

    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession
          .when(() -> Session.sendToTarget(captor.capture(), eq(sessionId)))
          .thenReturn(true);

      wrapped.onLogon(sessionId);
      wrapped.onLogon(sessionId);

      assertThat(captor.getAllValues()).hasSize(2);
      String id1 = captor.getAllValues().get(0).getString(TradSesReqID.FIELD);
      String id2 = captor.getAllValues().get(1).getString(TradSesReqID.FIELD);
      assertThat(id1).isNotEqualTo(id2);
    }
  }

  @Test
  @DisplayName("Flag ON: SessionNotFound is caught and swallowed — caller sees no exception")
  void flagEnabled_sessionNotFound_isSwallowed(@Mock QFSessionEventListener delegate)
      throws Exception {
    QFClientPoolManager manager =
        buildManager("CLIENT_A").withTradingSessionStatusOnLogon(true);
    SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
    QFSessionEventListener wrapped = wrap(manager, delegate, "CLIENT_A");

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession
          .when(() -> Session.sendToTarget(any(Message.class), any(SessionID.class)))
          .thenThrow(new SessionNotFound("session gone"));

      // Must not propagate — only a WARN log is expected
      assertThatCode(() -> wrapped.onLogon(sessionId)).doesNotThrowAnyException();
      // Delegate was still called before the failed send
      verify(delegate).onLogon(sessionId);
    }
  }

  @Test
  @DisplayName("Flag ON with null delegate: no NullPointerException, sendToTarget still fires")
  void flagEnabled_nullDelegate_noNpe() throws Exception {
    QFClientPoolManager manager =
        buildManager("CLIENT_A").withTradingSessionStatusOnLogon(true);
    SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
    QFSessionEventListener wrapped = wrap(manager, null /* no delegate */, "CLIENT_A");

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession
          .when(() -> Session.sendToTarget(any(Message.class), eq(sessionId)))
          .thenReturn(true);

      assertThatCode(() -> wrapped.onLogon(sessionId)).doesNotThrowAnyException();
      mockedSession.verify(() -> Session.sendToTarget(any(Message.class), eq(sessionId)));
    }
  }

  @Test
  @DisplayName("onLogout and onReject always delegate and never trigger sendToTarget")
  void onLogout_and_onReject_delegateOnly_noSendToTarget(@Mock QFSessionEventListener delegate)
      throws Exception {
    // Flag is ON — must have zero effect for non-logon events
    QFClientPoolManager manager =
        buildManager("CLIENT_A").withTradingSessionStatusOnLogon(true);
    SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
    QFSessionEventListener wrapped = wrap(manager, delegate, "CLIENT_A");

    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      wrapped.onLogout(sessionId);
      wrapped.onReject(sessionId, "bad message");

      verify(delegate).onLogout(sessionId);
      verify(delegate).onReject(sessionId, "bad message");
      mockedSession.verifyNoInteractions();
    }
  }
}
