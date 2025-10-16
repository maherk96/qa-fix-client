package com.qa.quick.fix.poc.client;

import com.qa.quick.fix.poc.config.ClientDefinition;
import com.qa.quick.fix.poc.config.ClientPortInfo;
import com.qa.quick.fix.poc.config.CommonSettings;
import com.qa.quick.fix.poc.config.ConnectionDetails;
import com.qa.quick.fix.poc.config.ConnectionEnvironment;
import com.qa.quick.fix.poc.config.OtherSettings;
import com.qa.quick.fix.poc.config.PortsConfiguration;
import com.qa.quick.fix.poc.config.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.Dictionary;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Password;
import quickfix.field.Text;
import quickfix.field.Username;
import quickfix.fix44.Logon;
import quickfix.fix44.Reject;

 
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for individual FIX client with its sessions
 */
public class ManagedFixClient implements Application {
    private static final Logger logger = LoggerFactory.getLogger(ManagedFixClient.class);
    
    private final String clientStreamName;
    private final ClientDefinition clientDefinition;
    private final ConnectionEnvironment connectionEnvironment;
    private final CommonSettings commonSettings;
    private final PortsConfiguration portsConfig;
    
    private SocketInitiator initiator;
    private SessionID tradeSessionId;
    private SessionID quoteSessionId; // Optional
    
    private final AtomicBoolean tradeSessionConnected = new AtomicBoolean(false);
    private final AtomicBoolean quoteSessionConnected = new AtomicBoolean(false);
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    
    private final MessageListener messageListener;
    private final SessionEventListener sessionEventListener;

    

    public ManagedFixClient(String clientStreamName, CommonSettings commonSettings,
                            ClientDefinition clientDefinition, ConnectionEnvironment connectionEnvironment,
                            PortsConfiguration portsConfig,
                            MessageListener messageListener, SessionEventListener sessionEventListener) {
        this.clientStreamName = clientStreamName;
        this.commonSettings = commonSettings;
        this.clientDefinition = clientDefinition;
        this.connectionEnvironment = connectionEnvironment;
        this.portsConfig = portsConfig;
        this.messageListener = messageListener;
        this.sessionEventListener = sessionEventListener;
    }

    public void start() throws ConfigError {
        logger.info("Starting client: {}", clientStreamName);
        
        SessionSettings settings = createSessionSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new SLF4JLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        initiator = new SocketInitiator(this, storeFactory, settings, logFactory, messageFactory);
        initiator.start();
        
        logger.info("Client {} initiator started", clientStreamName);
    }

    public void stop() {
        if (initiator != null) {
            logger.info("Stopping client: {}", clientStreamName);
            initiator.stop();
            tradeSessionConnected.set(false);
            quoteSessionConnected.set(false);
        }
    }

    public void sendTradeMessage(Message message) throws FixClientPoolException {
        if (tradeSessionId == null || !tradeSessionConnected.get()) {
            throw new FixClientPoolException("Trade session not connected for client: " + clientStreamName);
        }
        
        try {
            Session.sendToTarget(message, tradeSessionId);
            logger.debug("Sent trade message from client {}: {}", clientStreamName, message.getClass().getSimpleName());
        } catch (SessionNotFound e) {
            throw new FixClientPoolException("Trade session not found for client: " + clientStreamName, e);
        }
    }

    public void sendQuoteMessage(Message message) throws FixClientPoolException {
        if (quoteSessionId == null || !quoteSessionConnected.get()) {
            throw new FixClientPoolException("Quote session not available for client: " + clientStreamName);
        }
        
        try {
            Session.sendToTarget(message, quoteSessionId);
            logger.debug("Sent quote message from client {}: {}", clientStreamName, message.getClass().getSimpleName());
        } catch (SessionNotFound e) {
            throw new FixClientPoolException("Quote session not found for client: " + clientStreamName, e);
        }
    }

    public boolean waitForConnection(long timeout, TimeUnit unit) throws InterruptedException {
        return connectionLatch.await(timeout, unit);
    }

    public boolean isTradeSessionConnected() {
        return tradeSessionConnected.get();
    }

    public boolean isQuoteSessionConnected() {
        return quoteSessionConnected.get();
    }

    public boolean hasQuoteSession() {
        return quoteSessionId != null;
    }

    public String getClientStreamName() {
        return clientStreamName;
    }

    public ClientStatus getStatus() {
        return new ClientStatus(
            clientStreamName,
            tradeSessionConnected.get(),
            quoteSessionConnected.get(),
            tradeSessionId,
            quoteSessionId
        );
    }

