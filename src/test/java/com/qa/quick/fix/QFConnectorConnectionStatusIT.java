package com.qa.quick.fix;

import com.qa.quick.fix.cfg.ClientDefinition;
import com.qa.quick.fix.cfg.CommonSettings;
import com.qa.quick.fix.cfg.ConnectionDetails;
import com.qa.quick.fix.cfg.ConnectionEnvironment;
import com.qa.quick.fix.cfg.SessionConfig;
import com.qa.quick.fix.core.client.QFClientStatus;
import com.qa.quick.fix.core.client.QFConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import quickfix.SessionID;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for QFConnector connection status reporting.
 * Tests various connection state checking methods.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("critical")
@Tag("connection-status")
@DisplayName("QFConnector Connection Status Integration Tests")
class QFConnectorConnectionStatusIT {

    private TestFixAcceptor tradeAcceptor;
    private TestFixAcceptor quoteAcceptor;
    private QFConnector connector;

    private static final String CLIENT_NAME = "TEST_CLIENT";
    private static final int TRADE_PORT = 9876;
    private static final int QUOTE_PORT = 9877;

    @BeforeEach
    void setup() throws Exception {
        // Trade acceptor setup
        SessionID tradeSession = new SessionID("FIX.4.4", "TRADE_SERVER", CLIENT_NAME + "_TRADE");
        tradeAcceptor = new TestFixAcceptor(tradeSession, TRADE_PORT);
        tradeAcceptor.start();

        // Quote acceptor setup
        SessionID quoteSession = new SessionID("FIX.4.4", "QUOTE_SERVER", CLIENT_NAME + "_QUOTE");
        quoteAcceptor = new TestFixAcceptor(quoteSession, QUOTE_PORT);
        quoteAcceptor.start();

        // Brief pause to ensure acceptors are ready
        Thread.sleep(200);
    }

