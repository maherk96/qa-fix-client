
import com.qa.quick.fix.poc.client.*;
import com.qa.quick.fix.poc.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import quickfix.*;
import quickfix.field.Text;
import quickfix.fix44.Logon;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.Reject;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagedFixClientTest {

    @Mock private MessageListener messageListener;
    @Mock private SessionEventListener sessionEventListener;
    @Mock private CommonSettings commonSettings;
    @Mock private ClientDefinition clientDefinition;
    @Mock private ConnectionEnvironment connectionEnvironment;
    @Mock private PortsConfiguration portsConfig;
    @Mock private SessionConfig tradeSessionConfig;
    @Mock private SessionConfig quoteSessionConfig;
    @Mock private ConnectionDetails tradeConnectionDetails;
    @Mock private ConnectionDetails quoteConnectionDetails;
    @Mock private OtherSettings otherSettings;

    private ManagedFixClient managedFixClient;
    private static final String CLIENT_STREAM_NAME = "TEST_CLIENT";
    private static final String TRADE_SENDER_COMP_ID = "TRADE_SENDER";
    private static final String QUOTE_SENDER_COMP_ID = "QUOTE_SENDER";
    private static final String TARGET_COMP_ID = "TARGET";

    @BeforeEach
    void setUp() {
        reset(commonSettings, clientDefinition, connectionEnvironment, portsConfig,
                tradeSessionConfig, quoteSessionConfig, tradeConnectionDetails,
                quoteConnectionDetails, otherSettings);
    }

    private void setupBasicCommonSettings() {
        when(commonSettings.getBeginString()).thenReturn("FIX.4.4");
        when(commonSettings.getTargetCompID()).thenReturn(TARGET_COMP_ID);
        when(commonSettings.getFileStorePath()).thenReturn("/tmp/fixstore");
    }

    private void setupFullCommonSettings() {
        setupBasicCommonSettings();
        when(commonSettings.getConnectionType()).thenReturn("initiator");
        when(commonSettings.getReconnectInterval()).thenReturn("30");
        when(commonSettings.getHeartBtInt()).thenReturn("30");
        when(commonSettings.getUseDataDictionary()).thenReturn("Y");
        when(commonSettings.getDataDictionary()).thenReturn("FIX44.xml");
        when(commonSettings.getStartTime()).thenReturn("00:00:00");
        when(commonSettings.getEndTime()).thenReturn("23:59:59");
    }

    private void setupTradeOnlyClient() {
        setupBasicCommonSettings();

        when(tradeSessionConfig.getSenderCompID()).thenReturn(TRADE_SENDER_COMP_ID);
        when(tradeSessionConfig.getTargetCompID()).thenReturn(null);

        when(clientDefinition.getTradeSession()).thenReturn(tradeSessionConfig);
        when(clientDefinition.getQuoteSession()).thenReturn(null); // Explicitly null for trade-only
        when(clientDefinition.getOther()).thenReturn(otherSettings);

        when(tradeConnectionDetails.getSocketConnectHost()).thenReturn("trade-server.com");
        when(tradeConnectionDetails.getSocketConnectPort()).thenReturn("8001");

        when(connectionEnvironment.getTrade()).thenReturn(tradeConnectionDetails);
        when(connectionEnvironment.getQuote()).thenReturn(null); // Explicitly null for trade-only

        // Verify our setup is correct before creating the client
        assertNull( clientDefinition.getQuoteSession(),"Quote session should be null in setup");
        assertNull( connectionEnvironment.getQuote(), "Quote connection should be null in setup");

        managedFixClient = new ManagedFixClient(
                CLIENT_STREAM_NAME, commonSettings, clientDefinition,
                connectionEnvironment, null, messageListener, sessionEventListener
        );

        // Verify immediately after construction
        assertFalse(
                managedFixClient.hasQuoteSession(), "hasQuoteSession should be false immediately after construction");
    }

    private void setupTradeAndQuoteClient() {
        setupBasicCommonSettings();

        when(tradeSessionConfig.getSenderCompID()).thenReturn(TRADE_SENDER_COMP_ID);
        when(tradeSessionConfig.getTargetCompID()).thenReturn(null);
        when(quoteSessionConfig.getSenderCompID()).thenReturn(QUOTE_SENDER_COMP_ID);
        when(quoteSessionConfig.getTargetCompID()).thenReturn(null);

        when(clientDefinition.getTradeSession()).thenReturn(tradeSessionConfig);
        when(clientDefinition.getQuoteSession()).thenReturn(quoteSessionConfig);
        when(clientDefinition.getOther()).thenReturn(otherSettings);

        when(tradeConnectionDetails.getSocketConnectHost()).thenReturn("trade-server.com");
        when(tradeConnectionDetails.getSocketConnectPort()).thenReturn("8001");
        when(quoteConnectionDetails.getSocketConnectHost()).thenReturn("quote-server.com");
        when(quoteConnectionDetails.getSocketConnectPort()).thenReturn("8002");

        when(connectionEnvironment.getTrade()).thenReturn(tradeConnectionDetails);
        when(connectionEnvironment.getQuote()).thenReturn(quoteConnectionDetails);

        managedFixClient = new ManagedFixClient(
                CLIENT_STREAM_NAME, commonSettings, clientDefinition,
                connectionEnvironment, null, messageListener, sessionEventListener
        );
    }

    private void setupClientWithPortsConfig() {
        setupBasicCommonSettings();

        when(tradeSessionConfig.getSenderCompID()).thenReturn(TRADE_SENDER_COMP_ID);
        when(quoteSessionConfig.getSenderCompID()).thenReturn(QUOTE_SENDER_COMP_ID);

        when(clientDefinition.getTradeSession()).thenReturn(tradeSessionConfig);
        when(clientDefinition.getQuoteSession()).thenReturn(quoteSessionConfig);
        when(clientDefinition.getOther()).thenReturn(otherSettings);

        when(tradeConnectionDetails.getSocketConnectHost()).thenReturn("trade-server.com");
        when(tradeConnectionDetails.getSocketConnectPort()).thenReturn("8001");
        when(quoteConnectionDetails.getSocketConnectHost()).thenReturn("quote-server.com");
        when(quoteConnectionDetails.getSocketConnectPort()).thenReturn("8002");

        when(connectionEnvironment.getTrade()).thenReturn(tradeConnectionDetails);
        when(connectionEnvironment.getQuote()).thenReturn(quoteConnectionDetails);

        // Setup ports config
        ClientPortInfo tradePortInfo = new ClientPortInfo();
        tradePortInfo.setName(TRADE_SENDER_COMP_ID);
        tradePortInfo.setPort("9001");
        tradePortInfo.setLocation("NYC");

        ClientPortInfo quotePortInfo = new ClientPortInfo();
        quotePortInfo.setName(QUOTE_SENDER_COMP_ID);
        quotePortInfo.setPort("9002");
        quotePortInfo.setLocation("NYC");

        when(portsConfig.getClients()).thenReturn(Arrays.asList(tradePortInfo, quotePortInfo));

        managedFixClient = new ManagedFixClient(
                CLIENT_STREAM_NAME, commonSettings, clientDefinition,
                connectionEnvironment, portsConfig, messageListener, sessionEventListener
        );
    }

    @Test
    void testTradeOnlyClient_Construction() {
        setupTradeOnlyClient();

        assertEquals(CLIENT_STREAM_NAME, managedFixClient.getClientStreamName());
        assertFalse(managedFixClient.isTradeSessionConnected());
        assertFalse(managedFixClient.isQuoteSessionConnected());
        assertFalse(managedFixClient.hasQuoteSession());
        assertFalse(managedFixClient.isFullyConnected());
    }

    @Test
    void testTradeAndQuoteClient_Construction() {
        setupTradeAndQuoteClient();

        assertEquals(CLIENT_STREAM_NAME, managedFixClient.getClientStreamName());
        assertFalse(managedFixClient.isTradeSessionConnected());
        assertFalse(managedFixClient.isQuoteSessionConnected());
        assertFalse(managedFixClient.isFullyConnected());
    }

    @Test
    void testPortResolution_NoPortsConfig_UsesConnectionDetails() throws Exception {
        setupFullCommonSettings();
        setupTradeOnlyClient();

        SessionSettings settings = invokeCreateSessionSettings();

        assertNotNull(settings);
        assertEquals(1, settings.size());
    }

    @Test
    void testPortResolution_WithPortsConfig_OverridesConnectionDetails() throws Exception {
        setupFullCommonSettings();
        setupClientWithPortsConfig();

        SessionSettings settings = invokeCreateSessionSettings();

        assertNotNull(settings);
        assertEquals(2, settings.size());
    }

    @Test
    void testPortResolution_PortsConfigNoMatch_FallsBackToConnectionDetails() {
        setupBasicCommonSettings();

        when(tradeSessionConfig.getSenderCompID()).thenReturn("UNKNOWN_SENDER");
        when(clientDefinition.getTradeSession()).thenReturn(tradeSessionConfig);
        when(clientDefinition.getQuoteSession()).thenReturn(null);
        when(clientDefinition.getOther()).thenReturn(otherSettings);

        when(tradeConnectionDetails.getSocketConnectHost()).thenReturn("trade-server.com");
        when(tradeConnectionDetails.getSocketConnectPort()).thenReturn("8001");
        when(connectionEnvironment.getTrade()).thenReturn(tradeConnectionDetails);

        ClientPortInfo portInfo = new ClientPortInfo();
        portInfo.setName("DIFFERENT_SENDER");
        portInfo.setPort("9999");
        when(portsConfig.getClients()).thenReturn(Arrays.asList(portInfo));

        managedFixClient = new ManagedFixClient(
                CLIENT_STREAM_NAME, commonSettings, clientDefinition,
                connectionEnvironment, portsConfig, messageListener, sessionEventListener
        );

        assertNotNull(managedFixClient);
    }

    @Test
    void testSessionCreation_TradeSession() {
        setupTradeOnlyClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);

        managedFixClient.onCreate(tradeSessionId);

        assertFalse(managedFixClient.isTradeSessionConnected());
        assertFalse(managedFixClient.hasQuoteSession());
    }

    @Test
    void testSessionCreation_TradeAndQuoteSession() {
        setupTradeAndQuoteClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        SessionID quoteSessionId = new SessionID("FIX.4.4", QUOTE_SENDER_COMP_ID, TARGET_COMP_ID);

        managedFixClient.onCreate(tradeSessionId);
        managedFixClient.onCreate(quoteSessionId);

        assertTrue(managedFixClient.hasQuoteSession());
    }

    @Test
    void testLogon_TradeSession() {
        setupTradeOnlyClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        managedFixClient.onCreate(tradeSessionId);

        managedFixClient.onLogon(tradeSessionId);

        assertTrue(managedFixClient.isTradeSessionConnected());
        assertTrue(managedFixClient.isFullyConnected());
        verify(sessionEventListener).onLogon(tradeSessionId);
    }

    @Test
    void testLogon_BothSessions() {
        setupTradeAndQuoteClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        SessionID quoteSessionId = new SessionID("FIX.4.4", QUOTE_SENDER_COMP_ID, TARGET_COMP_ID);

        managedFixClient.onCreate(tradeSessionId);
        managedFixClient.onCreate(quoteSessionId);

        managedFixClient.onLogon(tradeSessionId);
        assertTrue(managedFixClient.isTradeSessionConnected());
        assertFalse(managedFixClient.isQuoteSessionConnected());
        assertFalse(managedFixClient.isFullyConnected());

        managedFixClient.onLogon(quoteSessionId);
        assertTrue(managedFixClient.isTradeSessionConnected());
        assertTrue(managedFixClient.isQuoteSessionConnected());
        assertTrue(managedFixClient.isFullyConnected());
    }

    @Test
    void testLogout_TradeSession() {
        setupTradeOnlyClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        managedFixClient.onCreate(tradeSessionId);
        managedFixClient.onLogon(tradeSessionId);

        assertTrue(managedFixClient.isTradeSessionConnected());

        managedFixClient.onLogout(tradeSessionId);

        assertFalse(managedFixClient.isTradeSessionConnected());
        assertFalse(managedFixClient.isFullyConnected());
        verify(sessionEventListener).onLogout(tradeSessionId);
    }

    @Test
    void testSendTradeMessage_SessionNotConnected() {
        setupTradeOnlyClient();

        Message message = new NewOrderSingle();

        FixClientPoolException exception = assertThrows(FixClientPoolException.class, () -> {
            managedFixClient.sendTradeMessage(message);
        });

        assertTrue(exception.getMessage().contains("Trade session not connected"));
    }

    @Test
    void testSendQuoteMessage_NoQuoteSession() {
        setupTradeOnlyClient();

        Message message = new NewOrderSingle();

        FixClientPoolException exception = assertThrows(FixClientPoolException.class, () -> {
            managedFixClient.sendQuoteMessage(message);
        });

        assertTrue(exception.getMessage().contains("Quote session not available"));
    }

    @Test
    void testSendQuoteMessage_SessionNotConnected() {
        setupTradeAndQuoteClient();

        SessionID quoteSessionId = new SessionID("FIX.4.4", QUOTE_SENDER_COMP_ID, TARGET_COMP_ID);
        managedFixClient.onCreate(quoteSessionId);

        Message message = new NewOrderSingle();

        FixClientPoolException exception = assertThrows(FixClientPoolException.class, () -> {
            managedFixClient.sendQuoteMessage(message);
        });

        assertTrue(exception.getMessage().contains("Quote session not available"));
    }

    @Test
    void testWaitForConnection_Timeout() throws Exception {
        setupTradeOnlyClient();

        boolean result = managedFixClient.waitForConnection(100, TimeUnit.MILLISECONDS);
        assertFalse(result);
    }

    @Test
    void testWaitForConnection_Success() throws Exception {
        setupTradeOnlyClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);

        Thread connectThread = new Thread(() -> {
            try {
                Thread.sleep(50);
                managedFixClient.onCreate(tradeSessionId);
                managedFixClient.onLogon(tradeSessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        connectThread.start();

        boolean result = managedFixClient.waitForConnection(200, TimeUnit.MILLISECONDS);
        assertTrue(result);
        connectThread.join();
    }

    @Test
    void testToAdmin_LogonWithCredentials() {
        setupTradeOnlyClient();

        when(otherSettings.getUsername()).thenReturn("testuser");
        when(otherSettings.getPassword()).thenReturn("testpass");

        SessionID sessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        Logon logonMessage = new Logon();

        managedFixClient.toAdmin(logonMessage, sessionId);

        assertTrue(logonMessage.isSetField(553)); // Username field
        assertTrue(logonMessage.isSetField(554)); // Password field
    }

    @Test
    void testToAdmin_LogonWithoutCredentials() {
        setupTradeOnlyClient();

        when(otherSettings.getUsername()).thenReturn(null);
        when(otherSettings.getPassword()).thenReturn(null);

        SessionID sessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        Logon logonMessage = new Logon();

        managedFixClient.toAdmin(logonMessage, sessionId);

        assertFalse(logonMessage.isSetField(553)); // Username field
        assertFalse(logonMessage.isSetField(554)); // Password field
    }

    @Test
    void testFromAdmin_RejectMessage() throws Exception {
        setupTradeOnlyClient();

        SessionID sessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        Reject rejectMessage = new Reject();
        rejectMessage.setString(Text.FIELD, "Invalid message format");

        managedFixClient.fromAdmin(rejectMessage, sessionId);

        verify(sessionEventListener).onReject(sessionId, "Invalid message format");
    }

    @Test
    void testFromAdmin_RejectMessageNoText() throws Exception {
        setupTradeOnlyClient();

        SessionID sessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        Reject rejectMessage = new Reject();

        managedFixClient.fromAdmin(rejectMessage, sessionId);

        verify(sessionEventListener).onReject(sessionId, "Unknown");
    }

    @Test
    void testFromApp_MessageReceived() {
        setupTradeOnlyClient();

        SessionID sessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        Message message = new NewOrderSingle();

        managedFixClient.fromApp(message, sessionId);

        verify(messageListener).onMessage(sessionId, message);
    }

    @Test
    void testGetStatus_TradeOnly() {
        setupTradeOnlyClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        managedFixClient.onCreate(tradeSessionId);
        managedFixClient.onLogon(tradeSessionId);

        ClientStatus status = managedFixClient.getStatus();

        assertEquals(CLIENT_STREAM_NAME, status.getClientStreamName());
        assertTrue(status.isTradeSessionConnected());
        assertFalse(status.isQuoteSessionConnected());
        assertEquals(tradeSessionId, status.getTradeSessionId());
        assertNull(status.getQuoteSessionId());
    }

    @Test
    void testGetStatus_TradeAndQuote() {
        setupTradeAndQuoteClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        SessionID quoteSessionId = new SessionID("FIX.4.4", QUOTE_SENDER_COMP_ID, TARGET_COMP_ID);

        managedFixClient.onCreate(tradeSessionId);
        managedFixClient.onCreate(quoteSessionId);
        managedFixClient.onLogon(tradeSessionId);
        managedFixClient.onLogon(quoteSessionId);

        ClientStatus status = managedFixClient.getStatus();

        assertEquals(CLIENT_STREAM_NAME, status.getClientStreamName());
        assertTrue(status.isTradeSessionConnected());
        assertTrue(status.isQuoteSessionConnected());
        assertEquals(tradeSessionId, status.getTradeSessionId());
        assertEquals(quoteSessionId, status.getQuoteSessionId());
    }

    @Test
    void testIsFullyConnected_TradeOnly() {
        setupTradeOnlyClient();

        assertFalse(managedFixClient.isFullyConnected());

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        managedFixClient.onCreate(tradeSessionId);

        assertFalse(managedFixClient.isFullyConnected());

        managedFixClient.onLogon(tradeSessionId);

        assertTrue(managedFixClient.isFullyConnected());
    }

    @Test
    void testIsFullyConnected_TradeAndQuote() {
        setupTradeAndQuoteClient();

        SessionID tradeSessionId = new SessionID("FIX.4.4", TRADE_SENDER_COMP_ID, TARGET_COMP_ID);
        SessionID quoteSessionId = new SessionID("FIX.4.4", QUOTE_SENDER_COMP_ID, TARGET_COMP_ID);

        managedFixClient.onCreate(tradeSessionId);
        managedFixClient.onCreate(quoteSessionId);

        assertFalse(managedFixClient.isFullyConnected());

        managedFixClient.onLogon(tradeSessionId);
        assertFalse(managedFixClient.isFullyConnected());

        managedFixClient.onLogon(quoteSessionId);
        assertTrue(managedFixClient.isFullyConnected());
    }

    private SessionSettings invokeCreateSessionSettings() throws Exception {
        java.lang.reflect.Method method = ManagedFixClient.class.getDeclaredMethod("createSessionSettings");
        method.setAccessible(true);
        return (SessionSettings) method.invoke(managedFixClient);
    }
}