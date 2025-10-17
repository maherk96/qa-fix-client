package com.qa.quick.fix.core.client;

import com.qa.quick.fix.cfg.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import quickfix.SessionID;
import quickfix.SocketInitiator;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class QFConnectorTest {

    private QFConnector connectorUnderTest;

    @AfterEach
    void tearDown() {
        if (connectorUnderTest != null) {
            try {
                connectorUnderTest.stop();
            } catch (Exception ignored) {
                // ignore to avoid failing tests that intentionally throw on stop()
            }
        }
    }

    // Helpers
    private static CommonSettings commonDefaults() {
        CommonSettings cs = new CommonSettings();
        cs.setConnectionType("initiator");
        cs.setBeginString("FIX.4.4");
        cs.setHeartBtInt("30");
        cs.setStartTime("00:00:00");
        cs.setEndTime("23:59:59");
        cs.setFileStorePath("build/qf-store");
        cs.setTargetCompID("TARGET");
        return cs;
    }

    private static ClientDefinition withTrade(boolean trade, boolean quote) {
        ClientDefinition def = new ClientDefinition();
        if (trade) def.setTradeSession(new SessionConfig("SENDER_T", null));
        if (quote) def.setQuoteSession(new SessionConfig("SENDER_Q", null));
        return def;
    }

    private static ConnectionEnvironment env(boolean trade, boolean quote) {
        ConnectionEnvironment env = new ConnectionEnvironment();
        if (trade) env.setTrade(new ConnectionDetails("127.0.0.1", "9876"));
        if (quote) env.setQuote(new ConnectionDetails("127.0.0.1", "9877"));
        return env;
    }

    private static QFConnector newConnector(String name, CommonSettings cs, ClientDefinition def, ConnectionEnvironment env) {
        return new QFConnector(name, cs, def, env, null, null, null, null);
    }

    private static <T> T getPrivate(Object obj, String fieldName, Class<T> type) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return type.cast(f.get(obj));
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setPrivate(Object obj, String fieldName, Object value) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 1.1 Start Tests
    @Test
    void testStart_Success() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));

        assertDoesNotThrow(() -> connectorUnderTest.start());

        AtomicBoolean started = getPrivate(connectorUnderTest, "started", AtomicBoolean.class);
        assertThat(started.get()).isTrue();

        SocketInitiator initiator = getPrivate(connectorUnderTest, "initiator", SocketInitiator.class);
        assertThat(initiator).isNotNull();

        CountDownLatch latch = getPrivate(connectorUnderTest, "connectionLatch", CountDownLatch.class);
        assertThat(latch.getCount()).isEqualTo(1);
    }

    @Test
    void testStart_AlreadyStarted() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));

        assertDoesNotThrow(() -> connectorUnderTest.start());
        SocketInitiator first = getPrivate(connectorUnderTest, "initiator", SocketInitiator.class);

        assertDoesNotThrow(() -> connectorUnderTest.start());
        SocketInitiator second = getPrivate(connectorUnderTest, "initiator", SocketInitiator.class);
        assertThat(second).isSameAs(first);
    }

    @Test
    void testStart_ConfigError_RollsBack() {
        CommonSettings cs = commonDefaults();
        cs.setBeginString(null); // cause failure during SessionID creation
        connectorUnderTest = newConnector("C1", cs, withTrade(true, false), env(true, false));

        assertThrows(Exception.class, () -> connectorUnderTest.start());

        AtomicBoolean started = getPrivate(connectorUnderTest, "started", AtomicBoolean.class);
        assertThat(started.get()).isFalse();
        assertThat(getPrivate(connectorUnderTest, "initiator", SocketInitiator.class)).isNull();
    }

    @Test
    void testStart_BothSessionsConfigured_InitializesCorrectLatchCount() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, true), env(true, true));
        assertDoesNotThrow(() -> connectorUnderTest.start());
        CountDownLatch latch = getPrivate(connectorUnderTest, "connectionLatch", CountDownLatch.class);
        assertThat(latch.getCount()).isEqualTo(2);
    }

    @Test
    void testStart_OnlyTradeSessionConfigured_InitializesCorrectLatchCount() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));
        assertDoesNotThrow(() -> connectorUnderTest.start());
        CountDownLatch latch = getPrivate(connectorUnderTest, "connectionLatch", CountDownLatch.class);
        assertThat(latch.getCount()).isEqualTo(1);
    }

    @Test
    void testStart_OnlyQuoteSessionConfigured_InitializesCorrectLatchCount() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(false, true), env(false, true));
        assertDoesNotThrow(() -> connectorUnderTest.start());
        CountDownLatch latch = getPrivate(connectorUnderTest, "connectionLatch", CountDownLatch.class);
        assertThat(latch.getCount()).isEqualTo(1);
    }

    // 1.2 Stop Tests
    @Test
    void testStop_WhenStarted_Success() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));

        // Mock initiator and mark as started
        SocketInitiator initiator = mock(SocketInitiator.class);
        setPrivate(connectorUnderTest, "initiator", initiator);
        setPrivate(connectorUnderTest, "started", new AtomicBoolean(true));
        setPrivate(connectorUnderTest, "tradeSessionConnected", new AtomicBoolean(true));
        setPrivate(connectorUnderTest, "quoteSessionConnected", new AtomicBoolean(true));
        setPrivate(connectorUnderTest, "tradeSessionId", new SessionID("FIX.4.4", "SENDER_T", "TARGET"));
        setPrivate(connectorUnderTest, "quoteSessionId", new SessionID("FIX.4.4", "SENDER_Q", "TARGET"));
        setPrivate(connectorUnderTest, "connectionLatch", new CountDownLatch(1));
        setPrivate(connectorUnderTest, "configuredSessionCount", 1);

        assertDoesNotThrow(() -> connectorUnderTest.stop());

        verify(initiator, times(1)).stop();
        assertThat(getPrivate(connectorUnderTest, "initiator", SocketInitiator.class)).isNull();
        assertThat(getPrivate(connectorUnderTest, "started", AtomicBoolean.class).get()).isFalse();
        assertThat(getPrivate(connectorUnderTest, "tradeSessionConnected", AtomicBoolean.class).get()).isFalse();
        assertThat(getPrivate(connectorUnderTest, "quoteSessionConnected", AtomicBoolean.class).get()).isFalse();
        assertThat(getPrivate(connectorUnderTest, "tradeSessionId", SessionID.class)).isNull();
        assertThat(getPrivate(connectorUnderTest, "quoteSessionId", SessionID.class)).isNull();
        assertThat(getPrivate(connectorUnderTest, "connectionLatch", CountDownLatch.class).getCount()).isZero();
    }

    @Test
    void testStop_WhenNotStarted_NoOp() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));
        SocketInitiator initiator = mock(SocketInitiator.class);
        setPrivate(connectorUnderTest, "initiator", initiator);
        setPrivate(connectorUnderTest, "started", new AtomicBoolean(false));

        assertDoesNotThrow(() -> connectorUnderTest.stop());
        verifyNoInteractions(initiator);
    }

    @Test
    void testStop_InitiatorThrowsException_StillCleansUp() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));
        SocketInitiator initiator = mock(SocketInitiator.class);
        doThrow(new RuntimeException("boom")).when(initiator).stop();
        setPrivate(connectorUnderTest, "initiator", initiator);
        setPrivate(connectorUnderTest, "started", new AtomicBoolean(true));
        setPrivate(connectorUnderTest, "connectionLatch", new CountDownLatch(1));
        setPrivate(connectorUnderTest, "configuredSessionCount", 1);
        setPrivate(connectorUnderTest, "tradeSessionId", new SessionID("FIX.4.4", "SENDER_T", "TARGET"));

        assertDoesNotThrow(() -> connectorUnderTest.stop());
        assertThat(getPrivate(connectorUnderTest, "initiator", SocketInitiator.class)).isNull();
        assertThat(getPrivate(connectorUnderTest, "started", AtomicBoolean.class).get()).isFalse();
        assertThat(getPrivate(connectorUnderTest, "connectionLatch", CountDownLatch.class).getCount()).isZero();
    }

    @Test
    void testStop_UnblocksWaitingThreads() throws Exception {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));
        connectorUnderTest.start();

        // Wait in a background thread
        CountDownLatch waiterStarted = new CountDownLatch(1);
        var waiter = new Thread(() -> {
            try {
                waiterStarted.countDown();
                connectorUnderTest.waitForConnection(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
        });
        waiter.start();
        waiterStarted.await(1, TimeUnit.SECONDS);
        // Now stop, which should drain the latch and unblock the waiter
        connectorUnderTest.stop();
        waiter.join(2000);
        assertThat(waiter.isAlive()).isFalse();
    }

    // 1.3 Restart Tests
    @Test
    void testRestart_Success() {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));
        assertDoesNotThrow(() -> connectorUnderTest.start());
        assertDoesNotThrow(() -> connectorUnderTest.restart());
        assertThat(getPrivate(connectorUnderTest, "started", AtomicBoolean.class).get()).isTrue();
    }

    @Test
    void testRestart_StopFails_StillStarts() {
        CommonSettings cs = commonDefaults();
        ClientDefinition def = withTrade(true, false);
        ConnectionEnvironment en = env(true, false);

        connectorUnderTest = new QFConnector("C1", cs, def, en, null, null, null, null) {
            @Override
            public synchronized void stop() {
                throw new RuntimeException("simulated stop failure");
            }
        };

        assertDoesNotThrow(() -> connectorUnderTest.restart());
        assertThat(getPrivate(connectorUnderTest, "started", AtomicBoolean.class).get()).isTrue();
    }

    @Test
    void testRestartAndAwait_Success() throws Exception {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));

        // Trigger logon after restart in a background thread
        new Thread(() -> {
            try {
                Thread.sleep(200);
                SessionID sid = new SessionID("FIX.4.4", "SENDER_T", "TARGET");
                connectorUnderTest.onCreate(sid);
                connectorUnderTest.onLogon(sid);
            } catch (InterruptedException ignored) { }
        }).start();

        boolean ok = connectorUnderTest.restartAndAwait(2, TimeUnit.SECONDS);
        assertThat(ok).isTrue();
    }

    @Test
    void testRestartAndAwait_Timeout() throws Exception {
        connectorUnderTest = newConnector("C1", commonDefaults(), withTrade(true, false), env(true, false));
        boolean ok = connectorUnderTest.restartAndAwait(200, TimeUnit.MILLISECONDS);
        assertThat(ok).isFalse();
    }

    @Test
    void testRestartAndAwait_StopFails_StillWaits() throws Exception {
        CommonSettings cs = commonDefaults();
        ClientDefinition def = withTrade(true, false);
        ConnectionEnvironment en = env(true, false);

        connectorUnderTest = new QFConnector("C1", cs, def, en, null, null, null, null) {
            @Override
            public synchronized void stop() {
                throw new RuntimeException("simulated stop failure");
            }
        };

        new Thread(() -> {
            try {
                Thread.sleep(200);
                SessionID sid = new SessionID("FIX.4.4", "SENDER_T", "TARGET");
                connectorUnderTest.onCreate(sid);
                connectorUnderTest.onLogon(sid);
            } catch (InterruptedException ignored) { }
        }).start();

        boolean ok = connectorUnderTest.restartAndAwait(2, TimeUnit.SECONDS);
        assertThat(ok).isTrue();
    }
}