    @AfterEach
    void teardown() {
        if (connector != null) {
            try {
                connector.close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }

        if (tradeAcceptor != null) {
            tradeAcceptor.stop();
        }

        if (quoteAcceptor != null) {
            quoteAcceptor.stop();
        }
    }

    /**
     * Creates common settings shared across test configurations.
     */
    private CommonSettings createCommonSettings() {
        CommonSettings settings = new CommonSettings();
        settings.setBeginString("FIX.4.4");
        settings.setConnectionType("initiator");
        settings.setReconnectInterval("5");
        settings.setHeartBtInt("30");
        settings.setStartTime("00:00:00");
        settings.setEndTime("00:00:00");
        settings.setFileStorePath("target/test-data/connector-status");
        settings.setUseDataDictionary("N");
        settings.setSlf4jLogHeartbeats("N");
        return settings;
    }

    /**
     * Creates a connector with only a trade session configured.
     */
    private QFConnector createTradeOnlyConnector() {
        CommonSettings commonSettings = createCommonSettings();
        commonSettings.setTargetCompID("TRADE_SERVER");

        SessionConfig tradeSession = new SessionConfig();
        tradeSession.setSenderCompID(CLIENT_NAME + "_TRADE");
        tradeSession.setTargetCompID("TRADE_SERVER");

        ClientDefinition clientDef = new ClientDefinition();
        clientDef.setTradeSession(tradeSession);

        ConnectionDetails tradeConnection = new ConnectionDetails();
        tradeConnection.setSocketConnectHost("localhost");
        tradeConnection.setSocketConnectPort(String.valueOf(TRADE_PORT));

        ConnectionEnvironment connEnv = new ConnectionEnvironment();
        connEnv.setTrade(tradeConnection);

        return new QFConnector(
            CLIENT_NAME,
            commonSettings,
            clientDef,
            connEnv,
            null, null, null, null
        );
    }

    /**
     * Creates a connector with both trade and quote sessions configured.
     */
    private QFConnector createDualSessionConnector() {
        CommonSettings commonSettings = createCommonSettings();
        commonSettings.setTargetCompID("TRADE_SERVER");

        SessionConfig tradeSession = new SessionConfig();
        tradeSession.setSenderCompID(CLIENT_NAME + "_TRADE");
        tradeSession.setTargetCompID("TRADE_SERVER");

        SessionConfig quoteSession = new SessionConfig();
        quoteSession.setSenderCompID(CLIENT_NAME + "_QUOTE");
        quoteSession.setTargetCompID("QUOTE_SERVER");

        ClientDefinition clientDef = new ClientDefinition();
        clientDef.setTradeSession(tradeSession);
        clientDef.setQuoteSession(quoteSession);

        ConnectionDetails tradeConnection = new ConnectionDetails();
        tradeConnection.setSocketConnectHost("localhost");
        tradeConnection.setSocketConnectPort(String.valueOf(TRADE_PORT));

        ConnectionDetails quoteConnection = new ConnectionDetails();
        quoteConnection.setSocketConnectHost("localhost");
        quoteConnection.setSocketConnectPort(String.valueOf(QUOTE_PORT));

        ConnectionEnvironment connEnv = new ConnectionEnvironment();
        connEnv.setTrade(tradeConnection);
        connEnv.setQuote(quoteConnection);

        return new QFConnector(
            CLIENT_NAME,
            commonSettings,
            clientDef,
            connEnv,
            null, null, null, null
        );
    }

    /**
     * Creates a connector with no sessions configured (for testing edge case).
     */
    private QFConnector createNoSessionsConnector() {
        CommonSettings commonSettings = createCommonSettings();
        commonSettings.setTargetCompID("SERVER");

        ClientDefinition clientDef = new ClientDefinition();
        // No sessions configured

        ConnectionEnvironment connEnv = new ConnectionEnvironment();
        // No connections configured

        return new QFConnector(
            CLIENT_NAME,
            commonSettings,
            clientDef,
            connEnv,
            null, null, null, null
        );
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: isConnected with no sessions configured - returns false")
    void testIsConnected_NoSessionsConfigured_ReturnsFalse() {
        // Given: Connector with no sessions configured
        connector = createNoSessionsConnector();

        // When: Check connection status
        boolean connected = connector.isConnected();

        // Then: Should return false
        assertThat(connected)
            .as("isConnected() should return false when no sessions are configured")
            .isFalse();

        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should not be connected")
            .isFalse();

        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should not be connected")
            .isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: isConnected with trade session only connected - returns true")
    void testIsConnected_TradeSessionOnly_Connected_ReturnsTrue() throws Exception {
        // Given: Connector with only trade session configured
        connector = createTradeOnlyConnector();

        // When: Start and connect
        connector.start();
        boolean connectionSuccessful = connector.waitForConnection(10, TimeUnit.SECONDS);
        assertThat(connectionSuccessful).isTrue();

        // Then: Should report as connected
        assertThat(connector.isConnected())
            .as("isConnected() should return true when trade session is connected")
            .isTrue();

        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should be connected")
            .isTrue();

        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should not be connected (not configured)")
            .isFalse();

        assertThat(connector.hasQuoteSession())
            .as("Should not have quote session capability")
            .isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: isConnected with both sessions, only one connected - returns false")
    void testIsConnected_BothSessions_OnlyOneConnected_ReturnsFalse() throws Exception {
        // Given: Connector with both sessions configured
        connector = createDualSessionConnector();

        // When: Start but only let trade session connect by stopping quote acceptor
        quoteAcceptor.stop();
        Thread.sleep(100); // Brief pause

        connector.start();
        
        // Wait a bit for trade session to connect
        Thread.sleep(2000);

        // Then: isConnected should return false because not all sessions are connected
        // Trade session should be connected
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should be connected")
            .isTrue();

        // Quote session should not be connected
        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should not be connected")
            .isFalse();

        // Overall connected status should be false
        assertThat(connector.isConnected())
            .as("isConnected() should return false when only one of two sessions is connected")
            .isFalse();

        assertThat(connector.hasQuoteSession())
            .as("Should have quote session capability")
            .isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: isConnected with both sessions connected - returns true")
    void testIsConnected_BothSessions_BothConnected_ReturnsTrue() throws Exception {
        // Given: Connector with both sessions configured
        connector = createDualSessionConnector();

        // When: Start and wait for both sessions to connect
        connector.start();
        boolean connected = connector.waitForConnection(10, TimeUnit.SECONDS);
        assertThat(connected).isTrue();

        // Then: All connection checks should return true
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should be connected")
            .isTrue();

        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should be connected")
            .isTrue();

        assertThat(connector.isConnected())
            .as("isConnected() should return true when both sessions are connected")
            .isTrue();

        assertThat(connector.hasQuoteSession())
            .as("Should have quote session capability")
            .isTrue();

        // Verify acceptors received logons
        assertThat(tradeAcceptor.getLogons())
            .as("Trade acceptor should have received logon")
            .hasValueGreaterThanOrEqualTo(1);

        assertThat(quoteAcceptor.getLogons())
            .as("Quote acceptor should have received logon")
            .hasValueGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: isTradeSessionConnected reflects actual state")
    void testIsTradeSessionConnected_ReflectsActualState() throws Exception {
        // Given: Connector with trade session
        connector = createTradeOnlyConnector();

        // When: Not started
        // Then: Should not be connected
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should not be connected before start")
            .isFalse();

        // When: Started and connected
        connector.start();
        connector.waitForConnection(10, TimeUnit.SECONDS);

        // Then: Should be connected
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should be connected after logon")
            .isTrue();

        // When: Stopped
        connector.stop();
        Thread.sleep(300);

        // Then: Should not be connected
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should not be connected after stop")
            .isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: isQuoteSessionConnected reflects actual state")
    void testIsQuoteSessionConnected_ReflectsActualState() throws Exception {
        // Given: Connector with both sessions
        connector = createDualSessionConnector();

        // When: Not started
        // Then: Should not be connected
        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should not be connected before start")
            .isFalse();

        // When: Started and connected
        connector.start();
        connector.waitForConnection(10, TimeUnit.SECONDS);

        // Then: Should be connected
        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should be connected after logon")
            .isTrue();

        // When: Stopped
        connector.stop();
        Thread.sleep(300);

        // Then: Should not be connected
        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should not be connected after stop")
            .isFalse();
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: hasQuoteSession reflects configuration, not connection state")
    void testHasQuoteSession_ReflectsConfiguration() throws Exception {
        // Test 1: Trade-only connector
        // Given: Connector with only trade session
        connector = createTradeOnlyConnector();

        // When: Check before starting
        // Then: Should return false (not configured)
        assertThat(connector.hasQuoteSession())
            .as("Trade-only connector should not have quote session capability")
            .isFalse();

        // When: Connected
        connector.start();
        connector.waitForConnection(5, TimeUnit.SECONDS);

        // Then: Still should return false (capability, not connection state)
        assertThat(connector.hasQuoteSession())
            .as("Trade-only connector should not have quote session even when connected")
            .isFalse();

        connector.close();
        Thread.sleep(200);

        // Test 2: Dual-session connector
        // Given: Connector with both sessions
        connector = createDualSessionConnector();

        // When: Check before starting
        // Then: Should return true (configured)
        assertThat(connector.hasQuoteSession())
            .as("Dual-session connector should have quote session capability before connecting")
            .isTrue();

        // When: Started but quote not connected (stop quote acceptor)
        quoteAcceptor.stop();
        Thread.sleep(100);
        
        connector.start();
        Thread.sleep(2000); // Let trade connect

        // Then: Should still return true (capability exists, even if not connected)
        assertThat(connector.hasQuoteSession())
            .as("Dual-session connector should have quote session capability even if not connected")
            .isTrue();

        assertThat(connector.isQuoteSessionConnected())
            .as("Quote session should not be connected")
            .isFalse();
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: getStatus returns accurate snapshot")
    void testGetStatus_ReturnsAccurateSnapshot() throws Exception {
        // Test 1: Before connection
        // Given: Fresh dual-session connector
        connector = createDualSessionConnector();

        // When: Get status before starting
        QFClientStatus status = connector.getStatus();

        // Then: Should reflect not-connected state
        assertThat(status).isNotNull();
        assertThat(status.clientStreamName()).isEqualTo(CLIENT_NAME);
        assertThat(status.tradeSessionConnected()).isFalse();
        assertThat(status.quoteSessionConnected()).isFalse();
        assertThat(status.tradeSessionId()).isNull();
        assertThat(status.quoteSessionId()).isNull();

        // Test 2: After connection
        // When: Start and connect
        connector.start();
        boolean connected = connector.waitForConnection(10, TimeUnit.SECONDS);
        assertThat(connected).isTrue();

        // When: Get status after connection
        status = connector.getStatus();

        // Then: Should reflect connected state with session IDs
        assertThat(status).isNotNull();
        assertThat(status.clientStreamName()).isEqualTo(CLIENT_NAME);
        assertThat(status.tradeSessionConnected())
            .as("Status should show trade session connected")
            .isTrue();
        assertThat(status.quoteSessionConnected())
            .as("Status should show quote session connected")
            .isTrue();
        
        assertThat(status.tradeSessionId())
            .as("Trade session ID should be populated")
            .isNotNull();
        assertThat(status.tradeSessionId().getSenderCompID())
            .isEqualTo(CLIENT_NAME + "_TRADE");
        assertThat(status.tradeSessionId().getTargetCompID())
            .isEqualTo("TRADE_SERVER");

        assertThat(status.quoteSessionId())
            .as("Quote session ID should be populated")
            .isNotNull();
        assertThat(status.quoteSessionId().getSenderCompID())
            .isEqualTo(CLIENT_NAME + "_QUOTE");
        assertThat(status.quoteSessionId().getTargetCompID())
            .isEqualTo("QUOTE_SERVER");

        // Test 3: After disconnection
        // When: Stop connector
        connector.stop();
        Thread.sleep(300);

        // When: Get status after stop
        status = connector.getStatus();

        // Then: Should reflect disconnected state
        assertThat(status).isNotNull();
        assertThat(status.clientStreamName()).isEqualTo(CLIENT_NAME);
        assertThat(status.tradeSessionConnected())
            .as("Status should show trade session disconnected")
            .isFalse();
        assertThat(status.quoteSessionConnected())
            .as("Status should show quote session disconnected")
            .isFalse();
        
        // Session IDs should be null after stop
        assertThat(status.tradeSessionId())
            .as("Trade session ID should be null after stop")
            .isNull();
        assertThat(status.quoteSessionId())
            .as("Quote session ID should be null after stop")
            .isNull();
    }
}