# market-data-hub

A transport-agnostic market data aggregation library for Java 17.

Clients configure one or more named markets backed by either a **Solace PubSub+** or **TIBCO Rendezvous (TIBRV)** transport. The library manages feed lifecycles, normalises incoming `MarketDataSnapshotFullRefresh` messages into a full depth-of-book price ladder, and exposes a rich, thread-safe query API.

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

## Project structure

```
market-data-hub/
├── src/main/java/com/marketdatahub/
│   ├── MarketDataHub.java              ← entry point / façade
│   ├── config/
│   │   ├── MarketDataHubConfig.java
│   │   ├── MarketConfig.java
│   │   ├── SolaceConfig.java
│   │   ├── TibrvConfig.java
│   │   └── TransportType.java
│   ├── feed/
│   │   ├── MarketFeed.java             ← interface
│   │   ├── FeedManager.java
│   │   ├── MarketFeedFactory.java
│   │   ├── FeedStatus.java
│   │   ├── solace/SolaceFeed.java      ← abstract base
│   │   └── tibrv/TibrvFeed.java       ← abstract base
│   ├── listener/
│   │   └── MarketDataListener.java
│   ├── book/
│   │   ├── MarketBook.java
│   │   └── BookEntry.java
│   └── model/
│       ├── MDEntryType.java            ← BID / OFFER / TRADE
│       ├── PriceLevel.java             ← single order-book rung
│       └── ParsedMessage.java
└── src/test/java/com/marketdatahub/
    ├── MarketBookTest.java
    └── FeedManagerTest.java
```

---

## Requirements

| Dependency | Version  |
|------------|----------|
| Java       | 17+      |
| Maven      | 3.8+     |
| Lombok     | 1.18.30  |
| SLF4J      | 2.0.9    |
| Logback    | 1.4.14   |
| JUnit 5    | 5.10.1   |
| Mockito    | 5.8.0    |

> Solace and TIBRV client JARs are **not** included — add them to your own `pom.xml` and provide concrete feed subclasses.

---

## Building

```bash
cd market-data-hub
mvn clean package    # compile + test + jar
mvn clean test       # run tests only
```

---

## Quick start

### 1. Configure the hub

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
```

### 2. Create the hub and register callbacks

```java
MarketDataHub hub = new MarketDataHub(config);

// called every time EUR/USD is updated from any feed
hub.onSymbolUpdate("EUR/USD", entry -> {
    if (!entry.getBidLevels().isEmpty() && !entry.getOfferLevels().isEmpty()) {
        double bid   = entry.getBidLevels().get(0).getPrice();
        double offer = entry.getOfferLevels().get(0).getPrice();
        System.out.printf("EUR/USD  bid=%.5f  offer=%.5f  mid=%.5f%n",
            bid, offer, (bid + offer) / 2);
    }
});

// deregister when no longer needed
Consumer<BookEntry> myCallback = entry -> System.out.println(entry);
hub.onSymbolUpdate("USD/JPY", myCallback);
hub.removeSymbolCallback("USD/JPY", myCallback);
```

### 3. Start feeds

```java
hub.startFeeds(List.of("EBS", "REUTERS")); // or a subset
```

### 4. Query the book at any time

```java
MarketBook book = hub.getBook();

// best rung
book.getBestBid("EUR/USD") .ifPresent(bid  -> System.out.println("Bid: "    + bid));
book.getBestOffer("EUR/USD").ifPresent(ask  -> System.out.println("Offer: "  + ask));
book.getMidPrice("EUR/USD") .ifPresent(mid  -> System.out.println("Mid: "    + mid));
book.getSpread("EUR/USD")   .ifPresent(sprd -> System.out.println("Spread: " + sprd));
book.getSpreadInBps("EUR/USD").ifPresent(bps -> System.out.println("Spread bps: " + bps));

// weighted mid accounts for size asymmetry at rung 1
book.getWeightedMidPrice("EUR/USD").ifPresent(wm -> System.out.println("Weighted mid: " + wm));

// full ladder access
List<PriceLevel> bids   = book.getBidLadder("EUR/USD");
List<PriceLevel> offers = book.getOfferLadder("EUR/USD");
int bidDepth   = book.getBidDepth("EUR/USD");
int offerDepth = book.getOfferDepth("EUR/USD");

