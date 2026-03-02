package com.marketdatahub;

import com.marketdatahub.book.BookEntry;
import com.marketdatahub.book.MarketBook;
import com.marketdatahub.config.MarketDataHubConfig;
import com.marketdatahub.feed.FeedManager;
import com.marketdatahub.feed.FeedStatus;
import com.marketdatahub.feed.MarketFeedFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Primary entry point for the market-data-hub library.
 * <p>
 * Ties together the {@link MarketDataHubConfig}, {@link FeedManager}, and
 * {@link MarketBook} into a single, easy-to-use façade.
 *
 * <h2>Minimal usage</h2>
 * <pre>{@code
 * MarketDataHubConfig config = MarketDataHubConfig.builder()
 *     .addMarket("EBS", TransportType.SOLACE, SolaceConfig.builder()
 *         .host("tcp://broker:55555").vpn("default")
 *         .username("user").password("pass")
 *         .topic("EQ/marketData/v1/EBS/>").build())
 *     .build();
 *
 * MarketDataHub hub = new MarketDataHub(config);
 * hub.onSymbolUpdate("EUR/USD", entry -> System.out.println(entry));
 * hub.startFeeds(List.of("EBS"));
 *
 * // query the book at any time
 * hub.getBook().getMidPrice("EUR/USD").ifPresent(System.out::println);
 *
 * // shut down cleanly
 * hub.stopAll();
 * }</pre>
 *
 * <h2>Custom feed factory</h2>
 * <p>
 * To plug in real Solace/TIBRV consumer logic, pass a custom
 * {@link MarketFeedFactory} subclass to the three-argument constructor:
 * <pre>{@code
 * MarketDataHub hub = new MarketDataHub(config, new MyFeedFactory());
 * }</pre>
 */
@Slf4j
public class MarketDataHub {

    private final MarketBook book;
    private final FeedManager feedManager;
    private volatile boolean stopped = false;

    /**
     * Constructs a {@code MarketDataHub} using the default (stub) feed factory.
     *
     * @param config the hub configuration; must not be {@code null}
     */
    public MarketDataHub(MarketDataHubConfig config) {
        this(config, new MarketFeedFactory());
    }

    /**
     * Constructs a {@code MarketDataHub} with a caller-supplied feed factory.
     * <p>
     * Use this constructor to inject concrete {@link com.marketdatahub.feed.MarketFeed}
     * implementations that contain real transport logic.
     *
     * @param config  the hub configuration; must not be {@code null}
     * @param factory the factory used to create feeds; must not be {@code null}
     */
    public MarketDataHub(MarketDataHubConfig config, MarketFeedFactory factory) {
        if (config == null)  throw new IllegalArgumentException("config must not be null");
        if (factory == null) throw new IllegalArgumentException("factory must not be null");
        this.book        = new MarketBook();
        this.feedManager = new FeedManager(config, factory, book);
        log.info("MarketDataHub initialised with {} market(s)",
            config.getMarkets() != null ? config.getMarkets().size() : 0);
    }

    /**
     * Starts the feeds for the specified market names.
     * <p>
     * Each feed is started on its own dedicated thread. Markets that are already
     * running are silently skipped.
     *
     * @param marketNames the names of the markets to start; must not be {@code null}
     * @throws IllegalStateException    if the hub has already been stopped via {@link #stopAll()}
     * @throws IllegalArgumentException if any name is not present in the configuration
     */
    public void startFeeds(List<String> marketNames) {
        if (stopped) {
            throw new IllegalStateException(
                "MarketDataHub has been stopped and cannot be restarted. " +
                "Create a new instance.");
        }
        log.info("Starting feeds: {}", marketNames);
        feedManager.startFeeds(marketNames);
    }

    /**
     * Stops the feeds for the specified market names only.
     * Markets that are not running are silently skipped.
     *
     * @param marketNames the names of the markets to stop; must not be {@code null}
     */
    public void stopFeeds(List<String> marketNames) {
        if (stopped) {
            log.warn("MarketDataHub is stopped, ignoring stopFeeds()");
            return;
        }
        log.info("Stopping feeds: {}", marketNames);
        feedManager.stopFeeds(marketNames);
    }

    /**
     * Stops all running feeds and shuts down internal resources.
     * <p>
     * This method is idempotent — subsequent calls after the first are no-ops.
     * After this call the hub should not be used again. Create a new instance
     * if restart is required.
     */
    public void stopAll() {
        if (stopped) {
            log.warn("MarketDataHub already stopped, ignoring stopAll()");
            return;
        }
        stopped = true;
        log.info("Stopping all feeds");
        feedManager.stopAll();
    }

    /**
     * Returns the live {@link MarketBook} maintained by this hub.
     * <p>
     * The book is thread-safe; all query methods may be called from any thread
     * at any time.
     *
     * @return the shared {@link MarketBook} instance
     */
    public MarketBook getBook() {
        return book;
    }

    /**
     * Registers a callback that fires every time the {@link BookEntry} for
     * {@code symbol} is updated by an incoming message.
     * <p>
     * Multiple callbacks per symbol are supported. Callbacks are invoked
     * synchronously on the feed's delivery thread, so they should return quickly.
     * Any exception thrown by a callback is caught and logged, and delivery to
     * subsequent callbacks is not affected.
     *
     * @param symbol   the instrument symbol to watch (e.g. {@code "EUR/USD"})
     * @param callback the consumer to invoke with the refreshed {@link BookEntry}
     */
    public void onSymbolUpdate(String symbol, Consumer<BookEntry> callback) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be null or blank");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        book.registerCallback(symbol, callback);
        log.debug("Registered update callback for symbol='{}'", symbol);
    }

    /**
     * Removes a previously registered symbol update callback.
     * If the callback was not registered, this is a no-op.
     *
     * @param symbol   the instrument symbol; must not be null or blank
     * @param callback the consumer to remove; must not be null
     */
    public void removeSymbolCallback(String symbol, Consumer<BookEntry> callback) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be null or blank");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        book.removeCallback(symbol, callback);
        log.debug("Removed update callback for symbol='{}'", symbol);
    }

    /**
     * Returns a snapshot of the current {@link FeedStatus} for every configured market.
     *
     * @return an unmodifiable map of market name → {@link FeedStatus}
     */
    public Map<String, FeedStatus> getStatus() {
        return feedManager.getStatus();
    }
}
