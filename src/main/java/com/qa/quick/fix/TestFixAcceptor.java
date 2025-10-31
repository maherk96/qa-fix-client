package com.qa.quick.fix;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import quickfix.*;
import quickfix.field.*;

/**
 * Test FIX Acceptor for integration testing. Simulates a FIX server that clients can connect to.
 * Supports both trade (order) and quote clients with automatic responses.
 */
@Slf4j
public class TestFixAcceptor implements Application, AutoCloseable {

  private final SocketAcceptor acceptor;
  private final List<Message> receivedMessages = new CopyOnWriteArrayList<>();
  private final List<SessionID> connectedSessions = new CopyOnWriteArrayList<>();
  private final CountDownLatch sessionLogonLatch = new CountDownLatch(1);

  @Getter private final AtomicInteger logons = new AtomicInteger(0);
  @Getter private final AtomicInteger logouts = new AtomicInteger(0);
  @Getter private final AtomicInteger fromAppCount = new AtomicInteger(0);
  @Getter private final AtomicInteger orderCount = new AtomicInteger(0);
  @Getter private final AtomicInteger quoteCount = new AtomicInteger(0);

  private boolean autoRespondToOrders = true;
  private boolean autoRespondToQuotes = true;

  /**
   * Creates a test acceptor with the given session and port. Auto-responds to orders and quotes by
   * default.
   */
  public TestFixAcceptor(SessionID sessionID, int port) throws ConfigError {
    this(sessionID, port, true, true);
  }

  /** Creates a test acceptor with configurable auto-response behavior. */
  public TestFixAcceptor(
      SessionID sessionID, int port, boolean autoRespondToOrders, boolean autoRespondToQuotes)
      throws ConfigError {
    this.autoRespondToOrders = autoRespondToOrders;
    this.autoRespondToQuotes = autoRespondToQuotes;
    SessionSettings settings = createSettings(sessionID, port);
    MessageStoreFactory storeFactory = new MemoryStoreFactory();
    LogFactory logFactory = new SLF4JLogFactory(settings);
    MessageFactory messageFactory = new DefaultMessageFactory();

    acceptor = new SocketAcceptor(this, storeFactory, settings, logFactory, messageFactory);
  }

  private SessionSettings createSettings(SessionID sessionID, int port) throws ConfigError {
    SessionSettings settings = new SessionSettings();
    Dictionary defaults = new Dictionary();

    defaults.setString("ConnectionType", "acceptor");
    defaults.setString("SocketAcceptPort", String.valueOf(port));
    defaults.setString("StartTime", "00:00:00");
    defaults.setString("EndTime", "00:00:00");
    defaults.setString("HeartBtInt", "30");
    defaults.setString("FileStorePath", "target/test-data/acceptor");
    defaults.setString("FileLogPath", "target/test-data/acceptor-logs");
    defaults.setString("UseDataDictionary", "N");
    defaults.setString("SLF4JLogHeartbeats", "N");

    settings.set(defaults);

    Dictionary sessionDict = new Dictionary();
    sessionDict.setString("BeginString", sessionID.getBeginString());
    sessionDict.setString("SenderCompID", sessionID.getSenderCompID());
    sessionDict.setString("TargetCompID", sessionID.getTargetCompID());

    settings.set(sessionID, sessionDict);

    return settings;
  }

  public void start() throws ConfigError {
    acceptor.start();
    log.info("Test FIX Acceptor started");
  }

  public void stop() {
    acceptor.stop();
    log.info("Test FIX Acceptor stopped");
  }

  public boolean awaitConnection(long timeout, TimeUnit unit) {
    try {
      return sessionLogonLatch.await(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public List<Message> getReceivedMessages() {
    return new CopyOnWriteArrayList<>(receivedMessages);
  }

  public void clearReceivedMessages() {
    receivedMessages.clear();
    fromAppCount.set(0);
  }

  public List<SessionID> getConnectedSessions() {
    return new CopyOnWriteArrayList<>(connectedSessions);
  }

  public void sendMessage(SessionID sessionID, Message message) {
    Session session = Session.lookupSession(sessionID);
    if (session != null && session.isLoggedOn()) {
      session.send(message);
    } else {
      log.warn("Cannot send message, session not connected: {}", sessionID);
    }
  }

  public void setAutoRespondToOrders(boolean autoRespond) {
    this.autoRespondToOrders = autoRespond;
  }

  public void setAutoRespondToQuotes(boolean autoRespond) {
    this.autoRespondToQuotes = autoRespond;
  }

  @Override
  public void onCreate(SessionID sessionId) {
    log.info("Test acceptor: Session created - {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    log.info("Test acceptor: Session logon - {}", sessionId);
    connectedSessions.add(sessionId);
    logons.incrementAndGet();
    sessionLogonLatch.countDown();
  }

  @Override
  public void onLogout(SessionID sessionId) {
    log.info("Test acceptor: Session logout - {}", sessionId);
    connectedSessions.remove(sessionId);
    logouts.incrementAndGet();
  }

  @Override
  public void toAdmin(Message message, SessionID sessionId) {
    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);
      log.debug("Test acceptor: Sending admin {} to {}", msgType, sessionId);
    } catch (FieldNotFound e) {
      log.debug("Test acceptor: Sending admin message to {}", sessionId);
    }
  }

  @Override
  public void fromAdmin(Message message, SessionID sessionId) {
    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);
      log.debug("Test acceptor: Received admin {} from {}", msgType, sessionId);
    } catch (FieldNotFound e) {
      log.debug("Test acceptor: Received admin message from {}", sessionId);
    }
  }

