package com.qa.quick.fix.poc.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal FIX 4.4 acceptor that responds to NewOrderSingle (D) with
 * Pending New, New, and Filled ExecutionReports.
 */
public class SimpleFixAcceptor implements Application, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SimpleFixAcceptor.class);

    private final String beginString;
    private final String senderCompId; // server comp id
    private final String targetCompId; // expected client comp id
    private final int acceptPort;

    private SocketAcceptor acceptor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger oidCounter = new AtomicInteger(1);
    private final AtomicInteger execCounter = new AtomicInteger(1);

    public SimpleFixAcceptor(String beginString, String senderCompId, String targetCompId, int acceptPort) {
        this.beginString = beginString;
        this.senderCompId = senderCompId;
        this.targetCompId = targetCompId;
        this.acceptPort = acceptPort;
    }

    public void start() throws ConfigError {
        SessionSettings settings = new SessionSettings();
        Dictionary dict = new Dictionary();
        dict.setString(SessionFactory.SETTING_CONNECTION_TYPE, "acceptor");
        dict.setString("UseDataDictionary", "N");
        dict.setString(Session.SETTING_START_TIME, "00:00:00");
        dict.setString(Session.SETTING_END_TIME, "00:00:00");
        dict.setString(Session.SETTING_HEARTBTINT, "30");
        dict.setString("ValidateLengthAndChecksum", "N");
        dict.setString(Session.SETTING_RESET_ON_LOGON, "Y");
        dict.setString(Session.SETTING_RESET_ON_LOGOUT, "Y");
        dict.setString(Session.SETTING_RESET_ON_DISCONNECT, "Y");
        dict.setString(Acceptor.SETTING_SOCKET_ACCEPT_PORT, Integer.toString(acceptPort));

        SessionID sid = new SessionID(beginString, senderCompId, targetCompId);
        settings.set(sid, dict);

        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new SLF4JLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        acceptor = new SocketAcceptor(this, storeFactory, settings, logFactory, messageFactory);
        acceptor.start();
        log.info("SimpleFixAcceptor started on port {} for {}->{}", acceptPort, senderCompId, targetCompId);
    }

    public void stop() {
        if (acceptor != null) {
            acceptor.stop();
            scheduler.shutdownNow();
            log.info("SimpleFixAcceptor stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Acceptor session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Acceptor logon: {}", sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Acceptor logout: {}", sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) { }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) { }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend { }

    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        if (Objects.equals(msgType, MsgType.ORDER_SINGLE)) {
            NewOrderSingle nos = (NewOrderSingle) message;
            handleNewOrderSingle(nos, sessionId);
        }
    }

    private void handleNewOrderSingle(NewOrderSingle nos, SessionID sessionID) {
        try {
            String clOrdID = nos.getString(ClOrdID.FIELD);
            char side = nos.getChar(Side.FIELD);
            double ordQty = nos.getDouble(OrderQty.FIELD);
            String symbol = nos.getString(Symbol.FIELD);
            double price = nos.isSetField(Price.FIELD) ? nos.getDouble(Price.FIELD) : 100.0;

            String orderId = Integer.toString(oidCounter.getAndIncrement());

            // Pending New
            sendExecReport(sessionID, orderId, clOrdID, side, symbol, ExecType.PENDING_NEW, OrdStatus.PENDING_NEW,
                    0.0, 0.0, ordQty, 0.0);

            // New (slight delay)
            scheduler.schedule(() -> {
                try {
                    sendExecReport(sessionID, orderId, clOrdID, side, symbol, ExecType.NEW, OrdStatus.NEW,
                            0.0, 0.0, ordQty, 0.0);
                } catch (Exception e) {
                    log.error("Error sending NEW ER", e);
                }
            }, 3, TimeUnit.MILLISECONDS);

            // Filled (slight delay)
            scheduler.schedule(() -> {
                try {
                    sendExecReport(sessionID, orderId, clOrdID, side, symbol, ExecType.TRADE, OrdStatus.FILLED,
                            ordQty, price, 0.0, price);
                } catch (Exception e) {
                    log.error("Error sending FILL ER", e);
                }
            }, 6, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Error handling NewOrderSingle", e);
        }
    }

    private void sendExecReport(SessionID sessionID,
                                String orderId,
                                String clOrdId,
                                char side,
                                String symbol,
                                char execType,
                                char ordStatus,
                                double lastQty,
                                double lastPx,
                                double leavesQty,
                                double avgPx) throws SessionNotFound {
        String execId = Integer.toString(execCounter.getAndIncrement());
        ExecutionReport er = new ExecutionReport(
                new OrderID(orderId),
                new ExecID(execId),
                new ExecType(execType),
                new OrdStatus(ordStatus),
                new Side(side),
                new LeavesQty(leavesQty),
                new CumQty(ordStatus == OrdStatus.FILLED ? (lastQty) : 0.0),
                new AvgPx(avgPx)
        );

        er.set(new ClOrdID(clOrdId));
        er.set(new Symbol(symbol));
        er.set(new TransactTime(java.time.LocalDateTime.now()));
        if (lastQty > 0.0) er.set(new LastQty(lastQty));
        if (lastPx > 0.0) er.set(new LastPx(lastPx));

        Session.sendToTarget(er, sessionID);
        log.debug("Sent ER execType={} ordStatus={} to {} clOrdId={} execId={}", execType, ordStatus, sessionID, clOrdId, execId);
    }
}
