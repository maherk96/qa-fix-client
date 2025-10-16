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

import static com.qa.quick.fix.util.QFUtil.setIfNotNull;

public class QFConnector implements Application {

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

    private final CountDownLatch connectionLatch = new CountDownLatch(1);

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

    public void start() throws ConfigError {
        logger.info(
                "Starting client: [{}] [Protocol: {}, TargetCompID: {}]",
                clientStreamName,
                commonSettings.getBeginString(),
                commonSettings.getTargetCompID()
        );

        SessionSettings settings = createSessionSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new SLF4JLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        initiator = new SocketInitiator(this, storeFactory, settings, logFactory, messageFactory);
        initiator.start();

        logger.info("Client [{}] initiator started", clientStreamName);
    }

    public void stop() {
        if (initiator != null) {
            logger.info("Stopping client: {}", clientStreamName);
            initiator.stop();
            tradeSessionConnected.set(false);
            quoteSessionConnected.set(false);
        }
    }

    public void sendTradeMessage(Message message) {
        if (tradeSessionId == null || !tradeSessionConnected.get()) {
            throw new QFInitializationException(
                    "Trade session not connected for client: " + clientStreamName
            );
        }

        try {
            Session.sendToTarget(message, tradeSessionId);
            logger.debug(
                    "Sent trade message from client [{}]: {}",
                    clientStreamName,
                    message.getClass().getSimpleName()
            );
        } catch (SessionNotFound e) {
            throw new QFInitializationException(
                    "Trade session not found for client: " + clientStreamName, e
            );
        }
    }
    public void sendQuoteMessage(Message message) {
        if (quoteSessionId == null || !quoteSessionConnected.get()) {
            throw new QFInitializationException(
                    "Quote session not available for client: " + clientStreamName
            );
        }
        try {
            Session.sendToTarget(message, quoteSessionId);
            logger.debug(
                    "Sent quote message from client {}: {}",
                    clientStreamName,
                    message.getClass().getSimpleName()
            );
        } catch (SessionNotFound e) {
            throw new QFInitializationException(
                    "Quote session not found for client: " + clientStreamName, e
            );
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
        }

        if (qFSessionEventListener != null) {
            qFSessionEventListener.onLogon(sessionId);
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
            qFSessionEventListener.onLogout(sessionId);
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
                    qFSessionEventListener.onReject(sessionId, reason);
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
            qFOutboundMessageListener.onOutgoingMessage(sessionId, message);
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
            qFInboundMessageListener.onMessage(sessionId, message);
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
        if (isTradeSessionConfigured()) {
            if (!isTradeSessionConnected()) {
                return false;
            }
        }

        if (isQuoteSessionConfigured()) {
            if (!isQuoteSessionConnected()) {
                return false;
            }
        }

        return isTradeSessionConfigured() || isQuoteSessionConfigured();
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





}