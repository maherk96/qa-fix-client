
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.Message;
import quickfix.field.*;
import quickfix.fix44.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles trade-related FIX messages
 */
public class TradeMessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(TradeMessageHandler.class);
    
    private final AtomicLong orderIdCounter;
    private final AtomicLong execIdCounter;
    
    public TradeMessageHandler(AtomicLong orderIdCounter, AtomicLong execIdCounter) {
        this.orderIdCounter = orderIdCounter;
        this.execIdCounter = execIdCounter;
    }
    
    public void handleMessage(Message message, SessionID sessionId) throws FieldNotFound {
        if (message instanceof NewOrderSingle) {
            handleNewOrderSingle((NewOrderSingle) message, sessionId);
        } else if (message instanceof OrderCancelRequest) {
            handleOrderCancelRequest((OrderCancelRequest) message, sessionId);
        } else if (message instanceof OrderCancelReplaceRequest) {
            handleOrderReplaceRequest((OrderCancelReplaceRequest) message, sessionId);
        } else {
            logger.warn("Unsupported trade message type: {}", message.getClass().getSimpleName());
        }
    }
    
    private void handleNewOrderSingle(NewOrderSingle order, SessionID sessionId) throws FieldNotFound {
        String clOrdID = order.getClOrdID().getValue();
        String symbol = order.getSymbol().getValue();
        Side side = order.getSide();
        double quantity = order.getOrderQty().getValue();
        
        logger.info("Processing trade order: ClOrdID={}, Symbol={}, Side={}, Qty={}", 
                   clOrdID, symbol, side, quantity);
        
        // Send acknowledgment
        sendExecutionReport(sessionId, clOrdID, symbol, side, quantity, 
                          ExecType.PENDING_NEW, OrdStatus.PENDING_NEW, 0, 0);
        
        // Simulate processing delay
        simulateProcessingDelay();
        
        // Send fill
        double fillPrice = generateRandomPrice();
        sendExecutionReport(sessionId, clOrdID, symbol, side, quantity,
                          ExecType.FILL, OrdStatus.FILLED, quantity, fillPrice);
    }
    
    private void handleOrderCancelRequest(OrderCancelRequest cancelRequest, SessionID sessionId) throws FieldNotFound {
        String clOrdID = cancelRequest.getClOrdID().getValue();
        String origClOrdID = cancelRequest.getOrigClOrdID().getValue();
        
        logger.info("Processing cancel request: ClOrdID={}, OrigClOrdID={}", clOrdID, origClOrdID);
        
        OrderCancelReject cancelReject = new OrderCancelReject();
        cancelReject.set(new ClOrdID(clOrdID));
        cancelReject.set(new OrigClOrdID(origClOrdID));
        cancelReject.set(new OrdStatus(OrdStatus.CANCELED));
        cancelReject.set(new CxlRejReason(CxlRejReason.UNKNOWN_ORDER));
        cancelReject.set(new Text("Order already filled"));
        
        sendMessage(cancelReject, sessionId);
    }
    
    private void handleOrderReplaceRequest(OrderCancelReplaceRequest replaceRequest, SessionID sessionId) throws FieldNotFound {
        String clOrdID = replaceRequest.getClOrdID().getValue();
        String origClOrdID = replaceRequest.getOrigClOrdID().getValue();
        
        logger.info("Processing replace request: ClOrdID={}, OrigClOrdID={}", clOrdID, origClOrdID);
        
        OrderCancelReject cancelReject = new OrderCancelReject();
        cancelReject.set(new ClOrdID(clOrdID));
        cancelReject.set(new OrigClOrdID(origClOrdID));
        cancelReject.set(new OrdStatus(OrdStatus.REJECTED));
        cancelReject.set(new CxlRejReason(CxlRejReason.OTHER));
        cancelReject.set(new Text("Replace not supported"));
        
        sendMessage(cancelReject, sessionId);
    }
    
    private void sendExecutionReport(SessionID sessionId, String clOrdID, String symbol, 
                                   Side side, double orderQty, char execType, char ordStatus,
                                   double lastQty, double lastPx) {
        try {
            ExecutionReport execReport = new ExecutionReport();
            execReport.set(new ClOrdID(clOrdID));
            execReport.set(new OrderID(String.valueOf(orderIdCounter.getAndIncrement())));
            execReport.set(new ExecID(String.valueOf(execIdCounter.getAndIncrement())));
            execReport.set(new ExecType(execType));
            execReport.set(new OrdStatus(ordStatus));
            execReport.set(new Symbol(symbol));
            execReport.set(side);
            execReport.set(new OrderQty(orderQty));
            execReport.set(new LeavesQty(orderQty - lastQty));
            execReport.set(new CumQty(lastQty));
            execReport.set(new AvgPx(lastPx));
            
            if (lastQty > 0) {
                execReport.set(new LastQty(lastQty));
                execReport.set(new LastPx(lastPx));
            }
            
            sendMessage(execReport, sessionId);
            logger.info("Sent execution report: ClOrdID={}, ExecType={}, OrdStatus={}", 
                       clOrdID, execType, ordStatus);
        } catch (Exception e) {
            logger.error("Error sending execution report", e);
        }
    }
    
    private void sendMessage(Message message, SessionID sessionId) {
        try {
            Session.sendToTarget(message, sessionId);
        } catch (SessionNotFound e) {
            logger.error("Session not found when sending message", e);
        }
    }
    
    private void simulateProcessingDelay() {
        try {            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private double generateRandomPrice() {
        return 100.0 + Math.random() * 50; // Random price between 100-150
    }
}