// price at a specific rung (1-based)
book.getBidAtRung("EUR/USD", 2).ifPresent(p -> System.out.println("Rung-2 bid: " + p));

// liquidity
book.getTotalLiquidity("EUR/USD")   .ifPresent(liq -> System.out.println("Total liq: " + liq));
book.getLiquidityImbalance("EUR/USD").ifPresent(imb -> System.out.println("Imbalance: " + imb));
book.getBidOfferRatio("EUR/USD")    .ifPresent(r   -> System.out.println("B/O ratio: " + r));

// book state
boolean known  = book.hasSymbol("EUR/USD");
boolean stale  = book.isStale("EUR/USD", Duration.ofSeconds(5));
boolean crossed = book.isCrossed("EUR/USD");
book.getLastUpdated("EUR/USD").ifPresent(ts -> System.out.println("Updated: " + ts));
book.getMarketName("EUR/USD") .ifPresent(m  -> System.out.println("From: " + m));

// multi-symbol helpers
List<String> all      = book.getActiveSymbols();
List<String> staleOnes = book.getStaleSymbols(Duration.ofSeconds(5));
List<String> crossed2  = book.getCrossedSymbols();
List<String> ebsOnly   = book.getActiveSymbolsByMarket("EBS");
List<String> tightest  = book.getTopNBySpread(3);

// full snapshot
book.getSnapshot("GBP/USD").ifPresent(e -> System.out.println(e));
```

### 5. Stop individual feeds or all feeds

```java
// stop a subset
hub.stopFeeds(List.of("EBS"));

// stop everything — idempotent, safe to call multiple times
hub.stopAll();
hub.stopAll(); // no-op, no exception

// startFeeds after stopAll throws IllegalStateException
// hub.startFeeds(List.of("EBS")); // ← throws ISE
```

### 6. Check feed health

```java
Map<String, FeedStatus> status = hub.getStatus();
// FeedStatus: RUNNING | STOPPED | ERROR
status.forEach((market, s) -> System.out.println(market + " → " + s));
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

| Method              | Returns  | Description                        |
|---------------------|----------|------------------------------------|
| `getBidPrice()`     | `double` | Rung-1 bid price                   |
| `getOfferPrice()`   | `double` | Rung-1 offer price                 |
| `getBidSize()`      | `long`   | Rung-1 bid quantity                |
| `getOfferSize()`    | `long`   | Rung-1 offer quantity              |
| `getLastTradePrice()`| `double`| TRADE entry price                  |
| `getBidLevels()`    | `List<PriceLevel>` | All bid levels, positionNo asc |
| `getOfferLevels()`  | `List<PriceLevel>` | All offer levels, positionNo asc|

---

## MarketBook API reference

All methods are safe to call concurrently from any thread.

### Best-rung queries

| Method                        | Returns            | Description                                   |
|-------------------------------|--------------------|-----------------------------------------------|
| `getBestBid(symbol)`          | `Optional<Double>` | Rung-1 bid price                              |
| `getBestOffer(symbol)`        | `Optional<Double>` | Rung-1 offer price                            |
| `getBidSize(symbol)`          | `Optional<Long>`   | Rung-1 bid quantity                           |
| `getOfferSize(symbol)`        | `Optional<Long>`   | Rung-1 offer quantity                         |
| `getLastTradePrice(symbol)`   | `Optional<Double>` | Last trade price (TRADE entry)                |

### Derived price queries

| Method                          | Returns            | Description                                         |
|---------------------------------|--------------------|-----------------------------------------------------|
| `getMidPrice(symbol)`           | `Optional<Double>` | `(bid + offer) / 2`                                 |
| `getSpread(symbol)`             | `Optional<Double>` | `offer − bid`; logs WARN if crossed                 |
| `getSpreadInBps(symbol)`        | `Optional<Double>` | `(spread / mid) × 10 000`                           |
| `getWeightedMidPrice(symbol)`   | `Optional<Double>` | `(bid×offerSz + offer×bidSz) / (bidSz + offerSz)`  |

### Liquidity queries

| Method                          | Returns            | Description                                          |
|---------------------------------|--------------------|------------------------------------------------------|
| `getTotalLiquidity(symbol)`     | `Optional<Long>`   | `bidSize + offerSize` at rung 1                      |
| `getLiquidityImbalance(symbol)` | `Optional<Double>` | `(bidSz − offerSz) / total`; range −1.0 … +1.0      |
| `getBidOfferRatio(symbol)`      | `Optional<Double>` | `bidSz / offerSz` at rung 1                          |

