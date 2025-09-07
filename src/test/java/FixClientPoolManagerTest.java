
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.quick.fix.poc.client.*;
import com.qa.quick.fix.poc.config.*;
import com.qa.quick.fix.poc.pool.FixClientPoolManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.fix44.NewOrderSingle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FixClientPoolManagerTest {

    @Mock private ManagedFixClient mockClient1;
    @Mock private ManagedFixClient mockClient2;
    @Mock private MessageListener globalMessageListener;
    @Mock private SessionEventListener globalSessionEventListener;

    @TempDir
    Path tempDir;

    private FixClientPoolManager poolManager;
    private static final String ENV_NAME = "TEST_ENV";
    private static final String CLIENT1_NAME = "CLIENT1";
    private static final String CLIENT2_NAME = "CLIENT2";
    private static final Set<String> CLIENT_NAMES = Set.of(CLIENT1_NAME, CLIENT2_NAME);

    @BeforeEach
    void setUp() {
        reset(mockClient1, mockClient2, globalMessageListener, globalSessionEventListener);
    }

    @Test
    void testConstructor_WithRealConfiguration_Success() throws Exception {
        String configPath = createValidConfigFile();

        FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));

        assertNotNull(manager);
        assertFalse(manager.isStarted());
    }

    @Test
    void testStartAll_MockedClients_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    // Configure the mock based on constructor arguments
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            assertTrue(manager.isStarted());

            // Verify mock interactions
            List<ManagedFixClient> constructedMocks = mockedConstruction.constructed();
            assertEquals(1, constructedMocks.size());
            verify(constructedMocks.get(0)).start();
        }
    }

    @Test
    void testStopAll_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            assertTrue(manager.isStarted());

            boolean result = manager.stopAll(5, TimeUnit.SECONDS);

            assertTrue(result);
            assertFalse(manager.isStarted());

            // Verify stop was called
            List<ManagedFixClient> constructedMocks = mockedConstruction.constructed();
            verify(constructedMocks.get(0)).stop();
        }
    }

    @Test
    void testSendTradeMessage_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            Message message = new NewOrderSingle();
            manager.sendTradeMessage("client1", message);

            // Verify message was sent
            List<ManagedFixClient> constructedMocks = mockedConstruction.constructed();
            verify(constructedMocks.get(0)).sendTradeMessage(message);
        }
    }

    @Test
    void testSendTradeMessage_PoolNotStarted_ThrowsException() throws Exception {
        String configPath = createValidConfigFile();
        FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));

        Message message = new NewOrderSingle();

        FixClientPoolException exception = assertThrows(FixClientPoolException.class, () -> {
            manager.sendTradeMessage("client1", message);
        });

        assertTrue(exception.getMessage().contains("Pool is not started"));
    }

    @Test
    void testSendQuoteMessage_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            Message message = new NewOrderSingle();
            manager.sendQuoteMessage("client1", message);

            // Verify message was sent
            List<ManagedFixClient> constructedMocks = mockedConstruction.constructed();
            verify(constructedMocks.get(0)).sendQuoteMessage(message);
        }
    }

    @Test
    void testGetClientStatus_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);

                    ClientStatus mockStatus = new ClientStatus(clientName, true, false,
                            new SessionID("FIX.4.4", "SENDER", "TARGET"), null);
                    when(mock.getStatus()).thenReturn(mockStatus);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            ClientStatus status = manager.getClientStatus("client1");

            assertNotNull(status);
            assertEquals("client1", status.getClientStreamName());
            assertTrue(status.isTradeSessionConnected());
        }
    }

    @Test
    void testGetClientStatus_ClientNotFound_ReturnsNull() throws Exception {
        String configPath = createValidConfigFile();
        FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));

        ClientStatus status = manager.getClientStatus("unknown_client");

        assertNull(status);
    }

    @Test
    void testIsClientConnected_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isTradeSessionConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            boolean connected = manager.isClientConnected("client1");

            assertTrue(connected);
        }
    }

    @Test
    void testGetStatistics_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            // Send a message to test statistics
            Message message = new NewOrderSingle();
            manager.sendTradeMessage("client1", message);

            FixClientPoolManager.PoolStatistics stats = manager.getStatistics();

            assertEquals(1, stats.getTotalClients());
            assertEquals(1, stats.getConnectedClients());
            assertEquals(1, stats.getTotalMessagesSent());
            assertEquals(1.0, stats.getConnectionRate(), 0.01);
        }
    }

    @Test
    void testEnableHealthMonitoring_Success() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);
                    when(mock.isFullyConnected()).thenReturn(true);
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));
            manager.startAll();

            manager.enableHealthMonitoring(5);
            manager.disableHealthMonitoring();

            // If no exception is thrown, health monitoring works
        }
    }

    @Test
    void testSetGlobalListeners_Success() throws Exception {
        String configPath = createValidConfigFile();
        FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1"));

        manager.setGlobalMessageListener(globalMessageListener);
        manager.setGlobalSessionEventListener(globalSessionEventListener);

        // Listeners are set - this is mainly for coverage
    }

    @Test
    void testStartAll_AllowPartialSuccess_SomeClientsFail() throws Exception {
        String configPath = createValidConfigFile();

        try (MockedConstruction<ManagedFixClient> mockedConstruction = mockConstruction(ManagedFixClient.class,
                (mock, context) -> {
                    String clientName = (String) context.arguments().get(0);
                    when(mock.getClientStreamName()).thenReturn(clientName);
                    when(mock.waitForConnection(anyLong(), any(TimeUnit.class))).thenReturn(true);

                    // Make second client fail to start
                    if ("client2".equals(clientName)) {
                        doThrow(new RuntimeException("Start failed")).when(mock).start();
                    }
                })) {

            FixClientPoolManager manager = new FixClientPoolManager(configPath, ENV_NAME, Set.of("client1", "client2"));
            FixClientPoolManager.StartupResult result = manager.startAll(true);

            assertTrue(manager.isStarted());
            assertFalse(result.isAllSuccessful());
            assertEquals(1, result.getSuccessfulCount());
            assertEquals(1, result.getFailedCount());
        }
    }

    @Test
    void testConstructor_InvalidEnvironment_ThrowsException() throws Exception {
        String configPath = createInvalidConfigFile();

        FixClientPoolException exception = assertThrows(FixClientPoolException.class, () -> {
            new FixClientPoolManager(configPath, "INVALID_ENV", Set.of("client1"));
        });

        assertTrue(exception.getMessage().contains("Environment not found"));
    }

    @Test
    void testConstructor_MissingClient_ThrowsException() throws Exception {
        String configPath = createValidConfigFile();

        FixClientPoolException exception = assertThrows(FixClientPoolException.class, () -> {
            new FixClientPoolManager(configPath, ENV_NAME, Set.of("missing_client"));
        });

        assertTrue(exception.getMessage().contains("Client not found"));
    }

    // Helper methods

    private String createValidConfigFile() throws IOException {
        Path configFile = tempDir.resolve("config.json");
        String configContent = """
            {
              "common": {
                "BeginString": "FIX.4.4",
                "TargetCompID": "TARGET",
                "FileStorePath": "/tmp/fix",
                "ConnectionType": "initiator"
              },
              "connections": {
                "TEST_ENV": {
                  "trade": {
                    "SocketConnectHost": "localhost",
                    "SocketConnectPort": "8001"
                  }
                }
              },
              "clients": {
                "client1": {
                  "tradeSession": {
                    "SenderCompID": "CLIENT1_TRADE"
                  }
                },
                "client2": {
                  "tradeSession": {
                    "SenderCompID": "CLIENT2_TRADE"
                  }
                }
              }
            }
            """;
        Files.write(configFile, configContent.getBytes());
        return configFile.toString();
    }

    private String createInvalidConfigFile() throws IOException {
        Path configFile = tempDir.resolve("invalid_config.json");
        String configContent = """
            {
              "common": {
                "BeginString": "FIX.4.4",
                "TargetCompID": "TARGET",
                "FileStorePath": "/tmp/fix"
              },
              "connections": {
                "OTHER_ENV": {
                  "trade": {
                    "SocketConnectHost": "localhost",
                    "SocketConnectPort": "8001"
                  }
                }
              },
              "clients": {
                "client1": {
                  "tradeSession": {
                    "SenderCompID": "CLIENT1_TRADE"
                  }
                }
              }
            }
            """;
        Files.write(configFile, configContent.getBytes());
        return configFile.toString();
    }
}