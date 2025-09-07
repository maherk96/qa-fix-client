

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

/**
 * Simple Trade-Only FIX Engine using the reusable base class
 */
public class SimpleTradeEngine extends com.qa.fix.engine.AbstractFixEngine {
    
    private com.qa.fix.engine.handlers.TradeMessageHandler tradeHandler;
    
    @Override
    protected String getEngineName() {
        return "Simple Trade Engine";
    }
    
    @Override
    protected String getFilePrefix() {
        return "simple_trade";
    }
    
    @Override
    protected SessionConfig[] getSessionConfigurations() {
        return new SessionConfig[] {
            new SessionConfig("FIX.4.4", "TRADE_ENGINE", "CLIENT", 8001, "TRADE")
        };
    }
    
    @Override
    protected SessionInfo determineSessionInfo(SessionID sessionId) {
        return new SessionInfo("TRADE", sessionId.getSenderCompID());
    }
    
    @Override
    protected void logPortInfo() {
        System.out.println("  Trade session on port 8001");
    }
    
    @Override
    protected void onSessionLogon(SessionID sessionId, SessionInfo info) {
        if (tradeHandler == null) {
            tradeHandler = new com.qa.fix.engine.handlers.TradeMessageHandler(orderIdCounter, execIdCounter);
        }
    }
    
    @Override
    protected void handleMessage(Message message, SessionID sessionId, SessionInfo sessionInfo) 
            throws FieldNotFound, UnsupportedMessageType {
        if ("TRADE".equals(sessionInfo.getSessionType())) {
            tradeHandler.handleMessage(message, sessionId);
        } else {
            throw new UnsupportedMessageType();
        }
    }
    
    public static void main(String[] args) {
        new SimpleTradeEngine().runEngine();
    }
}

/**
 * Trade+Quote FIX Engine using the reusable base class
 */
class MultiSessionEngine extends com.qa.fix.engine.AbstractFixEngine {
    
    private com.qa.fix.engine.handlers.TradeMessageHandler tradeHandler;
    private QuoteMessageHandler quoteHandler;
    
    @Override
    protected String getEngineName() {
        return "Multi-Session Engine";
    }
    
    @Override
    protected String getFilePrefix() {
        return "multi_session";
    }
    
    @Override
    protected SessionConfig[] getSessionConfigurations() {
        return new SessionConfig[] {
            new SessionConfig("FIX.4.4", "TRADE_ENGINE", "CLIENT", 8001, "TRADE"),
            new SessionConfig("FIX.4.4", "QUOTE_ENGINE", "CLIENT", 8002, "QUOTE")
        };
    }
    
    @Override
    protected SessionInfo determineSessionInfo(SessionID sessionId) {
        String senderCompId = sessionId.getSenderCompID();
        if (senderCompId.contains("QUOTE")) {
            return new SessionInfo("QUOTE", sessionId.getTargetCompID());
        } else {
            return new SessionInfo("TRADE", sessionId.getTargetCompID());
        }
    }
    
    @Override
    protected void logPortInfo() {
        System.out.println("  Trade session on port 8001");
        System.out.println("  Quote session on port 8002");
    }
    
    @Override
    protected void onSessionLogon(SessionID sessionId, SessionInfo info) {
        if ("TRADE".equals(info.getSessionType()) && tradeHandler == null) {
            tradeHandler = new com.qa.fix.engine.handlers.TradeMessageHandler(orderIdCounter, execIdCounter);
        } else if ("QUOTE".equals(info.getSessionType()) && quoteHandler == null) {
            quoteHandler = new QuoteMessageHandler(quoteIdCounter);
        }
    }
    
    @Override
    protected void handleMessage(Message message, SessionID sessionId, SessionInfo sessionInfo) 
            throws FieldNotFound, UnsupportedMessageType {
        switch (sessionInfo.getSessionType()) {
            case "TRADE":
                if (tradeHandler == null) {
                    throw new UnsupportedMessageType();
                }
                tradeHandler.handleMessage(message, sessionId);
                break;
            case "QUOTE":
                if (quoteHandler == null) {
                    throw new UnsupportedMessageType();
                }
                quoteHandler.handleMessage(message, sessionId);
                break;
            default:
                throw new UnsupportedMessageType();
        }
    }
    
    public static void main(String[] args) {
        new MultiSessionEngine().runEngine();
    }
}

/**
 * Example of how easy it is to create new engine variants
 * This one supports multiple trade sessions on different ports
 */
class MultiTradeEngine extends com.qa.fix.engine.AbstractFixEngine {
    
    private com.qa.fix.engine.handlers.TradeMessageHandler tradeHandler;
    
    @Override
    protected String getEngineName() {
        return "Multi-Trade Engine";
    }
    
    @Override
    protected String getFilePrefix() {
        return "multi_trade";
    }
    
    @Override
    protected SessionConfig[] getSessionConfigurations() {
        return new SessionConfig[] {
            new SessionConfig("FIX.4.4", "TRADE_ENGINE_1", "CLIENT", 8001, "TRADE"),
            new SessionConfig("FIX.4.4", "TRADE_ENGINE_2", "CLIENT", 8003, "TRADE"),
            new SessionConfig("FIX.4.4", "TRADE_ENGINE_3", "CLIENT", 8004, "TRADE")
        };
    }
    
    @Override
    protected SessionInfo determineSessionInfo(SessionID sessionId) {
        return new SessionInfo("TRADE", sessionId.getSenderCompID());
    }
    
    @Override
    protected void logPortInfo() {
        System.out.println("  Trade session 1 on port 8001");
        System.out.println("  Trade session 2 on port 8003");
        System.out.println("  Trade session 3 on port 8004");
    }
    
    @Override
    protected void onSessionLogon(SessionID sessionId, SessionInfo info) {
        if (tradeHandler == null) {
            tradeHandler = new com.qa.fix.engine.handlers.TradeMessageHandler(orderIdCounter, execIdCounter);
        }
    }
    
    @Override
    protected void handleMessage(Message message, SessionID sessionId, SessionInfo sessionInfo) 
            throws FieldNotFound, UnsupportedMessageType {
        if ("TRADE".equals(sessionInfo.getSessionType())) {
            tradeHandler.handleMessage(message, sessionId);
        } else {
            throw new UnsupportedMessageType();
        }
    }
    
    public static void main(String[] args) {
        new MultiTradeEngine().runEngine();
    }
}