### Ladder depth queries

| Method                          | Returns               | Description                                   |
|---------------------------------|-----------------------|-----------------------------------------------|
| `getBidAtRung(symbol, rung)`    | `Optional<Double>`    | Bid price at the given 1-based rung            |
| `getOfferAtRung(symbol, rung)`  | `Optional<Double>`    | Offer price at the given 1-based rung          |
| `getBidLadder(symbol)`          | `List<PriceLevel>`    | Full bid ladder, best → worst; empty if unknown|
| `getOfferLadder(symbol)`        | `List<PriceLevel>`    | Full offer ladder, best → worst; empty if unknown|
| `getBidDepth(symbol)`           | `int`                 | Number of bid rungs; 0 if unknown              |
| `getOfferDepth(symbol)`         | `int`                 | Number of offer rungs; 0 if unknown            |

### Book-state queries

| Method                            | Returns              | Description                                             |
|-----------------------------------|----------------------|---------------------------------------------------------|
| `hasSymbol(symbol)`               | `boolean`            | `true` if symbol has been seen at least once            |
| `getSnapshot(symbol)`             | `Optional<BookEntry>`| Full entry; empty if never seen                         |
| `getActiveSymbols()`              | `List<String>`       | Snapshot of all known symbols                           |
| `getLastUpdated(symbol)`          | `Optional<Instant>`  | Timestamp of the last update                            |
| `getMarketName(symbol)`           | `Optional<String>`   | Market that last updated this symbol                    |
| `isStale(symbol, threshold)`      | `boolean`            | `true` if last update is older than threshold or absent |
| `isCrossed(symbol)`               | `boolean`            | `true` if best bid ≥ best offer                         |

### Multi-symbol / aggregation queries

| Method                                | Returns        | Description                                       |
|---------------------------------------|----------------|---------------------------------------------------|
| `getStaleSymbols(threshold)`          | `List<String>` | All symbols older than threshold                  |
| `getCrossedSymbols()`                 | `List<String>` | All crossed symbols                               |
| `getActiveSymbolsByMarket(marketName)`| `List<String>` | Symbols last updated by a specific market         |
| `getTopNBySpread(n)`                  | `List<String>` | Top N tightest-spread symbols, ascending          |

### Callback management

| Method                                    | Description                                             |
|-------------------------------------------|---------------------------------------------------------|
| `registerCallback(symbol, callback)`      | Fires `callback` on every update to `symbol`            |
| `removeCallback(symbol, callback)`        | Deregisters a previously registered callback; no-op if absent |

---

## MarketDataHub façade API

| Method                                  | Description                                                           |
|-----------------------------------------|-----------------------------------------------------------------------|
| `startFeeds(List<String>)`              | Starts named feeds; throws `ISE` if `stopAll()` was already called   |
| `stopFeeds(List<String>)`               | Stops named feeds only; no-op if already stopped                      |
| `stopAll()`                             | Stops all feeds; idempotent                                           |
| `getBook()`                             | Returns the live `MarketBook`                                         |
| `getStatus()`                           | Returns `Map<String, FeedStatus>` for every configured market         |
| `onSymbolUpdate(symbol, callback)`      | Registers a `Consumer<BookEntry>` callback on the book                |
| `removeSymbolCallback(symbol, callback)`| Removes a previously registered callback                              |

---

## Plugging in real transport logic

The library ships with no-op stub feeds. Subclass the abstract base classes and pass a custom factory.

### Solace example

```java
public class MySolaceFeed extends SolaceFeed {

    public MySolaceFeed(String marketName, SolaceConfig config) {
        super(marketName, config);
    }

    @Override
    protected void connectAndSubscribe() {
        SolaceConfig cfg = getConfig();
        // create your JCSMPSession; route each incoming message to onRawMessage(msg)
    }

    @Override
    protected void disconnectAndUnsubscribe() {
        // close the session
    }

    @Override
    protected void onRawMessage(Object raw) {
        BytesXMLMessage msg = (BytesXMLMessage) raw;
        // parse all NoMDEntries into List<PriceLevel>, then:
        getListener().onMessage(ParsedMessage.builder()
            .symbol(/* tag 55 */)
            .levels(/* parsed levels */)
            .marketName(getMarketName())
            .timestamp(Instant.now())
            .build());
    }
}
```

