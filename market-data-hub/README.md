# market-data-hub

A transport-agnostic market data aggregation library for Java 17.

Clients configure one or more named markets backed by either a **Solace PubSub+** or **TIBCO Rendezvous (TIBRV)** transport. The library manages feed lifecycles, normalises incoming `MarketDataSnapshotFullRefresh` messages into a full depth-of-book price ladder, and exposes a rich, thread-safe query API.

> **This library ships with no real Solace or TIBRV connection code.**
> You bring your own consumer by subclassing `SolaceFeed` / `TibrvFeed` and providing a `MarketFeedFactory`.
> The [Extension model](#extension-model) section explains exactly how.

---

## Features

- **Full order-book depth** — `ParsedMessage` carries all `NoMDEntries` rungs as a `List<PriceLevel>`, not just a single best bid/offer
- **Transport-agnostic** — swap between Solace and TIBRV per market without changing any book or callback code
- **Rich book query API** — best rung, mid, spread, spread-in-bps, weighted mid, liquidity imbalance, rung-by-rung ladder access, staleness checks, crossed-book detection, and multi-symbol aggregations — all returning `Optional<>`
- **Symbol callbacks** — register (and deregister) multiple `Consumer<BookEntry>` per symbol; they fire on every update
- **Idempotent lifecycle** — `stopAll()` is safe to call multiple times; `startFeeds()` throws `IllegalStateException` if the hub has already been stopped
- **Template-method feed base classes** — `SolaceFeed` and `TibrvFeed` handle lifecycle; you only implement `connectAndSubscribe`, `disconnectAndUnsubscribe`, and `onRawMessage`
- **Feed lifecycle management** — `FeedManager` runs each feed on its own daemon thread via a cached `ExecutorService`
- **No framework dependency** — pure Java 17, no Spring, no Guice
- **Structured logging** — SLF4J + Logback throughout

---

## Extension model

This is the most important section if you are wiring up real connections.

### How it works

```
Your code                        market-data-hub
─────────────────────────────    ──────────────────────────────────────────
MySolaceFeed                      SolaceFeed  (abstract)
  └─ connectAndSubscribe()   ◄──  called by start()
  └─ onRawMessage(raw)        ──► you call getListener().onMessage(parsed)
  └─ disconnectAndUnsubscribe ◄──  called by stop()

MyTibrvFeed                       TibrvFeed   (abstract)
  └─ (same three methods)

MyFeedFactory                     MarketFeedFactory
  └─ createSolaceFeed()      ──► returns MySolaceFeed
  └─ createTibrvFeed()       ──► returns MyTibrvFeed

MarketDataHub(config, new MyFeedFactory())
```

The library never touches a Solace or TIBRV JAR directly.
You add those JARs to your own build, write the three methods, and hand the factory to the hub.

---

### Step 1 — Subclass `SolaceFeed`

```java
import com.marketdatahub.feed.solace.SolaceFeed;
import com.marketdatahub.config.SolaceConfig;
import com.marketdatahub.model.MDEntryType;
import com.marketdatahub.model.ParsedMessage;
import com.marketdatahub.model.PriceLevel;
// your Solace JCSMP imports ...

public class MySolaceFeed extends SolaceFeed {

    private JCSMPSession session;

    public MySolaceFeed(String marketName, SolaceConfig config) {
        super(marketName, config);
    }

    /**
     * Open the JCSMP session and subscribe to the topic.
     * Route every arriving message to onRawMessage().
     */
    @Override
    protected void connectAndSubscribe() {
        SolaceConfig cfg = getConfig();
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST,     cfg.getHost());
        props.setProperty(JCSMPProperties.VPN_NAME, cfg.getVpn());
        props.setProperty(JCSMPProperties.USERNAME,  cfg.getUsername());
        props.setProperty(JCSMPProperties.PASSWORD,  cfg.getPassword());

        session = JCSMPFactory.onlyInstance().createSession(props);
        session.connect();

        XMLMessageConsumer consumer = session.getMessageConsumer(
            (BytesXMLMessage raw, JCSMPException e) -> {
                if (raw != null) onRawMessage(raw);
            });
        session.addSubscription(JCSMPFactory.onlyInstance().createTopic(cfg.getTopic()));
        consumer.start();
    }

    /** Close the session on stop. */
    @Override
    protected void disconnectAndUnsubscribe() {
        if (session != null) session.closeSession();
    }

    /**
     * Parse a raw JCSMP message into a ParsedMessage and hand it to the book.
     * Build one PriceLevel per NoMDEntries repeating-group entry.
     */
    @Override
    protected void onRawMessage(Object rawMessage) {
        BytesXMLMessage raw = (BytesXMLMessage) rawMessage;
        // --- your FIX / proprietary parsing here ---
        List<PriceLevel> levels = new ArrayList<>();
        // example: one bid and one offer rung
        levels.add(PriceLevel.builder()
            .entryType(MDEntryType.BID).price(parsedBid).size(parsedBidSize).positionNo(1).build());
        levels.add(PriceLevel.builder()
            .entryType(MDEntryType.OFFER).price(parsedOffer).size(parsedOfferSize).positionNo(1).build());

        getListener().onMessage(ParsedMessage.builder()
            .symbol(parsedSymbol)
            .levels(levels)
            .marketName(getMarketName())
            .timestamp(Instant.now())
            .build());
    }
}
```

---

### Step 2 — Subclass `TibrvFeed`

```java
import com.marketdatahub.feed.tibrv.TibrvFeed;
import com.marketdatahub.config.TibrvConfig;
// your TIBRV imports ...

public class MyTibrvFeed extends TibrvFeed implements TibrvMsgCallback {

    private TibrvRvdTransport transport;

    public MyTibrvFeed(String marketName, TibrvConfig config) {
        super(marketName, config);
    }

    @Override
    protected void connectAndSubscribe() {
        TibrvConfig cfg = getConfig();
        Tibrv.open(Tibrv.IMPL_NATIVE);
        transport = new TibrvRvdTransport(cfg.getService(), cfg.getNetwork(), cfg.getDaemon());
        new TibrvListener(Tibrv.defaultQueue(), this, transport, cfg.getSubject(), null);
        Tibrv.defaultQueue().dispatch(); // blocking dispatch loop — runs on the feed's own thread
    }

    @Override
    protected void disconnectAndUnsubscribe() {
        if (transport != null) transport.destroy();
        Tibrv.close();
    }

    /** TIBRV callback — bridge into onRawMessage. */
    @Override
    public void onMsg(TibrvListener listener, TibrvMsg msg) {
        onRawMessage(msg);
    }

    @Override
    protected void onRawMessage(Object rawMessage) {
        TibrvMsg msg = (TibrvMsg) rawMessage;
        // parse msg fields into levels, then:
        getListener().onMessage(ParsedMessage.builder()
            .symbol(msg.getString("symbol"))
            .levels(parseLevels(msg))
            .marketName(getMarketName())
            .timestamp(Instant.now())
            .build());
    }

    private List<PriceLevel> parseLevels(TibrvMsg msg) {
        // your parsing logic
        return List.of();
    }
}
```

---

### Step 3 — Write a `MarketFeedFactory`

Override only the methods for the transports you use.

```java
import com.marketdatahub.feed.MarketFeedFactory;
import com.marketdatahub.feed.solace.SolaceFeed;
import com.marketdatahub.feed.tibrv.TibrvFeed;
import com.marketdatahub.config.SolaceConfig;
import com.marketdatahub.config.TibrvConfig;

public class MyFeedFactory extends MarketFeedFactory {

    @Override
    protected SolaceFeed createSolaceFeed(String marketName, SolaceConfig config) {
        return new MySolaceFeed(marketName, config);
    }

    @Override
    protected TibrvFeed createTibrvFeed(String marketName, TibrvConfig config) {
        return new MyTibrvFeed(marketName, config);
    }
}
```

> If you only use one transport you can override just that one method and leave the other as the default no-op stub.

---

### Step 4 — Pass the factory to `MarketDataHub`

```java
MarketDataHubConfig config = MarketDataHubConfig.builder()
    .addMarket("EBS", TransportType.SOLACE, SolaceConfig.builder()
        .host("tcp://broker:55555")
        .vpn("default")
        .username("user")
        .password("pass")
        .topic("EQ/marketData/v1/EBS/>")
        .build())
    .addMarket("REUTERS", TransportType.TIBRV, TibrvConfig.builder()
        .service("7500")
        .network(";239.255.0.1")
        .daemon("localhost:7500")
        .subject("REUTERS.>")
        .build())
    .build();

// Pass your factory as the second argument
MarketDataHub hub = new MarketDataHub(config, new MyFeedFactory());
```

> Calling `new MarketDataHub(config)` (single-arg constructor) uses the built-in **no-op** stub factory — feeds start but receive no messages. Always supply your factory in production.

---

## Project structure

```
market-data-hub/
├── build.gradle
├── settings.gradle
└── src/
    ├── main/java/com/marketdatahub/
    │   ├── MarketDataHub.java              ← entry point / façade
    │   ├── config/
    │   │   ├── MarketDataHubConfig.java
    │   │   ├── MarketConfig.java           ← interface
    │   │   ├── SolaceConfig.java
    │   │   ├── TibrvConfig.java
    │   │   └── TransportType.java
    │   ├── feed/
    │   │   ├── MarketFeed.java             ← interface
    │   │   ├── FeedManager.java
    │   │   ├── MarketFeedFactory.java      ← extend this
    │   │   ├── FeedStatus.java
    │   │   ├── solace/SolaceFeed.java      ← extend this for Solace
    │   │   └── tibrv/TibrvFeed.java        ← extend this for TIBRV
    │   ├── listener/
    │   │   └── MarketDataListener.java
    │   ├── book/
    │   │   ├── MarketBook.java
    │   │   └── BookEntry.java
    │   └── model/
    │       ├── MDEntryType.java            ← BID / OFFER / TRADE
    │       ├── PriceLevel.java             ← single order-book rung
    │       └── ParsedMessage.java
    └── test/java/com/marketdatahub/
        ├── MarketBookTest.java
        └── FeedManagerTest.java
```

---

## Requirements

| Dependency | Version  |
|------------|----------|
| Java       | 17+      |
| Gradle     | 8.6+ (wrapper included) |
| Lombok     | 1.18.30  |
| SLF4J      | 2.0.9    |
| Logback    | 1.4.14   |
| JUnit 5    | 5.10.1   |
| Mockito    | 5.8.0    |

> Solace and TIBRV client JARs are **not** included — add them to your own `build.gradle` and provide concrete feed subclasses as described above.

---

## Building

```bash
cd market-data-hub
./gradlew build      # compile + test + jar
./gradlew test       # run tests only
```

---

## Quick start (end-to-end)

```java
// 1. Configure
MarketDataHubConfig config = MarketDataHubConfig.builder()
    .addMarket("EBS", TransportType.SOLACE, SolaceConfig.builder()
        .host("tcp://broker:55555").vpn("default")
        .username("user").password("pass")
        .topic("EQ/marketData/v1/EBS/>").build())
    .build();

// 2. Create hub with YOUR factory (see Extension model above)
MarketDataHub hub = new MarketDataHub(config, new MyFeedFactory());

// 3. Register callbacks before starting
hub.onSymbolUpdate("EUR/USD", entry -> {
    if (!entry.getBidLevels().isEmpty() && !entry.getOfferLevels().isEmpty()) {
        double bid   = entry.getBidLevels().get(0).getPrice();
        double offer = entry.getOfferLevels().get(0).getPrice();
        System.out.printf("EUR/USD  bid=%.5f  offer=%.5f%n", bid, offer);
    }
});

// Deregister when no longer needed
Consumer<BookEntry> cb = entry -> System.out.println(entry);
hub.onSymbolUpdate("USD/JPY", cb);
hub.removeSymbolCallback("USD/JPY", cb);

// 4. Start feeds
hub.startFeeds(List.of("EBS"));

// 5. Query the book at any time
MarketBook book = hub.getBook();

book.getBestBid("EUR/USD")      .ifPresent(b -> System.out.println("Bid: "    + b));
book.getBestOffer("EUR/USD")    .ifPresent(a -> System.out.println("Offer: "  + a));
book.getMidPrice("EUR/USD")     .ifPresent(m -> System.out.println("Mid: "    + m));
book.getSpread("EUR/USD")       .ifPresent(s -> System.out.println("Spread: " + s));
book.getSpreadInBps("EUR/USD")  .ifPresent(b -> System.out.println("Bps: "    + b));
book.getWeightedMidPrice("EUR/USD").ifPresent(w -> System.out.println("WMid: "+ w));

List<PriceLevel> bids   = book.getBidLadder("EUR/USD");
List<PriceLevel> offers = book.getOfferLadder("EUR/USD");
book.getBidAtRung("EUR/USD", 2).ifPresent(p -> System.out.println("Rung-2 bid: " + p));

book.getTotalLiquidity("EUR/USD")   .ifPresent(l -> System.out.println("Liq: "  + l));
book.getLiquidityImbalance("EUR/USD").ifPresent(i -> System.out.println("Imb: " + i));

boolean stale   = book.isStale("EUR/USD", Duration.ofSeconds(5));
boolean crossed = book.isCrossed("EUR/USD");

List<String> tightest = book.getTopNBySpread(3);
book.getSnapshot("EUR/USD").ifPresent(e -> System.out.println(e));

// 6. Stop
hub.stopFeeds(List.of("EBS"));   // selective
hub.stopAll();                    // everything — idempotent
```

---

## Price ladder model

Market data messages carry a full depth-of-book ladder via the `NoMDEntries` repeating group.

### Building a `ParsedMessage` with multiple rungs

```java
ParsedMessage msg = ParsedMessage.builder()
    .symbol("EUR/USD")
    .marketName("EBS")
    .timestamp(Instant.now())
    .levels(List.of(
        PriceLevel.builder().entryType(MDEntryType.BID)  .price(1.0850).size(1_000_000L).positionNo(1).build(),
        PriceLevel.builder().entryType(MDEntryType.BID)  .price(1.0849).size(2_000_000L).positionNo(2).build(),
        PriceLevel.builder().entryType(MDEntryType.BID)  .price(1.0848).size(3_000_000L).positionNo(3).build(),
        PriceLevel.builder().entryType(MDEntryType.OFFER).price(1.0852).size(1_500_000L).positionNo(1).build(),
        PriceLevel.builder().entryType(MDEntryType.OFFER).price(1.0853).size(2_500_000L).positionNo(2).build(),
        PriceLevel.builder().entryType(MDEntryType.TRADE).price(1.0851).size(0L)        .positionNo(1).build()
    ))
    .build();
```

### FIX tag mapping

| `PriceLevel` field | FIX tag                     | Notes                              |
|--------------------|-----------------------------|------------------------------------|
| `entryType`        | Tag 269 (MDEntryType)       | BID=0, OFFER=1, TRADE=2            |
| `price`            | Tag 270 (MDEntryPx)         |                                    |
| `size`             | Tag 271 (MDEntrySize)       |                                    |
| `positionNo`       | Tag 290 (MDEntryPositionNo) | 1-based; rung 1 = best price       |
| —                  | Tag 55  (Symbol)            | On `ParsedMessage.symbol`          |
| —                  | Tag 268 (NoMDEntries)       | Count of entries in `levels` list  |

### Backward-compatible convenience methods on `ParsedMessage`

These return rung-1 values and default to `0.0` / `0L` when absent:

| Method               | Returns            | Description                         |
|----------------------|--------------------|-------------------------------------|
| `getBidPrice()`      | `double`           | Rung-1 bid price                    |
| `getOfferPrice()`    | `double`           | Rung-1 offer price                  |
| `getBidSize()`       | `long`             | Rung-1 bid quantity                 |
| `getOfferSize()`     | `long`             | Rung-1 offer quantity               |
| `getLastTradePrice()`| `double`           | TRADE entry price                   |
| `getBidLevels()`     | `List<PriceLevel>` | All bid levels, positionNo asc      |
| `getOfferLevels()`   | `List<PriceLevel>` | All offer levels, positionNo asc    |

---

## MarketBook API reference

All methods are safe to call concurrently from any thread.

### Best-rung queries

| Method                      | Returns            | Description                       |
|-----------------------------|--------------------|-----------------------------------|
| `getBestBid(symbol)`        | `Optional<Double>` | Rung-1 bid price                  |
| `getBestOffer(symbol)`      | `Optional<Double>` | Rung-1 offer price                |
| `getBidSize(symbol)`        | `Optional<Long>`   | Rung-1 bid quantity               |
| `getOfferSize(symbol)`      | `Optional<Long>`   | Rung-1 offer quantity             |
| `getLastTradePrice(symbol)` | `Optional<Double>` | Last trade price (TRADE entry)    |

### Derived price queries

| Method                        | Returns            | Description                                        |
|-------------------------------|--------------------|----------------------------------------------------|
| `getMidPrice(symbol)`         | `Optional<Double>` | `(bid + offer) / 2`                                |
| `getSpread(symbol)`           | `Optional<Double>` | `offer − bid`; logs WARN if crossed                |
| `getSpreadInBps(symbol)`      | `Optional<Double>` | `(spread / mid) × 10 000`                          |
| `getWeightedMidPrice(symbol)` | `Optional<Double>` | `(bid×offerSz + offer×bidSz) / (bidSz + offerSz)` |

### Liquidity queries

| Method                          | Returns            | Description                                     |
|---------------------------------|--------------------|-------------------------------------------------|
| `getTotalLiquidity(symbol)`     | `Optional<Long>`   | `bidSize + offerSize` at rung 1                 |
| `getLiquidityImbalance(symbol)` | `Optional<Double>` | `(bidSz − offerSz) / total`; range −1.0 … +1.0 |
| `getBidOfferRatio(symbol)`      | `Optional<Double>` | `bidSz / offerSz` at rung 1                     |

### Ladder depth queries

| Method                         | Returns            | Description                                      |
|--------------------------------|--------------------|--------------------------------------------------|
| `getBidAtRung(symbol, rung)`   | `Optional<Double>` | Bid price at the given 1-based rung              |
| `getOfferAtRung(symbol, rung)` | `Optional<Double>` | Offer price at the given 1-based rung            |
| `getBidLadder(symbol)`         | `List<PriceLevel>` | Full bid ladder, best → worst; empty if unknown  |
| `getOfferLadder(symbol)`       | `List<PriceLevel>` | Full offer ladder, best → worst; empty if unknown|
| `getBidDepth(symbol)`          | `int`              | Number of bid rungs; 0 if unknown                |
| `getOfferDepth(symbol)`        | `int`              | Number of offer rungs; 0 if unknown              |

### Book-state queries

| Method                         | Returns               | Description                                              |
|--------------------------------|-----------------------|----------------------------------------------------------|
| `hasSymbol(symbol)`            | `boolean`             | `true` if symbol has been seen at least once             |
| `getSnapshot(symbol)`          | `Optional<BookEntry>` | Full entry; empty if never seen                          |
| `getActiveSymbols()`           | `List<String>`        | Snapshot of all known symbols                            |
| `getLastUpdated(symbol)`       | `Optional<Instant>`   | Timestamp of the last update                             |
| `getMarketName(symbol)`        | `Optional<String>`    | Market that last updated this symbol                     |
| `isStale(symbol, threshold)`   | `boolean`             | `true` if last update is older than threshold or absent  |
| `isCrossed(symbol)`            | `boolean`             | `true` if best bid ≥ best offer                          |

### Multi-symbol / aggregation queries

| Method                                 | Returns        | Description                               |
|----------------------------------------|----------------|-------------------------------------------|
| `getStaleSymbols(threshold)`           | `List<String>` | All symbols older than threshold          |
| `getCrossedSymbols()`                  | `List<String>` | All crossed symbols                       |
| `getActiveSymbolsByMarket(marketName)` | `List<String>` | Symbols last updated by a specific market |
| `getTopNBySpread(n)`                   | `List<String>` | Top N tightest-spread symbols, ascending  |

### Callback management

| Method                               | Description                                                    |
|--------------------------------------|----------------------------------------------------------------|
| `registerCallback(symbol, callback)` | Fires `callback` on every update to `symbol`                   |
| `removeCallback(symbol, callback)`   | Deregisters a previously registered callback; no-op if absent  |

---

## MarketDataHub façade API

| Method                                   | Description                                                         |
|------------------------------------------|---------------------------------------------------------------------|
| `startFeeds(List<String>)`               | Starts named feeds; throws `ISE` if `stopAll()` was already called  |
| `stopFeeds(List<String>)`                | Stops named feeds only; no-op if already stopped                    |
| `stopAll()`                              | Stops all feeds; idempotent                                         |
| `getBook()`                              | Returns the live `MarketBook`                                       |
| `getStatus()`                            | Returns `Map<String, FeedStatus>` for every configured market       |
| `onSymbolUpdate(symbol, callback)`       | Registers a `Consumer<BookEntry>` callback on the book              |
| `removeSymbolCallback(symbol, callback)` | Removes a previously registered callback                            |

---

## Thread safety

| Component                  | Mechanism                                                                                                                           |
|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `MarketBook`               | `ConcurrentHashMap` — each update atomically replaces the `BookEntry` value                                                         |
| `SolaceFeed` / `TibrvFeed` | `AtomicBoolean` guards `start` / `stop` lifecycle                                                                                  |
| `FeedManager`              | `ConcurrentHashMap` for feed and future tracking                                                                                    |
| Callbacks                  | Invoked on the feed delivery thread; a defensive `List.copyOf` snapshot is taken before iteration so callbacks may safely register/remove callbacks during execution |
| `MarketDataHub.stopAll()`  | `volatile boolean stopped` flag; idempotent by design                                                                               |

> Callbacks run synchronously on the feed's delivery thread — keep them non-blocking.

---

## Running the tests

```bash
./gradlew test
```

### `MarketBookTest` — 76 tests across 10 nested suites

| Suite           | Coverage                                                                                          |
|-----------------|---------------------------------------------------------------------------------------------------|
| `EmptyBook`     | All Optional/boolean getters return safe defaults for unknown symbols                             |
| `Ingestion`     | Correct ladder storage, multi-rung messages, atomic replacement, null guards, `getActiveSymbols`  |
| `BestRung`      | `getBestBid`, `getBestOffer`, `getBidSize`, `getOfferSize`, `getLastTradePrice`, one-sided books   |
| `DerivedPrices` | `getMidPrice`, `getSpread` (including crossed), `getSpreadInBps`, `getWeightedMidPrice`           |
| `Liquidity`     | `getTotalLiquidity`, `getLiquidityImbalance` (positive / negative / zero / empty), `getBidOfferRatio` |
| `LadderDepth`   | `getBidAtRung`, `getOfferAtRung`, `getBidLadder`, `getOfferLadder`, `getBidDepth`, `getOfferDepth` |
| `BookState`     | `hasSymbol`, `getLastUpdated`, `getMarketName`, `isStale`, `isCrossed`                            |
| `Aggregation`   | `getStaleSymbols`, `getCrossedSymbols`, `getActiveSymbolsByMarket`, `getTopNBySpread`             |
| `Callbacks`     | Register, multi-fire, cross-symbol isolation, exception safety, `removeCallback`                  |
| `ThreadSafety`  | 10 threads × 1 000 concurrent updates — book remains consistent                                  |

### `FeedManagerTest` — 17 tests across 5 nested suites

| Suite             | Coverage                                                                               |
|-------------------|----------------------------------------------------------------------------------------|
| `Construction`    | Null config / factory / listener each throw `IllegalArgumentException`                 |
| `StartFeeds`      | Selective start, both markets, unknown market, idempotent double-start, null list, listener forwarding |
| `StopFeeds`       | Selective stop (leaves other running), no-op on never-started feed, null list          |
| `StopAll`         | Stops all running feeds, no-op on idle manager                                         |
| `StatusReporting` | `STOPPED` before start, `RUNNING` after start, `STOPPED` after stop, all markets covered |
