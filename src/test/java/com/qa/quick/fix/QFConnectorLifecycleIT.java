package com.qa.quick.fix;

import com.qa.quick.fix.cfg.ClientDefinition;
import com.qa.quick.fix.cfg.CommonSettings;
import com.qa.quick.fix.cfg.ConnectionDetails;
import com.qa.quick.fix.cfg.ConnectionEnvironment;
import com.qa.quick.fix.cfg.SessionConfig;
import com.qa.quick.fix.core.client.QFConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import quickfix.ConfigError;
import quickfix.SessionID;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration tests for QFConnector basic lifecycle operations.
 * Tests start, stop, restart, and connection waiting functionality.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("critical")
@Tag("lifecycle")
@DisplayName("QFConnector Basic Lifecycle Integration Tests")
class QFConnectorLifecycleIT {

    private TestFixAcceptor acceptor;
    private QFConnector connector;
    
    private static final String CLIENT_NAME = "TEST_CLIENT";
    private static final int ACCEPTOR_PORT = 9876;

    @BeforeEach
    void setup() throws Exception {
        // Start acceptor that mirrors the client configuration
        SessionID acceptorSession = new SessionID("FIX.4.4", "SERVER", CLIENT_NAME);
        acceptor = new TestFixAcceptor(acceptorSession, ACCEPTOR_PORT);
        acceptor.start();
        
        // Brief pause to ensure acceptor is ready
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
        
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    /**
     * Creates a test QFConnector instance with standard configuration.
     */
    private QFConnector createTestConnector() {
        CommonSettings commonSettings = new CommonSettings();
        commonSettings.setBeginString("FIX.4.4");
        commonSettings.setTargetCompID("SERVER");
        commonSettings.setConnectionType("initiator");
        commonSettings.setReconnectInterval("5");
        commonSettings.setHeartBtInt("30");
        commonSettings.setStartTime("00:00:00");
        commonSettings.setEndTime("00:00:00");
        commonSettings.setFileStorePath("target/test-data/connector");
        commonSettings.setUseDataDictionary("N");
        commonSettings.setSlf4jLogHeartbeats("N");

        SessionConfig tradeSession = new SessionConfig();
        tradeSession.setSenderCompID(CLIENT_NAME);
        tradeSession.setTargetCompID("SERVER");

        ClientDefinition clientDef = new ClientDefinition();
        clientDef.setTradeSession(tradeSession);

        ConnectionDetails tradeConnection = new ConnectionDetails();
        tradeConnection.setSocketConnectHost("localhost");
        tradeConnection.setSocketConnectPort(String.valueOf(ACCEPTOR_PORT));

        ConnectionEnvironment connEnv = new ConnectionEnvironment();
        connEnv.setTrade(tradeConnection);

        return new QFConnector(
            CLIENT_NAME,
            commonSettings,
            clientDef,
            connEnv,
            null, // no ports config
            null, // no inbound listener
            null, // no session event listener
            null  // no outbound listener
        );
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Start fresh connector - connects successfully")
    void testStart_FreshConnector_ConnectsSuccessfully() throws Exception {
        // Given: Fresh connector
        connector = createTestConnector();

        // When: Start and wait for connection
        connector.start();
        boolean connected = connector.waitForConnection(10, TimeUnit.SECONDS);

        // Then: Should connect successfully
        assertThat(connected)
            .as("Connector should connect within 10 seconds")
            .isTrue();
        
        assertThat(connector.isConnected())
            .as("Connector should report as connected")
            .isTrue();
        
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should be connected")
            .isTrue();
        
assertThat(acceptor.getLogons())
    .as("Acceptor should have received logon")
    .hasValueGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Start already started connector - is idempotent")
    void testStart_AlreadyStarted_IsIdempotent() throws Exception {
        // Given: Already started connector
        connector = createTestConnector();
        connector.start();
        boolean firstConnection = connector.waitForConnection(5, TimeUnit.SECONDS);
        assertThat(firstConnection).isTrue();

        int initialLogons = acceptor.getLogons().get();

        // When: Start again
        connector.start(); // Should be no-op

        // Brief pause to see if anything happens
        Thread.sleep(500);

        // Then: Should remain connected, no additional logons
        assertThat(connector.isConnected())
            .as("Connector should still be connected")
            .isTrue();
        
        // No additional logons should have occurred
        assertThat(acceptor.getLogons())
                .as("Should not create additional connections")
                .hasValue(initialLogons);

    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Stop when started - disconnects successfully")
    void testStop_WhenStarted_DisconnectsSuccessfully() throws Exception {
        // Given: Started and connected connector
        connector = createTestConnector();
        connector.start();
        boolean connected = connector.waitForConnection(5, TimeUnit.SECONDS);
        assertThat(connected).isTrue();

        // When: Stop connector
        connector.stop();

        // Brief pause to allow disconnect to process
        Thread.sleep(300);

        // Then: Should be disconnected
        assertThat(connector.isConnected())
            .as("Connector should be disconnected after stop")
            .isFalse();
        
        assertThat(connector.isTradeSessionConnected())
            .as("Trade session should be disconnected")
            .isFalse();
        
        assertThat(acceptor.getLogouts())
            .as("Acceptor should have received logout")
            .hasValueGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Stop when not started - is idempotent")
    void testStop_WhenNotStarted_IsIdempotent() {
        // Given: Fresh connector (never started)
        connector = createTestConnector();

        // When: Stop without starting
        // Then: Should not throw exception
        assertDoesNotThrow(() -> connector.stop(),
            "Stop should not throw when connector was never started");
        
        // Verify state remains consistent
        assertThat(connector.isConnected())
            .as("Connector should not be connected")
            .isFalse();
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Restart connected session - reconnects successfully")
    void testRestart_ConnectedSession_ReconnectsSuccessfully() throws Exception {
        // Given: Connected connector
        connector = createTestConnector();
        connector.start();
        boolean initialConnection = connector.waitForConnection(5, TimeUnit.SECONDS);
        assertThat(initialConnection).isTrue();

        int logonsBeforeRestart = acceptor.getLogons().get();

        // When: Restart
        connector.restart();
        boolean reconnected = connector.waitForConnection(10, TimeUnit.SECONDS);

        // Then: Should reconnect successfully
        assertThat(reconnected)
            .as("Connector should reconnect after restart")
            .isTrue();
        
        assertThat(connector.isConnected())
            .as("Connector should be connected after restart")
            .isTrue();
        
        assertThat(acceptor.getLogons())
            .as("Should have additional logon after restart")
            .hasValueGreaterThan(logonsBeforeRestart);
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: RestartAndAwait - waits for connection and returns true")
    void testRestartAndAwait_WaitsForConnection_ReturnsTrue() throws Exception {
        // Given: Connected connector
        connector = createTestConnector();
        connector.start();
        connector.waitForConnection(5, TimeUnit.SECONDS);

        // When: Restart and await connection
        boolean connected = connector.restartAndAwait(10, TimeUnit.SECONDS);

        // Then: Should return true when connected
        assertThat(connected)
            .as("RestartAndAwait should return true when connection succeeds")
            .isTrue();
        
        assertThat(connector.isConnected())
            .as("Connector should be connected after restartAndAwait")
            .isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: Close calls stop")
    void testClose_CallsStop() throws Exception {
        // Given: Started connector
        connector = createTestConnector();
        connector.start();
        connector.waitForConnection(5, TimeUnit.SECONDS);
        assertThat(connector.isConnected()).isTrue();

        // When: Close (AutoCloseable)
        connector.close();

        // Brief pause
        Thread.sleep(300);

        // Then: Should be stopped/disconnected
        assertThat(connector.isConnected())
            .as("Connector should be disconnected after close")
            .isFalse();
        
        assertThat(acceptor.getLogouts())
            .as("Acceptor should have received logout")
            .hasValueGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: Wait for connection before start - returns false")
    void testWaitForConnection_BeforeStart_ReturnsFalse() throws Exception {
        // Given: Fresh connector (not started)
        connector = createTestConnector();

        // When: Wait for connection without starting
        boolean connected = connector.waitForConnection(1, TimeUnit.SECONDS);

        // Then: Should return false immediately
        assertThat(connected)
            .as("WaitForConnection should return false when not started")
            .isFalse();
        
        assertThat(connector.isConnected())
            .as("Connector should not be connected")
            .isFalse();
    }

    @Test
    @Order(9)
    @DisplayName("Test 9: Wait for connection after start - waits for connection")
    void testWaitForConnection_AfterStart_WaitsForConnection() throws Exception {
        // Given: Started connector
        connector = createTestConnector();
        connector.start();

        // When: Wait for connection in separate thread to verify it actually waits
        AtomicBoolean connectionResult = new AtomicBoolean(false);
        CountDownLatch waitStarted = new CountDownLatch(1);
        CountDownLatch waitCompleted = new CountDownLatch(1);

        Thread waitThread = new Thread(() -> {
            try {
                waitStarted.countDown();
                boolean connected = connector.waitForConnection(10, TimeUnit.SECONDS);
                connectionResult.set(connected);
                waitCompleted.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        waitThread.start();
        
        // Ensure wait has started
        assertThat(waitStarted.await(2, TimeUnit.SECONDS))
            .as("Wait thread should start")
            .isTrue();

        // Wait should complete when connection happens
        assertThat(waitCompleted.await(12, TimeUnit.SECONDS))
            .as("Wait should complete")
            .isTrue();

        waitThread.join(1000);

        // Then: Should return true when connected
        assertThat(connectionResult.get())
            .as("WaitForConnection should return true when connection succeeds")
            .isTrue();
        
        assertThat(connector.isConnected())
            .as("Connector should be connected")
            .isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("Test 10: Wait for connection timeout - returns false")
    void testWaitForConnection_Timeout_ReturnsFalse() throws Exception {
        // Given: Connector configured to connect to non-existent server
        CommonSettings commonSettings = new CommonSettings();
        commonSettings.setBeginString("FIX.4.4");
        commonSettings.setTargetCompID("SERVER");
        commonSettings.setConnectionType("initiator");
        commonSettings.setReconnectInterval("60"); // Long reconnect to ensure timeout
        commonSettings.setHeartBtInt("30");
        commonSettings.setStartTime("00:00:00");
        commonSettings.setEndTime("00:00:00");
        commonSettings.setFileStorePath("target/test-data/connector-timeout");
        commonSettings.setUseDataDictionary("N");
        commonSettings.setSlf4jLogHeartbeats("N");

        SessionConfig tradeSession = new SessionConfig();
        tradeSession.setSenderCompID("TIMEOUT_CLIENT");
        tradeSession.setTargetCompID("SERVER");

        ClientDefinition clientDef = new ClientDefinition();
        clientDef.setTradeSession(tradeSession);

        ConnectionDetails tradeConnection = new ConnectionDetails();
        tradeConnection.setSocketConnectHost("localhost");
        tradeConnection.setSocketConnectPort("19999"); // Non-existent port

        ConnectionEnvironment connEnv = new ConnectionEnvironment();
        connEnv.setTrade(tradeConnection);

        connector = new QFConnector(
            "TIMEOUT_CLIENT",
            commonSettings,
            clientDef,
            connEnv,
            null, null, null, null
        );

        // When: Start and wait for connection with short timeout
        connector.start();
        boolean connected = connector.waitForConnection(2, TimeUnit.SECONDS);

        // Then: Should timeout and return false
        assertThat(connected)
            .as("WaitForConnection should return false on timeout")
            .isFalse();
        
        assertThat(connector.isConnected())
            .as("Connector should not be connected after timeout")
            .isFalse();
    }
}