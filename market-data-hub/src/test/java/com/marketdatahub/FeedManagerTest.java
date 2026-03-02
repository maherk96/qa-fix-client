package com.marketdatahub;

import com.marketdatahub.config.MarketConfig;
import com.marketdatahub.config.MarketDataHubConfig;
import com.marketdatahub.config.SolaceConfig;
import com.marketdatahub.config.TibrvConfig;
import com.marketdatahub.config.TransportType;
import com.marketdatahub.feed.FeedManager;
import com.marketdatahub.feed.FeedStatus;
import com.marketdatahub.feed.MarketFeed;
import com.marketdatahub.feed.MarketFeedFactory;
import com.marketdatahub.listener.MarketDataListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link FeedManager}.
 *
 * <p>Uses a {@link ControllableFeed} / {@link ControllableFactory} pair so no real
 * transport connections are made. Tests are grouped into nested classes:
 * <ul>
 *   <li>{@link Construction}   — constructor null-argument validation</li>
 *   <li>{@link StartFeeds}     — selective start, unknown market, idempotent start, null list</li>
 *   <li>{@link StopFeeds}      — selective stop, no-op on non-running, null list</li>
 *   <li>{@link StopAll}        — stops every running feed</li>
 *   <li>{@link StatusReporting}— getStatus lifecycle transitions</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FeedManagerTest {

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    /** Controllable feed whose start/stop calls are tracked via AtomicBoolean. */
    static class ControllableFeed implements MarketFeed {
        private final String marketName;
        private final AtomicBoolean running = new AtomicBoolean(false);
        volatile MarketDataListener startedListener;

        ControllableFeed(String marketName) {
            this.marketName = marketName;
        }

        @Override
        public void start(MarketDataListener listener) {
            if (!running.compareAndSet(false, true)) {
                throw new IllegalStateException("Already running");
            }
            this.startedListener = listener;
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override public String getMarketName() { return marketName; }
        @Override public boolean isRunning()    { return running.get(); }
    }

    /** Factory that creates {@link ControllableFeed} instances and records them. */
    static class ControllableFactory extends MarketFeedFactory {
        private final Map<String, ControllableFeed> createdFeeds;

        ControllableFactory(Map<String, ControllableFeed> createdFeeds) {
            this.createdFeeds = createdFeeds;
        }

        @Override
        public MarketFeed createFeed(String marketName, MarketConfig config) {
            ControllableFeed feed = new ControllableFeed(marketName);
            createdFeeds.put(marketName, feed);
            return feed;
        }
    }

    // -----------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------

    private MarketDataHubConfig config;
    private MarketDataListener  listener;

    @BeforeEach
    void setUp() {
        config = MarketDataHubConfig.builder()
                .addMarket("EBS", TransportType.SOLACE, SolaceConfig.builder()
                        .host("tcp://broker:55555").vpn("default")
                        .username("user").password("pass")
                        .topic("EQ/marketData/>").build())
                .addMarket("REUTERS", TransportType.TIBRV, TibrvConfig.builder()
                        .service("7500").network(";239.255.0.1")
                        .daemon("localhost:7500").subject("REUTERS.>").build())
                .build();
        listener = mock(MarketDataListener.class);
    }

    private FeedManager buildManager(Map<String, ControllableFeed> feedMap) {
        return new FeedManager(config, new ControllableFactory(feedMap), listener);
    }

    // =========================================================================
    @Nested
    @DisplayName("Construction — null-argument validation")
    class Construction {

        @Test
        @DisplayName("Null config throws IllegalArgumentException")
        void nullConfigThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedManager(null, new MarketFeedFactory(), listener));
        }

        @Test
        @DisplayName("Null factory throws IllegalArgumentException")
        void nullFactoryThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedManager(config, null, listener));
        }

        @Test
        @DisplayName("Null listener throws IllegalArgumentException")
        void nullListenerThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedManager(config, new MarketFeedFactory(), null));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("startFeeds")
    class StartFeeds {

        @Test
        @DisplayName("startFeeds starts only the requested markets")
        void startsOnlyRequestedMarkets() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", true);

            assertTrue(feedMap.get("EBS").isRunning(), "EBS should be running");
            assertNull(feedMap.get("REUTERS"),          "REUTERS should not be created yet");
            mgr.stopAll();
        }

        @Test
        @DisplayName("startFeeds for both markets — both become RUNNING")
        void startsBothMarkets() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS", "REUTERS"));
            awaitRunning(feedMap, "EBS",     true);
            awaitRunning(feedMap, "REUTERS", true);

            assertTrue(feedMap.get("EBS").isRunning());
            assertTrue(feedMap.get("REUTERS").isRunning());
            mgr.stopAll();
        }

        @Test
        @DisplayName("startFeeds with an unknown market throws IllegalArgumentException")
        void unknownMarketThrows() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            assertThrows(IllegalArgumentException.class,
                    () -> mgr.startFeeds(List.of("UNKNOWN")));
            mgr.stopAll();
        }

        @Test
        @DisplayName("Calling startFeeds twice on the same market is idempotent")
        void doubleStartIsIdempotent() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", true);

            // second call must not throw and the feed must still be running
            assertDoesNotThrow(() -> mgr.startFeeds(List.of("EBS")));
            assertTrue(feedMap.get("EBS").isRunning());
            mgr.stopAll();
        }

        @Test
        @DisplayName("startFeeds with null list throws IllegalArgumentException")
        void nullListThrows() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            assertThrows(IllegalArgumentException.class,
                    () -> mgr.startFeeds(null));
            mgr.stopAll();
        }

        @Test
        @DisplayName("The listener passed to FeedManager is forwarded to each feed on start")
        void listenerIsForwardedToFeed() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", true);

            assertSame(listener, feedMap.get("EBS").startedListener,
                    "feed should receive the exact listener registered with FeedManager");
            mgr.stopAll();
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("stopFeeds")
    class StopFeeds {

        @Test
        @DisplayName("stopFeeds stops only the specified market, leaves others running")
        void stopsOnlySpecifiedMarket() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS", "REUTERS"));
            awaitRunning(feedMap, "EBS",     true);
            awaitRunning(feedMap, "REUTERS", true);

            mgr.stopFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", false);

            assertFalse(feedMap.get("EBS").isRunning(),     "EBS should be stopped");
            assertTrue(feedMap.get("REUTERS").isRunning(),  "REUTERS should still be running");
            mgr.stopAll();
        }

        @Test
        @DisplayName("stopFeeds on a never-started market is a no-op")
        void stopNonRunningMarketIsNoOp() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            // EBS was never started — should not throw
            assertDoesNotThrow(() -> mgr.stopFeeds(List.of("EBS")));
            mgr.stopAll();
        }

        @Test
        @DisplayName("stopFeeds with null list throws IllegalArgumentException")
        void nullListThrows() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            assertThrows(IllegalArgumentException.class,
                    () -> mgr.stopFeeds(null));
            mgr.stopAll();
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("stopAll")
    class StopAll {

        @Test
        @DisplayName("stopAll stops every running feed")
        void stopsAllRunningFeeds() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS", "REUTERS"));
            awaitRunning(feedMap, "EBS",     true);
            awaitRunning(feedMap, "REUTERS", true);

            mgr.stopAll();

            assertFalse(feedMap.get("EBS").isRunning());
            assertFalse(feedMap.get("REUTERS").isRunning());
        }

        @Test
        @DisplayName("stopAll on an idle manager does not throw")
        void stopAllIdleManagerDoesNotThrow() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);
            assertDoesNotThrow(mgr::stopAll);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Status reporting — getStatus")
    class StatusReporting {

        @Test
        @DisplayName("getStatus returns STOPPED for all markets before any feed starts")
        void allStoppedBeforeStart() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            Map<String, FeedStatus> status = mgr.getStatus();
            assertEquals(FeedStatus.STOPPED, status.get("EBS"));
            assertEquals(FeedStatus.STOPPED, status.get("REUTERS"));
            mgr.stopAll();
        }

        @Test
        @DisplayName("getStatus returns RUNNING for a started market, STOPPED for unstarted")
        void runningForStartedStoppedForOther() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", true);

            Map<String, FeedStatus> status = mgr.getStatus();
            assertEquals(FeedStatus.RUNNING, status.get("EBS"));
            assertEquals(FeedStatus.STOPPED, status.get("REUTERS"));
            mgr.stopAll();
        }

        @Test
        @DisplayName("getStatus returns STOPPED after a running feed is stopped")
        void stoppedAfterFeedStopped() throws InterruptedException {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            mgr.startFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", true);

            mgr.stopFeeds(List.of("EBS"));
            awaitRunning(feedMap, "EBS", false);

            assertEquals(FeedStatus.STOPPED, mgr.getStatus().get("EBS"));
            mgr.stopAll();
        }

        @Test
        @DisplayName("getStatus covers all configured markets regardless of start state")
        void statusCoversAllConfiguredMarkets() {
            Map<String, ControllableFeed> feedMap = new ConcurrentHashMap<>();
            FeedManager mgr = buildManager(feedMap);

            Map<String, FeedStatus> status = mgr.getStatus();
            assertTrue(status.containsKey("EBS"),     "EBS must appear in status map");
            assertTrue(status.containsKey("REUTERS"), "REUTERS must appear in status map");
            mgr.stopAll();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Polls until the named feed reaches the desired {@code running} state or 2 s elapses. */
    private static void awaitRunning(Map<String, ControllableFeed> feedMap,
                                     String marketName,
                                     boolean expectedRunning) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            ControllableFeed feed = feedMap.get(marketName);
            if (feed != null && feed.isRunning() == expectedRunning) return;
            Thread.sleep(20);
        }
    }
}
