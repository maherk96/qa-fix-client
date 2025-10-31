package com.qa.quick.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.quick.fix.cfg.*;
import com.qa.quick.fix.core.client.QFConnector;
import com.qa.quick.fix.exceptions.QFSessionException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import quickfix.ConfigError;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.MsgType;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("send")
@DisplayName("QFConnector send and awaitConnected integration tests")
class QFConnectorSendAndAwaitIT {

  private static final String CLIENT = "SEND_CLIENT";
  private static final int TRADE_PORT = 9886;

  private TestFixAcceptor acceptor;
  private QFConnector connector;

  private QFConnector tradeOnlyConnector(int port, PortsConfiguration ports) {
    CommonSettings common = new CommonSettings();
    common.setBeginString("FIX.4.4");
    common.setTargetCompID("TRADE_SERVER");
    common.setConnectionType("initiator");
    common.setReconnectInterval("5");
    common.setHeartBtInt("30");
    common.setStartTime("00:00:00");
    common.setEndTime("00:00:00");
    common.setFileStorePath("target/test-data/send");
    common.setUseDataDictionary("N");
    common.setSlf4jLogHeartbeats("N");

    SessionConfig trade = new SessionConfig();
    trade.setSenderCompID(CLIENT + "_TRADE");
    trade.setTargetCompID("TRADE_SERVER");

    ClientDefinition def = new ClientDefinition();
    def.setTradeSession(trade);

    ConnectionDetails tradeConn = new ConnectionDetails();
    tradeConn.setSocketConnectHost("localhost");
    tradeConn.setSocketConnectPort(String.valueOf(port));

    ConnectionEnvironment env = new ConnectionEnvironment();
    env.setTrade(tradeConn);

    return new QFConnector(CLIENT, common, def, env, ports, null, null, null);
  }

  @BeforeEach
  void setup() throws Exception {
    // Matching acceptor session for successful send
    SessionID tradeSession = new SessionID("FIX.4.4", "TRADE_SERVER", CLIENT + "_TRADE");
    acceptor = new TestFixAcceptor(tradeSession, TRADE_PORT);
    acceptor.start();
    Thread.sleep(150);
  }

  @AfterEach
  void cleanup() {
    if (connector != null) {
      try {
        connector.close();
      } catch (Exception ignored) {
      }
    }
    if (acceptor != null) {
      acceptor.stop();
    }
  }

  @Test
  @Order(1)
  @DisplayName("sendTradeMessage: sends app message when connected")
  void sendTradeMessage_success_whenConnected() throws Exception {
    connector = tradeOnlyConnector(TRADE_PORT, null);
    connector.start();
    assertThat(connector.waitForConnection(10, TimeUnit.SECONDS)).isTrue();

    int before = acceptor.getFromAppCount().get();

    Message app = new Message();
    app.getHeader().setString(MsgType.FIELD, MsgType.NEWS); // application message
    connector.sendTradeMessage(app);

    // Allow message to propagate
    Thread.sleep(300);

    assertThat(acceptor.getFromAppCount().get()).isGreaterThan(before);
  }

  @Test
  @Order(2)
  @DisplayName("sendTradeMessage before start: throws not configured")
  void sendTradeMessage_beforeStart_throwsNotConfigured() {
    connector = tradeOnlyConnector(TRADE_PORT, null);

    Message app = new Message();
    app.getHeader().setString(MsgType.FIELD, MsgType.NEWS);

    assertThatThrownBy(() -> connector.sendTradeMessage(app))
        .isInstanceOf(QFSessionException.class)
        .hasMessageContaining("not configured or initialized");
  }

  @Test
  @Order(3)
  @DisplayName("sendTradeMessage after start but not connected: throws not connected")
  void sendTradeMessage_afterStart_notConnected_throws() throws ConfigError, InterruptedException {
    // Connect to non-existent port; onCreate sets sessionId but not logged on
    connector = tradeOnlyConnector(19999, null);
    connector.start();
    // Small delay to ensure onCreate ran
    Thread.sleep(100);

    Message app = new Message();
    app.getHeader().setString(MsgType.FIELD, MsgType.NEWS);

    assertThatThrownBy(() -> connector.sendTradeMessage(app))
        .isInstanceOf(QFSessionException.class)
        .hasMessageContaining("not connected");
  }

  @Test
  @Order(4)
  @DisplayName("awaitConnected: returns false before start and true after connect")
  void awaitConnected_behavesAsExpected() throws Exception {
    connector = tradeOnlyConnector(TRADE_PORT, null);

    // before start
    assertThat(connector.awaitConnected(Duration.ofMillis(200))).isFalse();

    // after start
    connector.start();
    assertThat(connector.awaitConnected(Duration.ofSeconds(10))).isTrue();
  }

  @Test
  @Order(5)
  @DisplayName("PortsConfiguration overrides ConnectionDetails port")
  void portsConfiguration_overridesPort() throws Exception {
    // Run acceptor on TRADE_PORT; feed wrong port in ConnectionDetails but correct in
    // PortsConfiguration
    PortsConfiguration ports = new PortsConfiguration();
    ports.setClients(
        Collections.singletonList(
            new ClientPortInfo(CLIENT + "_TRADE", String.valueOf(TRADE_PORT), null)));

    connector = tradeOnlyConnector(19998, ports); // wrong port in ConnectionDetails
    connector.start();
    assertThat(connector.waitForConnection(10, TimeUnit.SECONDS)).isTrue();
  }
}