    // Application interface methods
    @Override
    public void onCreate(SessionID sessionId) {
        logger.info("Session created for client {}: {}", clientStreamName, sessionId);
        
        // Determine if this is trade or quote session based on SenderCompID
        String senderCompId = sessionId.getSenderCompID();
        
        if (clientDefinition.getTradeSession() != null && 
            senderCompId.equals(clientDefinition.getTradeSession().getSenderCompID())) {
            tradeSessionId = sessionId;
            logger.info("Identified as trade session for client {}", clientStreamName);
        } else if (clientDefinition.getQuoteSession() != null && 
                   senderCompId.equals(clientDefinition.getQuoteSession().getSenderCompID())) {
            quoteSessionId = sessionId;
            logger.info("Identified as quote session for client {}", clientStreamName);
        }
    }

    @Override
    public void onLogon(SessionID sessionId) {
        logger.info("Session logged on for client {}: {}", clientStreamName, sessionId);

        if (sessionId.equals(tradeSessionId)) {
            tradeSessionConnected.set(true);
            connectionLatch.countDown();
        } else if (sessionId.equals(quoteSessionId)) {
            quoteSessionConnected.set(true);
        }

        if (sessionEventListener != null) {
            sessionEventListener.onLogon(sessionId);
        }
    }

    @Override
    public void onLogout(SessionID sessionId) {
        logger.info("Session logged out for client {}: {}", clientStreamName, sessionId);

        if (sessionId.equals(tradeSessionId)) {
            tradeSessionConnected.set(false);
        } else if (sessionId.equals(quoteSessionId)) {
            quoteSessionConnected.set(false);
        }

        if (sessionEventListener != null) {
            sessionEventListener.onLogout(sessionId);
        }
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // Add credentials for logon
        if (message instanceof Logon) {
            OtherSettings other = clientDefinition.getOther();
            if (other != null) {
                if (other.getUsername() != null && other.getPassword() != null) {
                    message.setString(Username.FIELD, other.getUsername());
                    message.setString(Password.FIELD, other.getPassword());
                }
            }
        }
        
        logger.debug("Sending admin message from client {} to {}: {}", 
                    clientStreamName, sessionId, message.getClass().getSimpleName());
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound {
        logger.debug("Received admin message for client {} from {}: {}", 
                    clientStreamName, sessionId, message.getClass().getSimpleName());
        
        if (message instanceof Reject) {
            String reason = message.isSetField(Text.FIELD) ? 
                          message.getString(Text.FIELD) : "Unknown";
            logger.warn("Received reject for client {} from {}: {}", clientStreamName, sessionId, reason);
            
            if (sessionEventListener != null) {
                sessionEventListener.onReject(sessionId, reason);
            }
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        logger.debug("Sending app message from client {} to {}: {}", 
                    clientStreamName, sessionId, message.getClass().getSimpleName());
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        logger.debug("Received app message for client {} from {}: {}", 
                    clientStreamName, sessionId, message.getClass().getSimpleName());

        if (messageListener != null) {
            messageListener.onMessage(sessionId, message);
        }
    }

    private SessionSettings createSessionSettings() throws ConfigError {
        SessionSettings settings = new SessionSettings();
        
        // Create trade session
        if (clientDefinition.getTradeSession() != null && connectionEnvironment.getTrade() != null) {
            SessionID tradeSessionId = new SessionID(
                commonSettings.getBeginString(),
                clientDefinition.getTradeSession().getSenderCompID(),
                clientDefinition.getTradeSession().getTargetCompID() != null ? 
                    clientDefinition.getTradeSession().getTargetCompID() : commonSettings.getTargetCompID()
            );
            
            Dictionary tradeDict = createSessionDictionary(
                clientDefinition.getTradeSession(),
                connectionEnvironment.getTrade(),
                "trade"
            );
            
            settings.set(tradeSessionId, tradeDict);
            this.tradeSessionId = tradeSessionId;
        }
        
        // Create quote session if available
        if (clientDefinition.getQuoteSession() != null && connectionEnvironment.getQuote() != null) {
            SessionID quoteSessionId = new SessionID(
                commonSettings.getBeginString(),
                clientDefinition.getQuoteSession().getSenderCompID(),
                clientDefinition.getQuoteSession().getTargetCompID() != null ? 
                    clientDefinition.getQuoteSession().getTargetCompID() : commonSettings.getTargetCompID()
            );
            
            Dictionary quoteDict = createSessionDictionary(
                clientDefinition.getQuoteSession(),
                connectionEnvironment.getQuote(),
                "quote"
            );
            
            settings.set(quoteSessionId, quoteDict);
            this.quoteSessionId = quoteSessionId;
        }
        
        return settings;
    }

    private String findPortForSession(String senderCompId) {
        // First try to find port in ports configuration (for trade+quote clients)
        if (portsConfig != null && portsConfig.getClients() != null) {
            for (ClientPortInfo portInfo : portsConfig.getClients()) {
                if (senderCompId.equals(portInfo.getName())) {
                    return portInfo.getPort();
                }
            }
        }
        // If not found in ports config, the port should come from connection details
        return null;
    }

    private Dictionary createSessionDictionary(SessionConfig sessionConfig,
                                               ConnectionDetails connectionDetails,
                                               String sessionType) {
        Dictionary dict = new Dictionary();

        // Helper method to safely set string values
        setIfNotNull(dict, "ConnectionType", commonSettings.getConnectionType());
        setIfNotNull(dict, "ReconnectInterval", commonSettings.getReconnectInterval());
        setIfNotNull(dict, "SLF4JLogHeartbeats", commonSettings.getSlf4jLogHeartbeats());

        // Handle both start/end time formats
        if (commonSettings.getStartDay() != null && commonSettings.getEndDay() != null) {
            setIfNotNull(dict, "StartDay", commonSettings.getStartDay());
            setIfNotNull(dict, "EndDay", commonSettings.getEndDay());
        }
        setIfNotNull(dict, "StartTime", commonSettings.getStartTime());
        setIfNotNull(dict, "EndTime", commonSettings.getEndTime());

        setIfNotNull(dict, "UseDataDictionary", commonSettings.getUseDataDictionary());

        // Handle different data dictionary formats
        setIfNotNull(dict, "TransportDataDictionary", commonSettings.getTransportDataDictionary());
        setIfNotNull(dict, "AppDataDictionary", commonSettings.getAppDataDictionary());
        setIfNotNull(dict, "DataDictionary", commonSettings.getDataDictionary());

        setIfNotNull(dict, "ValidateLengthAndChecksum", commonSettings.getValidateLengthAndChecksum());
        setIfNotNull(dict, "ValidateFieldsOutOfOrder", commonSettings.getValidateFieldsOutOfOrder());
        setIfNotNull(dict, "ValidateFieldsHaveValues", commonSettings.getValidateFieldsHaveValues());
        setIfNotNull(dict, "ValidateUserDefinedFields", commonSettings.getValidateUserDefinedFields());
        setIfNotNull(dict, "ValidateUnorderedGroupFields", commonSettings.getValidateUnorderedGroupFields());
        setIfNotNull(dict, "CheckCompID", commonSettings.getCheckCompID());
        setIfNotNull(dict, "ResetOnLogout", commonSettings.getResetOnLogout());
        setIfNotNull(dict, "ResetOnLogon", commonSettings.getResetOnLogon());
        setIfNotNull(dict, "ResetOnDisconnect", commonSettings.getResetOnDisconnect());
        setIfNotNull(dict, "DefaultApplVerID", commonSettings.getDefaultApplVerID());
        setIfNotNull(dict, "BeginString", commonSettings.getBeginString());
        setIfNotNull(dict, "HeartBtInt", commonSettings.getHeartBtInt());
        setIfNotNull(dict, "TargetCompID", commonSettings.getTargetCompID());
        setIfNotNull(dict, "SLF4JLogEventCategory", commonSettings.getSlf4jLogEventCategory());
        setIfNotNull(dict, "SLF4JLogIncomingMessageCategory", commonSettings.getSlf4jLogIncomingMessageCategory());
        setIfNotNull(dict, "SLF4JLogOutgoingMessageCategory", commonSettings.getSlf4jLogOutgoingMessageCategory());
        setIfNotNull(dict, "TargetSubID", commonSettings.getTargetSubID());

        // Connection-specific settings
        setIfNotNull(dict, "SocketConnectHost", connectionDetails.getSocketConnectHost());

        // Get port from ports configuration
        String port = findPortForSession(sessionConfig.getSenderCompID());
        if (port != null) {
            dict.setString("SocketConnectPort", port);
        } else if (connectionDetails.getSocketConnectPort() != null) {
            dict.setString("SocketConnectPort", connectionDetails.getSocketConnectPort());
        }

        // Session-specific file paths
        String basePath = commonSettings.getFileStorePath() + "/" + clientStreamName + "/" + sessionType;
        dict.setString("FileStorePath", basePath);
        dict.setString("FileLogPath", basePath + "_logs");

        return dict;
    }

    public boolean isFullyConnected() {
        // Check trade session: if configured, it must be connected
        if (isTradeSessionConfigured()) {
            if (!isTradeSessionConnected()) {
                return false;
            }
        }

        // Check quote session: if configured, it must be connected
        if (isQuoteSessionConfigured()) {
            if (!isQuoteSessionConnected()) {
                return false;
            }
        }

        // Must have at least one configured session
        return isTradeSessionConfigured() || isQuoteSessionConfigured();
    }

    private boolean isTradeSessionConfigured() {
        return clientDefinition.getTradeSession() != null && connectionEnvironment.getTrade() != null;
    }

    private boolean isQuoteSessionConfigured() {
        return clientDefinition.getQuoteSession() != null && connectionEnvironment.getQuote() != null;
    }

    // Helper method to safely set values
    private void setIfNotNull(Dictionary dict, String key, String value) {
        if (value != null) {
            dict.setString(key, value);
        }
    }

}