  @Override
  public void toApp(Message message, SessionID sessionId) {
    log.debug("Test acceptor: Sending app message to {}", sessionId);
  }

  @Override
  public void fromApp(Message message, SessionID sessionId) {
    log.info("Test acceptor: Received app message from {}", sessionId);
    receivedMessages.add(message);
    fromAppCount.incrementAndGet();

    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);

      // Handle New Order Single (D)
      if (MsgType.ORDER_SINGLE.equals(msgType) && autoRespondToOrders) {
        handleNewOrderSingle(message, sessionId);
        orderCount.incrementAndGet();
      }
      // Handle Quote Request (R)
      else if (MsgType.QUOTE_REQUEST.equals(msgType) && autoRespondToQuotes) {
        handleQuoteRequest(message, sessionId);
        quoteCount.incrementAndGet();
      }
      // Handle Quote (S)
      else if (MsgType.QUOTE.equals(msgType) && autoRespondToQuotes) {
        handleQuote(message, sessionId);
        quoteCount.incrementAndGet();
      }
    } catch (FieldNotFound e) {
      log.warn("Test acceptor: Could not parse message type", e);
    }
  }

  /** Handles New Order Single by sending Pending New -> New execution reports */
  private void handleNewOrderSingle(Message order, SessionID sessionId) {
    try {
      String clOrdID = order.getString(ClOrdID.FIELD);
      String symbol = order.getString(Symbol.FIELD);
      char side = order.getChar(Side.FIELD);
      double orderQty = order.getDouble(OrderQty.FIELD);
      char ordType = order.getChar(OrdType.FIELD);

      // Optional fields
      Double price = null;
      if (order.isSetField(Price.FIELD)) {
        price = order.getDouble(Price.FIELD);
      }

      // Generate order ID
      String orderID = "ORD" + System.currentTimeMillis();
      String execID1 = "EXEC" + System.currentTimeMillis() + "_1";
      String execID2 = "EXEC" + System.currentTimeMillis() + "_2";

      // Send Pending New (OrdStatus = A)
      Message pendingNew =
          createExecutionReport(
              orderID,
              execID1,
              clOrdID,
              symbol,
              side,
              orderQty,
              ordType,
              price,
              ExecType.PENDING_NEW,
              OrdStatus.PENDING_NEW,
              0,
              0,
              orderQty);
      sendMessage(sessionId, pendingNew);
      log.info("Test acceptor: Sent Pending New for ClOrdID: {}", clOrdID);

      // Small delay to simulate processing
      Thread.sleep(50);

      // Send New (OrdStatus = 0)
      Message newOrder =
          createExecutionReport(
              orderID,
              execID2,
              clOrdID,
              symbol,
              side,
              orderQty,
              ordType,
              price,
              ExecType.NEW,
              OrdStatus.NEW,
              0,
              0,
              orderQty);
      sendMessage(sessionId, newOrder);
      log.info("Test acceptor: Sent New for ClOrdID: {}", clOrdID);

    } catch (FieldNotFound | InterruptedException e) {
      log.error("Test acceptor: Error handling order", e);
    }
  }

  /** Handles Quote Request by sending a Quote response */
  private void handleQuoteRequest(Message quoteRequest, SessionID sessionId) {
    try {
      String quoteReqID = quoteRequest.getString(QuoteReqID.FIELD);
      String symbol = quoteRequest.getString(Symbol.FIELD);

      // Generate quote ID
      String quoteID = "QUOTE" + System.currentTimeMillis();

      // Create Quote message (S)
      Message quote = new Message();
      quote.getHeader().setString(MsgType.FIELD, MsgType.QUOTE);
      quote.setString(QuoteID.FIELD, quoteID);
      quote.setString(QuoteReqID.FIELD, quoteReqID);
      quote.setString(Symbol.FIELD, symbol);
      quote.setDouble(BidPx.FIELD, 100.00); // Sample bid price
      quote.setDouble(OfferPx.FIELD, 100.50); // Sample offer price
      quote.setDouble(BidSize.FIELD, 1000);
      quote.setDouble(OfferSize.FIELD, 1000);

      sendMessage(sessionId, quote);
      log.info("Test acceptor: Sent Quote response for QuoteReqID: {}", quoteReqID);

    } catch (FieldNotFound e) {
      log.error("Test acceptor: Error handling quote request", e);
    }
  }

  /** Handles Quote submission by sending a Quote Acknowledgement */
  private void handleQuote(Message quote, SessionID sessionId) {
    try {
      String quoteID = quote.getString(QuoteID.FIELD);
      String symbol = quote.getString(Symbol.FIELD);

      // Create Quote Status Report (AI) - Quote Acknowledgement
      Message quoteAck = new Message();
      quoteAck.getHeader().setString(MsgType.FIELD, "AI"); // QuoteStatusReport
      quoteAck.setString(QuoteID.FIELD, quoteID);
      quoteAck.setString(Symbol.FIELD, symbol);
      quoteAck.setInt(QuoteStatus.FIELD, 0); // Accepted

      sendMessage(sessionId, quoteAck);
      log.info("Test acceptor: Sent Quote Acknowledgement for QuoteID: {}", quoteID);

    } catch (FieldNotFound e) {
      log.error("Test acceptor: Error handling quote", e);
    }
  }

  /** Creates an execution report message */
  private Message createExecutionReport(
      String orderID,
      String execID,
      String clOrdID,
      String symbol,
      char side,
      double orderQty,
      char ordType,
      Double price,
      char execType,
      char ordStatus,
      double cumQty,
      double lastQty,
      double leavesQty) {

    Message execReport = new Message();
    execReport.getHeader().setString(MsgType.FIELD, MsgType.EXECUTION_REPORT);

    execReport.setString(OrderID.FIELD, orderID);
    execReport.setString(ExecID.FIELD, execID);
    execReport.setString(ClOrdID.FIELD, clOrdID);
    execReport.setChar(ExecType.FIELD, execType);
    execReport.setChar(OrdStatus.FIELD, ordStatus);
    execReport.setString(Symbol.FIELD, symbol);
    execReport.setChar(Side.FIELD, side);
    execReport.setDouble(OrderQty.FIELD, orderQty);
    execReport.setChar(OrdType.FIELD, ordType);

    if (price != null) {
      execReport.setDouble(Price.FIELD, price);
    }

    execReport.setDouble(CumQty.FIELD, cumQty);
    execReport.setDouble(LeavesQty.FIELD, leavesQty);

    if (lastQty > 0) {
      execReport.setDouble(LastQty.FIELD, lastQty);
    }

    return execReport;
  }

  @Override
  public void close() {
    stop();
  }

  /**
   * Main method to run the acceptor as a standalone server. Supports both QUOTE and TRADE sessions.
   * Press Ctrl+C to stop.
   */
  public static void main(String[] args) {
    TestFixAcceptor quoteAcceptor = null;
    TestFixAcceptor tradeAcceptor = null;

    try {
      // Configure QUOTE session - TRAP-A-001-QUOTE
      SessionID quoteSessionID =
          new SessionID(
              "FIX.4.4", // FIX version (from config BeginString)
              "EPAM", // SenderCompID (TargetCompID from client perspective)
              "TRAP-A-001-QUOTE" // TargetCompID (SenderCompID from client perspective)
              );
      int quotePort = 36112;

      // Configure TRADE session - TRAP-A-001-TRADE
      SessionID tradeSessionID =
          new SessionID(
              "FIX.4.4", // FIX version
              "EPAM", // SenderCompID
              "TRAP-A-001-TRADE" // TargetCompID
              );
      int tradePort = 46112;

      log.info("=================================================");
      log.info("Starting Test FIX Acceptor for TRAP-A-001 client");
      log.info("=================================================");
      log.info("QUOTE Session on port {}: {}", quotePort, quoteSessionID);
      log.info("TRADE Session on port {}: {}", tradePort, tradeSessionID);
      log.info("Press Ctrl+C to stop...");
      log.info("=================================================");

      // Create and start QUOTE acceptor
      quoteAcceptor = new TestFixAcceptor(quoteSessionID, quotePort);
      quoteAcceptor.start();
      log.info("QUOTE acceptor started on port {}", quotePort);

      // Create and start TRADE acceptor
      tradeAcceptor = new TestFixAcceptor(tradeSessionID, tradePort);
      tradeAcceptor.start();
      log.info("TRADE acceptor started on port {}", tradePort);

      log.info("=================================================");
      log.info("Both acceptors running. Waiting for connections...");
      log.info("=================================================");

      // Store references for shutdown hook
      final TestFixAcceptor finalQuoteAcceptor = quoteAcceptor;
      final TestFixAcceptor finalTradeAcceptor = tradeAcceptor;

      // Add shutdown hook for graceful shutdown
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log.info("Shutdown signal received, stopping acceptors...");
                    if (finalQuoteAcceptor != null) {
                      finalQuoteAcceptor.close();
                      log.info("QUOTE acceptor stopped");
                    }
                    if (finalTradeAcceptor != null) {
                      finalTradeAcceptor.close();
                      log.info("TRADE acceptor stopped");
                    }
                    log.info("All acceptors stopped");
                  }));

      // Keep the main thread alive
      Thread.currentThread().join();

    } catch (ConfigError e) {
      log.error("Configuration error", e);
      if (quoteAcceptor != null) quoteAcceptor.close();
      if (tradeAcceptor != null) tradeAcceptor.close();
      System.exit(1);
    } catch (InterruptedException e) {
      log.info("Main thread interrupted");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error("Error running acceptor", e);
      if (quoteAcceptor != null) quoteAcceptor.close();
      if (tradeAcceptor != null) tradeAcceptor.close();
      System.exit(1);
    }
  }
}
