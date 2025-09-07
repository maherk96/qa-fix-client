package com.qa.fix.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.Text;
import quickfix.fix44.Reject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for FIX engines that handles common functionality
 */
public abstract class AbstractFixEngine implements Application {
    protected static final Logger logger = LoggerFactory.getLogger(AbstractFixEngine.class);
    
    protected SocketAcceptor acceptor;
    protected final ConcurrentHashMap<SessionID, SessionInfo> activeSessions = new ConcurrentHashMap<>();
    protected final AtomicLong orderIdCounter = new AtomicLong(1);
    protected final AtomicLong execIdCounter = new AtomicLong(1);
    protected final AtomicLong quoteIdCounter = new AtomicLong(1);
    
    public static class SessionInfo {
        private final String sessionType;
        private final String clientId;
        
        public SessionInfo(String sessionType, String clientId) {
            this.sessionType = sessionType;
            this.clientId = clientId;
        }
        
        public String getSessionType() { return sessionType; }
        public String getClientId() { return clientId; }
    }
    
    public void start() throws ConfigError {
        SessionSettings settings = createSessionSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new SLF4JLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();
        
        acceptor = new SocketAcceptor(this, storeFactory, settings, logFactory, messageFactory);
        acceptor.start();
        
        logger.info("{} started", getEngineName());
        logPortInfo();
    }
    
    public void stop() {
        if (acceptor != null) {
            acceptor.stop();
            logger.info("{} stopped", getEngineName());
        }
    }
    
    @Override
    public void onCreate(SessionID sessionId) {
        logger.info("Session created: {}", sessionId);
        
        SessionInfo sessionInfo = determineSessionInfo(sessionId);
        activeSessions.put(sessionId, sessionInfo);
        logger.info("Session {} identified as {} for client {}", 
                   sessionId, sessionInfo.getSessionType(), sessionInfo.getClientId());
    }
    
    @Override
    public void onLogon(SessionID sessionId) {
        SessionInfo info = activeSessions.get(sessionId);
        logger.info("Client logged on: {} ({})", sessionId, info.getSessionType());
        onSessionLogon(sessionId, info);
    }
    
    @Override
    public void onLogout(SessionID sessionId) {
        SessionInfo info = activeSessions.remove(sessionId);
        logger.info("Client logged out: {} ({})", sessionId, info != null ? info.getSessionType() : "unknown");
        onSessionLogout(sessionId, info);
    }
    
    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        logger.debug("Sending admin message to {}: {}", sessionId, message.getClass().getSimpleName());
    }
    
    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        logger.debug("Received admin message from {}: {}", sessionId, message.getClass().getSimpleName());
    }
    
    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        SessionInfo info = activeSessions.get(sessionId);
        logger.info("Sending {} message to {}: {}", 
                   info != null ? info.getSessionType() : "unknown", sessionId, message.getClass().getSimpleName());
    }
    
    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound, UnsupportedMessageType {
        SessionInfo sessionInfo = activeSessions.get(sessionId);
        if (sessionInfo == null) {
            logger.warn("Received message from unknown session: {}", sessionId);
            return;
        }
        
        logger.info("Received {} message from {}: {}", 
                   sessionInfo.getSessionType(), sessionId, message.getClass().getSimpleName());
        
        try {
            handleMessage(message, sessionId, sessionInfo);
        } catch (Exception e) {
            logger.error("Error processing message from {}", sessionId, e);
            sendReject(message, sessionId, "Error processing message: " + e.getMessage());
        }
    }
    
    protected void sendReject(Message originalMessage, SessionID sessionId, String reason) {
        try {
            Reject reject = new Reject();
            reject.set(new Text(reason));
            Session.sendToTarget(reject, sessionId);
            logger.warn("Sent reject to {}: {}", sessionId, reason);
        } catch (SessionNotFound e) {
            logger.error("Session not found when sending reject", e);
        }
    }
    
    protected SessionSettings createSessionSettings() {
        SessionSettings settings = new SessionSettings();
        
        try {
            // Set default configuration
            Dictionary defaults = createDefaultSettings();
            settings.set(defaults);
            
            // Add session-specific configurations
            for (SessionConfig sessionConfig : getSessionConfigurations()) {
                SessionID sessionId = new SessionID(
                    sessionConfig.getBeginString(),
                    sessionConfig.getSenderCompId(),
                    sessionConfig.getTargetCompId()
                );
                
                Dictionary sessionDict = createSessionDictionary(sessionConfig);
                settings.set(sessionId, sessionDict);
            }
            
        } catch (ConfigError e) {
            logger.error("Error creating session settings", e);
            throw new RuntimeException(e);
        }
        
        return settings;
    }
    
    protected Dictionary createDefaultSettings() {
        Dictionary defaults = new Dictionary();
        defaults.setString("ConnectionType", "acceptor");
        defaults.setString("StartTime", "00:00:00");
        defaults.setString("EndTime", "23:59:59");
        defaults.setString("HeartBtInt", "30");
        defaults.setString("ReconnectInterval", "5");
        defaults.setString("FileStorePath", "target/data/" + getFilePrefix());
        defaults.setString("FileLogPath", "target/logs/" + getFilePrefix());
        defaults.setString("UseDataDictionary", "Y");
        defaults.setString("DataDictionary", "FIX44.xml");
        defaults.setString("ResetOnLogon", "Y");
        defaults.setString("ResetOnLogout", "Y");
        defaults.setString("ResetOnDisconnect", "Y");
        return defaults;
    }
    
    protected Dictionary createSessionDictionary(SessionConfig config) {
        Dictionary dict = new Dictionary();
        dict.setString("BeginString", config.getBeginString());
        dict.setString("SenderCompID", config.getSenderCompId());
        dict.setString("TargetCompID", config.getTargetCompId());
        dict.setString("SocketAcceptPort", String.valueOf(config.getPort()));
        return dict;
    }
    
    // Template method pattern - subclasses implement these
    protected abstract String getEngineName();
    protected abstract String getFilePrefix();
    protected abstract SessionConfig[] getSessionConfigurations();
    protected abstract SessionInfo determineSessionInfo(SessionID sessionId);
    protected abstract void handleMessage(Message message, SessionID sessionId, SessionInfo sessionInfo) 
        throws FieldNotFound, UnsupportedMessageType;
    protected abstract void logPortInfo();
    
    // Optional hooks for subclasses
    protected void onSessionLogon(SessionID sessionId, SessionInfo info) {
        // Default: do nothing
    }
    
    protected void onSessionLogout(SessionID sessionId, SessionInfo info) {
        // Default: do nothing
    }
    
    // Utility class for session configuration
    public static class SessionConfig {
        private final String beginString;
        private final String senderCompId;
        private final String targetCompId;
        private final int port;
        private final String sessionType;
        
        public SessionConfig(String beginString, String senderCompId, String targetCompId, int port, String sessionType) {
            this.beginString = beginString;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.port = port;
            this.sessionType = sessionType;
        }
        
        public String getBeginString() { return beginString; }
        public String getSenderCompId() { return senderCompId; }
        public String getTargetCompId() { return targetCompId; }
        public int getPort() { return port; }
        public String getSessionType() { return sessionType; }
    }
    
    // Common main method pattern
    protected void runEngine() {
        try {
            start();
            
            System.out.println(getEngineName() + " is running:");
            logPortInfo();
            System.out.println("Press Ctrl+C to stop.");
            
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
            
            // Wait indefinitely
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("Error running FIX engine", e);
        }
    }
}