### TIBRV example

```java
public class MyTibrvFeed extends TibrvFeed implements TibrvMsgCallback {

    public MyTibrvFeed(String marketName, TibrvConfig config) {
        super(marketName, config);
    }

    @Override
    protected void connectAndSubscribe() {
        TibrvConfig cfg = getConfig();
        Tibrv.open(Tibrv.IMPL_NATIVE);
        TibrvRvdTransport transport =
            new TibrvRvdTransport(cfg.getService(), cfg.getNetwork(), cfg.getDaemon());
        new TibrvListener(Tibrv.defaultQueue(), this, transport, cfg.getSubject(), null);
    }

    @Override
    protected void disconnectAndUnsubscribe() { Tibrv.close(); }

    @Override
    public void onMsg(TibrvListener listener, TibrvMsg msg) { onRawMessage(msg); }

    @Override
    protected void onRawMessage(Object raw) {
        TibrvMsg msg = (TibrvMsg) raw;
        // parse into ParsedMessage and deliver:
        getListener().onMessage(/* ... */);
    }
}
```

### Custom factory

```java
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

MarketDataHub hub = new MarketDataHub(config, new MyFeedFactory());
```

---

## Thread safety

| Component      | Mechanism                                                                       |
|----------------|---------------------------------------------------------------------------------|
| `MarketBook`   | `ConcurrentHashMap` — each update atomically replaces the `BookEntry` value      |
| `SolaceFeed` / `TibrvFeed` | `AtomicBoolean` guards `start` / `stop` lifecycle           |
| `FeedManager`  | `ConcurrentHashMap` for feed and future tracking                                |
| Callbacks      | Invoked on the feed delivery thread; a defensive `List.copyOf` snapshot is taken before iteration so callbacks may themselves register/remove callbacks safely |
| `MarketDataHub.stopAll()` | `volatile boolean stopped` flag; idempotent by design            |

> Callbacks run synchronously on the feed's delivery thread — keep them non-blocking.

---

## Running the tests

```bash
mvn test
```

### `MarketBookTest` — 43 tests across 10 nested suites

| Suite            | Coverage                                                                                         |
|------------------|--------------------------------------------------------------------------------------------------|
| `EmptyBook`      | All Optional/boolean getters return safe defaults for unknown symbols                            |
| `Ingestion`      | Correct ladder storage, multi-rung messages, atomic replacement, null guards, `getActiveSymbols` |
| `BestRung`       | `getBestBid`, `getBestOffer`, `getBidSize`, `getOfferSize`, `getLastTradePrice`, one-sided books  |
| `DerivedPrices`  | `getMidPrice`, `getSpread` (including crossed), `getSpreadInBps`, `getWeightedMidPrice`          |
| `Liquidity`      | `getTotalLiquidity`, `getLiquidityImbalance` (positive / negative / zero / empty), `getBidOfferRatio` |
| `LadderDepth`    | `getBidAtRung`, `getOfferAtRung`, `getBidLadder`, `getOfferLadder`, `getBidDepth`, `getOfferDepth` |
| `BookState`      | `hasSymbol`, `getLastUpdated`, `getMarketName`, `isStale` (fresh / stale), `isCrossed` (crossed / locked / normal / unknown) |
| `Aggregation`    | `getStaleSymbols`, `getCrossedSymbols`, `getActiveSymbolsByMarket`, `getTopNBySpread`            |
| `Callbacks`      | Register, multi-fire, cross-symbol isolation, exception safety, `removeCallback` (stop firing / no-op / partial removal) |
| `ThreadSafety`   | 10 threads × 1 000 concurrent updates — book remains consistent                                  |

### `FeedManagerTest` — 17 tests across 5 nested suites

| Suite             | Coverage                                                                              |
|-------------------|---------------------------------------------------------------------------------------|
| `Construction`    | Null config / factory / listener each throw `IllegalArgumentException`               |
| `StartFeeds`      | Selective start, both markets, unknown market, idempotent double-start, null list, listener forwarding |
| `StopFeeds`       | Selective stop (leaves other running), no-op on never-started feed, null list         |
| `StopAll`         | Stops all running feeds, no-op on idle manager                                        |
| `StatusReporting` | `STOPPED` before start, `RUNNING` after start, `STOPPED` after stop, all markets covered |
