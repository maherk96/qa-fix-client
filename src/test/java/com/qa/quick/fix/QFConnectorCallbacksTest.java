package com.qa.quick.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.qa.quick.fix.cfg.ClientDefinition;
import com.qa.quick.fix.cfg.CommonSettings;
import com.qa.quick.fix.cfg.ConnectionEnvironment;
import com.qa.quick.fix.cfg.OtherSettings;
import com.qa.quick.fix.cfg.SessionConfig;
import com.qa.quick.fix.core.client.QFConnector;
import com.qa.quick.fix.core.listeners.QFInboundMessageListener;
import com.qa.quick.fix.core.listeners.QFOutboundMessageListener;
import com.qa.quick.fix.core.listeners.QFSessionEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.DefaultApplVerID;
import quickfix.field.DefaultCstmApplVerID;
import quickfix.field.MsgType;
import quickfix.field.Password;
import quickfix.field.Text;
import quickfix.field.Username;

@ExtendWith(MockitoExtension.class)
@DisplayName("QFConnector admin/app callbacks and listener behavior")
class QFConnectorCallbacksTest {

  private QFConnector newConnector(
      QFInboundMessageListener inbound,
      QFSessionEventListener sessionListener,
      QFOutboundMessageListener outbound) {
    CommonSettings common = new CommonSettings();
    common.setBeginString("FIX.4.4");
    common.setTargetCompID("TARGET");
    common.setConnectionType("initiator");
    common.setReconnectInterval("5");
    common.setHeartBtInt("30");
    common.setStartTime("00:00:00");
    common.setEndTime("00:00:00");
    common.setFileStorePath("target/test-data/callbacks");
    common.setUseDataDictionary("N");
    common.setSlf4jLogHeartbeats("N");

    // No sessions required for these unit-level callback tests
    ClientDefinition clientDef = new ClientDefinition();

    return new QFConnector(
        "CALLBACKS",
        common,
        clientDef,
        new ConnectionEnvironment(),
        null,
        inbound,
        sessionListener,
        outbound);
  }

  @Test
  @DisplayName("toAdmin enriches LOGON with OtherSettings fields")
  void toAdmin_enrichesLogon_withOtherSettings() throws Exception {
    CommonSettings common = new CommonSettings();
    common.setBeginString("FIX.4.4");
    common.setTargetCompID("TARGET");

    SessionConfig trade = new SessionConfig();
    trade.setSenderCompID("SENDER");
    trade.setTargetCompID("TARGET");

    OtherSettings other = new OtherSettings();
    other.setUsername("user1");
    other.setPassword("pass1");
    other.setDefaultApplVerID("9");
    other.setDefaultCstmApplVerID("xt.1");
    other.setSenderSubID("SUB_A");
    other.setTargetSubID("SUB_B");

    ClientDefinition def = new ClientDefinition();
    def.setTradeSession(trade);
    def.setOther(other);

    QFConnector connector =
        new QFConnector(
            "CALLBACKS", common, def, new ConnectionEnvironment(), null, null, null, null);

    Message logon = new Message();
    logon.getHeader().setString(MsgType.FIELD, MsgType.LOGON);

    SessionID sid = new SessionID("FIX.4.4", "SENDER", "TARGET");
    connector.toAdmin(logon, sid);

    assertThat(logon.isSetField(Username.FIELD)).isTrue();
    assertThat(logon.getString(Username.FIELD)).isEqualTo("user1");
    assertThat(logon.isSetField(Password.FIELD)).isTrue();
    assertThat(logon.getString(Password.FIELD)).isEqualTo("pass1");
    assertThat(logon.isSetField(DefaultApplVerID.FIELD)).isTrue();
    assertThat(logon.getString(DefaultApplVerID.FIELD)).isEqualTo("9");
    assertThat(logon.isSetField(DefaultCstmApplVerID.FIELD)).isTrue();
    assertThat(logon.getString(DefaultCstmApplVerID.FIELD)).isEqualTo("xt.1");
    assertThat(logon.isSetField(new quickfix.field.SenderSubID().getField())).isTrue();
    assertThat(logon.getString(new quickfix.field.SenderSubID().getField())).isEqualTo("SUB_A");
    assertThat(logon.isSetField(new quickfix.field.TargetSubID().getField())).isTrue();
    assertThat(logon.getString(new quickfix.field.TargetSubID().getField())).isEqualTo("SUB_B");
  }

  @Test
  @DisplayName("fromAdmin invokes onReject for REJECT and passes reason")
  void fromAdmin_invokesOnReject_withReason(@Mock QFSessionEventListener sessionListener)
      throws Exception {
    QFConnector connector = newConnector(null, sessionListener, null);

    Message reject = new Message();
    reject.getHeader().setString(MsgType.FIELD, MsgType.REJECT);
    reject.setString(Text.FIELD, "Bad message");

    SessionID sid = new SessionID("FIX.4.4", "SENDER", "TARGET");
    connector.fromAdmin(reject, sid);

    verify(sessionListener).onReject(sid, "Bad message");
  }

  @Test
  @DisplayName("fromAdmin without MsgType throws FieldNotFound")
  void fromAdmin_withoutMsgType_throwsFieldNotFound() {
    QFConnector connector = newConnector(null, null, null);
    Message msg = new Message(); // no MsgType in header
    SessionID sid = new SessionID("FIX.4.4", "SENDER", "TARGET");

    assertThatThrownBy(() -> connector.fromAdmin(msg, sid)).isInstanceOf(FieldNotFound.class);
  }

  @Test
  @DisplayName("toApp and fromApp invoke listeners and swallow exceptions")
  void toApp_fromApp_invokeListeners_andSwallowExceptions(
      @Mock QFInboundMessageListener inbound, @Mock QFOutboundMessageListener outbound) {
    QFSessionEventListener sessionListener =
        new QFSessionEventListener() {
          @Override
          public void onLogon(SessionID sessionId) {}

          @Override
          public void onLogout(SessionID sessionId) {}

          @Override
          public void onReject(SessionID sessionId, String reason) {}
        };

    QFConnector connector = newConnector(inbound, sessionListener, outbound);
    SessionID sid = new SessionID("FIX.4.4", "S", "T");
    Message appMsg = new Message();
    appMsg.getHeader().setString(MsgType.FIELD, MsgType.NEWS); // app message

    // Verify happy path does not throw and listeners are invoked
    assertDoesNotThrow(() -> connector.toApp(appMsg, sid));
    assertDoesNotThrow(() -> connector.fromApp(appMsg, sid));
    verify(outbound).onOutgoingMessage(sid, appMsg);
    verify(inbound).onMessage(sid, appMsg);

    // Listeners throwing shouldn't propagate
    doThrow(new RuntimeException("boom")).when(outbound).onOutgoingMessage(sid, appMsg);
    doThrow(new RuntimeException("boom")).when(inbound).onMessage(sid, appMsg);

    assertDoesNotThrow(() -> connector.toApp(appMsg, sid));
    assertDoesNotThrow(() -> connector.fromApp(appMsg, sid));
  }
}
