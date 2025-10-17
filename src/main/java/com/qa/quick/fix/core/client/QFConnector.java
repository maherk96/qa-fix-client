package com.qa.quick.fix.core.client;

import com.qa.quick.fix.cfg.*;
import com.qa.quick.fix.core.listeners.QFInboundMessageListener;
import com.qa.quick.fix.core.listeners.QFOutboundMessageListener;
import com.qa.quick.fix.core.listeners.QFSessionEventListener;
import com.qa.quick.fix.exceptions.QFInitializationException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import quickfix.field.DefaultApplVerID;
import quickfix.field.DefaultCstmApplVerID;
import quickfix.field.MsgType;
import quickfix.field.Password;
import quickfix.field.Text;
import quickfix.field.Username;
import java.time.Duration;

import static com.qa.quick.fix.util.QFUtil.setIfNotNull;

public class QFConnector implements Application, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(QFConnector.class);

    @Getter
    private final String clientStreamName;

    private final ClientDefinition clientDefinition;
    private final ConnectionEnvironment connectionEnvironment;
    private final CommonSettings commonSettings;
    private final PortsConfiguration portsConfig;

    private SocketInitiator initiator;
    private volatile SessionID tradeSessionId;
    private volatile SessionID quoteSessionId;

    private final AtomicBoolean tradeSessionConnected = new AtomicBoolean(false);
    private final AtomicBoolean quoteSessionConnected = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile CountDownLatch connectionLatch = new CountDownLatch(0);

    private final QFInboundMessageListener qFInboundMessageListener;
    private final QFSessionEventListener qFSessionEventListener;
    private final QFOutboundMessageListener qFOutboundMessageListener;

    private final Map<SessionID, Long> lastHeartbeatTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> clientConnectionTimes = new ConcurrentHashMap<>();

    public QFConnector(
            String clientStreamName,
            CommonSettings commonSettings,
            ClientDefinition clientDefinition,
            ConnectionEnvironment connectionEnvironment,
            PortsConfiguration portsConfig,
            QFInboundMessageListener qFInboundMessageListener,
            QFSessionEventListener qFSessionEventListener,
            QFOutboundMessageListener qFOutboundMessageListener) {

        this.clientStreamName = clientStreamName;
        this.commonSettings = commonSettings;
        this.clientDefinition = clientDefinition;
        this.connectionEnvironment = connectionEnvironment;
        this.portsConfig = portsConfig;
        this.qFInboundMessageListener = qFInboundMessageListener;
        this.qFSessionEventListener = qFSessionEventListener;
        this.qFOutboundMessageListener = qFOutboundMessageListener;
    }

    public synchronized void start() throws ConfigError {
        if (!started.compareAndSet(false, true)) {
            logger.debug("Start called but already started for client {}", clientStreamName);
            return;
        }
        logger.info(
                "Starting client: [{}] [Protocol: {}, TargetCompID: {}]",
                clientStreamName,
                commonSettings.getBeginString(),
                commonSettings.getTargetCompID()
        );

        try {
            SessionSettings settings = createSessionSettings();
            int sessionsToAwait = (isTradeSessionConfigured() ? 1 : 0) + (isQuoteSessionConfigured() ? 1 : 0);
            connectionLatch = new CountDownLatch(sessionsToAwait);
            MessageStoreFactory storeFactory = new MemoryStoreFactory();
            LogFactory logFactory = new SLF4JLogFactory(settings);
            MessageFactory messageFactory = new DefaultMessageFactory();

            initiator = new SocketInitiator(this, storeFactory, settings, logFactory, messageFactory);
            initiator.start();

            logger.info("Client [{}] initiator started", clientStreamName);
        } catch (RuntimeException | ConfigError e) {
            // rollback started state and cleanup partially created initiator
            try {
                if (initiator != null) {
                    initiator.stop();
                }
            } catch (Exception stopErr) {
                logger.warn("Rollback stop failed for client {}: {}", clientStreamName, stopErr.getMessage());
            } finally {
                initiator = null;
                started.set(false);
            }
            throw e;
        }
    }

    public synchronized void stop() {
        if (!started.get()) {
            logger.debug("Stop called but not started for client {}", clientStreamName);
            return;
        }

        logger.info("Stopping client: {}", clientStreamName);
        try {
            if (initiator != null) {
                initiator.stop();
            }
        } catch (RuntimeException e) {
            logger.error("Error stopping initiator for client {}", clientStreamName, e);
        } finally {
            tradeSessionConnected.set(false);
            quoteSessionConnected.set(false);
            tradeSessionId = null;
            quoteSessionId = null;
            initiator = null;
            // Unblock any waiters
            connectionLatch = new CountDownLatch(0);
            started.set(false);
        }
    }

    /**
     * Restarts the connector synchronously without waiting for connection.
     */
    public synchronized void restart() throws ConfigError {
        stop();
        start();
    }

    /**
     * Restarts the connector and waits for all configured sessions to connect.
     * @return true if all configured sessions connected within the timeout; false otherwise
     */
    public synchronized boolean restartAndAwait(long timeout, TimeUnit unit)
            throws ConfigError, InterruptedException {
        stop();
        start();
        return waitForConnection(timeout, unit);
    }

    public void sendTradeMessage(Message message) {
        sendMessage(tradeSessionId, message, Channel.TRADE);
    }
    public void sendQuoteMessage(Message message) {
        sendMessage(quoteSessionId, message, Channel.QUOTE);
    }

    private enum Channel { TRADE, QUOTE }

    private void sendMessage(SessionID sessionId, Message message, Channel channel) {
        if (sessionId == null) {
            throw new QFInitializationException(
                    (channel == null ? "Session" : channel.name().toLowerCase() + " session") +
                            " not configured or initialized for client: " + clientStreamName);
        }

        Session session = Session.lookupSession(sessionId);
        if (session == null || !session.isLoggedOn()) {
            throw new QFInitializationException(
                    (channel == null ? "Session" : channel.name().toLowerCase() + " session") +
                            " not connected for client: " + clientStreamName);
        }

        try {
            boolean sent = session.send(message);
            if (!sent) {
                throw new QFInitializationException(
                        "Failed to send message on " + channel.name().toLowerCase() + " for client: " + clientStreamName);
            }
            logger.debug("Sent {} message from client [{}]: {}", channel.name().toLowerCase(), clientStreamName,
                    message.getClass().getSimpleName());
        } catch (RuntimeException e) {
            throw new QFInitializationException(
                    "Error sending message on " + channel.name().toLowerCase() + " for client: " + clientStreamName, e);
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
        // Indicate whether a quote session is configured for this client (capability),
        // not whether it is currently connected.
        return isQuoteSessionConfigured();
    }

    public QFClientStatus getStatus() {
        return new QFClientStatus(
                clientStreamName,
                tradeSessionConnected.get(),
                quoteSessionConnected.get(),
                tradeSessionId,
                quoteSessionId
        );
    }

    @Override
    public void onCreate(SessionID sessionId) {
        logger.info("Session created for client {}: {}", clientStreamName, sessionId);

        String senderCompID = sessionId.getSenderCompID();

        if (clientDefinition.getTradeSession() != null
                && senderCompID.equals(clientDefinition.getTradeSession().getSenderCompID())) {
            tradeSessionId = sessionId;
            logger.info("Identified as trade session for client {}", clientStreamName);
        } else if (clientDefinition.getQuoteSession() != null
                && senderCompID.equals(clientDefinition.getQuoteSession().getSenderCompID())) {
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
            connectionLatch.countDown();
        }

        if (qFSessionEventListener != null) {
            try {
                qFSessionEventListener.onLogon(sessionId);
            } catch (Exception ex) {
                logger.error("SessionEventListener.onLogon threw for client {}: {}", clientStreamName, ex.getMessage(), ex);
            }
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

        if (qFSessionEventListener != null) {
            try {
                qFSessionEventListener.onLogout(sessionId);
            } catch (Exception ex) {
                logger.error("SessionEventListener.onLogout threw for client {}: {}", clientStreamName, ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            var msgType = message.getHeader().getString(MsgType.FIELD);
            if (MsgType.LOGON.equals(msgType)) {
                var other = clientDefinition.getOther();
                if (other != null) {
                    other.getUsername()
                            .ifPresent(userName -> message.setField(new Username(userName)));
                    other.getPassword()
                            .ifPresent(password -> message.setField(new Password(password)));
                    other.getDefaultCstmApplVerID()
                            .ifPresent(verId -> message.setField(new DefaultCstmApplVerID(verId)));
                    other.getDefaultApplVerID()
                            .ifPresent(verId -> message.setField(new DefaultApplVerID(verId)));
                    other.getSenderSubID()
                            .ifPresent(senderSubId -> message.setField(new quickfix.field.SenderSubID(senderSubId)));
                    other.getTargetSubID()
                            .ifPresent(targetSubId -> message.setField(new quickfix.field.TargetSubID(targetSubId)));
                }
            }

            logger.debug(
                    "Sending admin message from client {} to {}: {}",
                    clientStreamName,
                    sessionId,
                    message.getClass().getSimpleName()
            );
        } catch (FieldNotFound e) {
            logger.warn(
                    "Could not determine message type for admin message, skipping logon defaults: {}",
                    e.getMessage()
            );
        }
    }


    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound {
        logger.debug(
                "Received admin message for client {} from {}: {}",
                clientStreamName,
                sessionId,
                message.getClass().getSimpleName()
        );

        try {
            var msgType = message.getHeader().getString(MsgType.FIELD);

            if (MsgType.REJECT.equals(msgType)) {
                var reason = message.isSetField(Text.FIELD) ? message.getString(Text.FIELD) : "Unknown";
                logger.warn(
                        "Received reject for client {} from {}: {}",
                        clientStreamName,
                        sessionId,
                        reason
                );

                if (qFSessionEventListener != null) {
                    try {
                        qFSessionEventListener.onReject(sessionId, reason);
                    } catch (Exception ex) {
                        logger.error("SessionEventListener.onReject threw for client {}: {}", clientStreamName, ex.getMessage(), ex);
                    }
                }
            }
        } catch (FieldNotFound e) {
            logger.warn("Could not determine message type for admin message: {}", e.getMessage());
            throw new FieldNotFound(e.getMessage());
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        logger.debug(
                "Sending app message from client {} to {}: {}",
                clientStreamName,
                sessionId,
                message.getClass().getSimpleName()
        );
        logger.debug("MSG_OUT: {} -> {}", sessionId, message);

        if (qFOutboundMessageListener != null) {
            try {
                qFOutboundMessageListener.onOutgoingMessage(sessionId, message);
            } catch (Exception ex) {
                logger.error("OutboundMessageListener threw for client {}: {}", clientStreamName, ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        logger.debug(
                "Received app message for client {} from {}: {}",
                clientStreamName,
                sessionId,
                message.getClass().getSimpleName()
        );

        if (qFInboundMessageListener != null) {
            try {
                qFInboundMessageListener.onMessage(sessionId, message);
            } catch (Exception ex) {
                logger.error("InboundMessageListener threw for client {}: {}", clientStreamName, ex.getMessage(), ex);
            }
        }
    }

    private SessionSettings createSessionSettings() throws ConfigError {
        SessionSettings settings = new SessionSettings();

        if (clientDefinition.getTradeSession() != null && connectionEnvironment.getTrade() != null) {
            SessionID tradeSessionId = new SessionID(
                    commonSettings.getBeginString(),
                    clientDefinition.getTradeSession().getSenderCompID(),
                    clientDefinition.getTradeSession().getTargetCompID() != null
                            ? clientDefinition.getTradeSession().getTargetCompID()
                            : commonSettings.getTargetCompID()
            );

            Dictionary tradeDict = createSessionDictionary(
                    clientDefinition.getTradeSession(),
                    connectionEnvironment.getTrade(),
                    "trade"
            );

            settings.set(tradeSessionId, tradeDict);
            this.tradeSessionId = tradeSessionId;
        }

        if (clientDefinition.getQuoteSession() != null && connectionEnvironment.getQuote() != null) {
            SessionID quoteSessionId = new SessionID(
                    commonSettings.getBeginString(),
                    clientDefinition.getQuoteSession().getSenderCompID(),
                    clientDefinition.getQuoteSession().getTargetCompID() != null
                            ? clientDefinition.getQuoteSession().getTargetCompID()
                            : commonSettings.getTargetCompID()
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

    private String getPortForSession(String senderCompId) {
        if (portsConfig != null && portsConfig.getClients() != null) {
            for (ClientPortInfo portInfo : portsConfig.getClients()) {
                if (senderCompId.equals(portInfo.getName())) {
                    return portInfo.getPort();
                }
            }
        }
        return null;
    }

    public boolean isConnected() {
        // At least one session must be configured
        if (!isTradeSessionConfigured() && !isQuoteSessionConfigured()) {
            return false;
        }

        // All configured sessions must be connected
        if (isTradeSessionConfigured() && !isTradeSessionConnected()) {
            return false;
        }

        if (isQuoteSessionConfigured() && !isQuoteSessionConnected()) {
            return false;
        }

        return true;
    }

    private boolean isTradeSessionConfigured() {
        return clientDefinition.getTradeSession() != null && connectionEnvironment.getTrade() != null;
    }

    private boolean isQuoteSessionConfigured() {
        return clientDefinition.getQuoteSession() != null && connectionEnvironment.getQuote() != null;
    }

    private Dictionary createSessionDictionary(SessionConfig sessionConfig, ConnectionDetails connectionDetails, String sessionType) {
        Dictionary dict = new Dictionary();

        setIfNotNull(dict, "ConnectionType", commonSettings.getConnectionType());
        setIfNotNull(dict, "ReconnectInterval", commonSettings.getReconnectInterval());
        setIfNotNull(dict, "SLF4JLogHeartbeats", commonSettings.getSlf4jLogHeartbeats());

        if (commonSettings.getStartDay() != null && commonSettings.getEndDay() != null) {
            setIfNotNull(dict, "StartDay", commonSettings.getStartDay());
            setIfNotNull(dict, "EndDay", commonSettings.getEndDay());
        }

        setIfNotNull(dict, "StartTime", commonSettings.getStartTime());
        setIfNotNull(dict, "EndTime", commonSettings.getEndTime());

        setIfNotNull(dict, "UseDataDictionary", commonSettings.getUseDataDictionary());
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

        setIfNotNull(dict, "SocketConnectHost", connectionDetails.getSocketConnectHost());

        var port = getPortForSession(sessionConfig.getSenderCompID());
        if (port != null) {
            dict.setString("SocketConnectPort", port);
        } else if (connectionDetails.getSocketConnectPort() != null) {
            dict.setString("SocketConnectPort", connectionDetails.getSocketConnectPort());
        }

        var basePath = commonSettings.getFileStorePath() + "/" + clientStreamName + "/" + sessionType;
        dict.setString("FileStorePath", basePath);
        dict.setString("FileLogPath", basePath + "-logs");

        return dict;
    }

    public boolean awaitConnected(Duration timeout) {
        try {
            return connectionLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        stop();
    }





}
