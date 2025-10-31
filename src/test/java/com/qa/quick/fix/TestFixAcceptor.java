package com.qa.quick.fix;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import quickfix.*;
import quickfix.field.MsgType;

/**
 * Test FIX Acceptor for integration testing. Simulates a FIX server that clients can connect to.
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

  /** Creates a test acceptor with the given session and port. */
  public TestFixAcceptor(SessionID sessionID, int port) throws ConfigError {
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
  }

  @Override
  public void close() {
    stop();
  }
}
