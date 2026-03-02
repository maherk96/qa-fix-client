package com.marketdatahub.book;

import com.marketdatahub.listener.MarketDataListener;
import com.marketdatahub.model.ParsedMessage;
import com.marketdatahub.model.PriceLevel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe, in-memory order book that maintains the latest {@link BookEntry} for
 * every symbol seen across all configured markets.
 * <p>
 * The book implements {@link MarketDataListener} so it can be passed directly to
 * {@link com.marketdatahub.feed.MarketFeed#start(MarketDataListener)}. On each
 * incoming {@link ParsedMessage} the corresponding {@link BookEntry} — including its
 * full bid/offer ladder — is atomically replaced in the underlying
 * {@link ConcurrentHashMap}.
 * <p>
 * All public query methods return {@link Optional} to make absent-symbol handling
 * explicit at the call site. Ladder-specific methods ({@link #getBidLadder},
 * {@link #getOfferLadder}, {@link #getBidDepth}, etc.) provide access to the full
 * depth beyond the best rung.
 */
@Slf4j
public class MarketBook implements MarketDataListener {

    private final ConcurrentHashMap<String, BookEntry> book = new ConcurrentHashMap<>();

    /**
     * Holds per-symbol update callbacks registered via
     * {@link com.marketdatahub.MarketDataHub#onSymbolUpdate}.
     * Values are copy-on-write lists; the outer map itself is a ConcurrentHashMap.
     */
    private final ConcurrentHashMap<String, List<Consumer<BookEntry>>> callbacks =
            new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // MarketDataListener
    // -----------------------------------------------------------------------

    /**
     * Processes a normalised market-data update, replacing the existing
     * {@link BookEntry} (including its full price ladder) for the message's symbol
     * and firing any registered callbacks.
     *
     * @param message the parsed, non-null market data update
     */
    @Override
    public void onMessage(ParsedMessage message) {
        if (message == null || message.getSymbol() == null) {
            log.warn("Received null or symbol-less ParsedMessage – ignoring");
            return;
        }

        BookEntry entry = BookEntry.builder()
                .symbol(message.getSymbol())
                .bidLevels(message.getBidLevels())
                .offerLevels(message.getOfferLevels())
                .lastTradePrice(message.getLastTradePrice())
                .marketName(message.getMarketName())
                .lastUpdated(message.getTimestamp())
                .build();

        book.put(message.getSymbol(), entry);
        log.debug("Updated book entry for symbol={} from market={} bidDepth={} offerDepth={}",
                message.getSymbol(), message.getMarketName(),
                entry.getBidLevels().size(), entry.getOfferLevels().size());

        fireCallbacks(message.getSymbol(), entry);
    }

    // -----------------------------------------------------------------------
    // Query API — best rung
    // -----------------------------------------------------------------------

    /**
     * Returns the best bid price (rung 1) for the given symbol.
     * Returns empty if the symbol is unknown or the entry carries no bid levels.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the best bid price, or empty if absent
     */
    public Optional<Double> getBestBid(String symbol) {
        return getBookEntry(symbol)
                .map(BookEntry::getBidLevels)
                .filter(l -> !l.isEmpty())
                .map(l -> l.get(0).getPrice());
    }

    /**
     * Returns the best offer (ask) price (rung 1) for the given symbol.
     * Returns empty if the symbol is unknown or the entry carries no offer levels.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the best offer price, or empty if absent
     */
    public Optional<Double> getBestOffer(String symbol) {
        return getBookEntry(symbol)
                .map(BookEntry::getOfferLevels)
                .filter(l -> !l.isEmpty())
                .map(l -> l.get(0).getPrice());
    }

    /**
     * Returns the mid price {@code (bestBid + bestOffer) / 2} for the given symbol.
     * Returns empty if the symbol is unknown or either side of the book is absent.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the mid price, or empty if unavailable
     */
    public Optional<Double> getMidPrice(String symbol) {
        Optional<Double> bid = getBestBid(symbol);
        Optional<Double> offer = getBestOffer(symbol);
        if (bid.isEmpty() || offer.isEmpty()) return Optional.empty();
        return Optional.of((bid.get() + offer.get()) / 2.0);
    }

    /**
     * Returns the spread {@code (bestOffer - bestBid)} for the given symbol.
     * Returns empty if the symbol is unknown or either side of the book is absent.
     * Logs a warning if a crossed book is detected (bid &gt;= offer).
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the spread, or empty if unavailable
     */
    public Optional<Double> getSpread(String symbol) {
        Optional<Double> bid = getBestBid(symbol);
        Optional<Double> offer = getBestOffer(symbol);
        if (bid.isEmpty() || offer.isEmpty()) return Optional.empty();
        double spread = offer.get() - bid.get();
        if (spread < 0) {
            log.warn("Crossed book detected for symbol={} bid={} offer={}",
                    symbol, bid.get(), offer.get());
        }
        return Optional.of(spread);
    }

    /**
     * Returns the spread expressed in basis points: {@code (spread / mid) * 10,000}.
     * Returns empty if the symbol is unknown, either side is absent, or mid is zero.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the spread in bps, or empty
     */
    public Optional<Double> getSpreadInBps(String symbol) {
        Optional<Double> bid = getBestBid(symbol);
        Optional<Double> offer = getBestOffer(symbol);
        if (bid.isEmpty() || offer.isEmpty()) return Optional.empty();
        double mid = (bid.get() + offer.get()) / 2.0;
        if (mid == 0) return Optional.empty();
        return Optional.of(((offer.get() - bid.get()) / mid) * 10_000);
    }

    /**
     * Returns the size-weighted mid price:
     * {@code (bidPrice * offerSize + offerPrice * bidSize) / (bidSize + offerSize)}.
     * More accurate than simple mid when bid/offer sizes are asymmetric.
     * Returns empty if the symbol is unknown, any price or size is absent, or total size is zero.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the weighted mid price, or empty
     */
    public Optional<Double> getWeightedMidPrice(String symbol) {
        Optional<Double> bid = getBestBid(symbol);
        Optional<Double> offer = getBestOffer(symbol);
        Optional<Long> bidSz = getBidSize(symbol);
        Optional<Long> offerSz = getOfferSize(symbol);
        if (bid.isEmpty() || offer.isEmpty() || bidSz.isEmpty() || offerSz.isEmpty())
            return Optional.empty();
        double totalSize = bidSz.get() + offerSz.get();
        if (totalSize == 0) return Optional.empty();
        return Optional.of(
                (bid.get() * offerSz.get() + offer.get() * bidSz.get()) / totalSize
        );
    }

    /**
     * Returns the last trade price for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the last trade price, or empty if the symbol is unknown
     */
    public Optional<Double> getLastTradePrice(String symbol) {
        return getBookEntry(symbol).map(BookEntry::getLastTradePrice);
    }

    /**
     * Returns the bid-side quantity at rung 1 for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the best bid size, or empty if absent
     */
    public Optional<Long> getBidSize(String symbol) {
        return getBookEntry(symbol)
                .map(BookEntry::getBidLevels)
                .filter(l -> !l.isEmpty())
                .map(l -> l.get(0).getSize());
    }

    /**
     * Returns the offer-side quantity at rung 1 for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the best offer size, or empty if absent
     */
    public Optional<Long> getOfferSize(String symbol) {
        return getBookEntry(symbol)
                .map(BookEntry::getOfferLevels)
                .filter(l -> !l.isEmpty())
                .map(l -> l.get(0).getSize());
    }

    /**
     * Returns the total available liquidity at rung 1: {@code bidSize + offerSize}.
     * Returns empty only if the symbol is unknown entirely.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing total rung-1 liquidity, or empty if unknown
     */
    public Optional<Long> getTotalLiquidity(String symbol) {
        return getBookEntry(symbol).map(e -> {
            long bidSz = e.getBidLevels().isEmpty() ? 0L : e.getBidLevels().get(0).getSize();
            long offerSz = e.getOfferLevels().isEmpty() ? 0L : e.getOfferLevels().get(0).getSize();
            return bidSz + offerSz;
        });
    }

    /**
     * Returns the liquidity imbalance at rung 1:
     * {@code (bidSize - offerSize) / (bidSize + offerSize)}.
     * Ranges from {@code -1.0} (all size on offer) to {@code +1.0} (all size on bid).
     * A positive value indicates buy-side pressure; negative indicates sell-side pressure.
     * Returns empty if the symbol is unknown or total rung-1 size is zero.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the imbalance ratio, or empty
     */
    public Optional<Double> getLiquidityImbalance(String symbol) {
        return getBookEntry(symbol).flatMap(e -> {
            long bidSz = e.getBidLevels().isEmpty() ? 0L : e.getBidLevels().get(0).getSize();
            long offerSz = e.getOfferLevels().isEmpty() ? 0L : e.getOfferLevels().get(0).getSize();
            double total = bidSz + offerSz;
            if (total == 0) return Optional.empty();
            return Optional.of((bidSz - offerSz) / total);
        });
    }

    /**
     * Returns the raw bid-to-offer size ratio at rung 1: {@code bidSize / offerSize}.
     * Returns empty if the symbol is unknown or offer size at rung 1 is zero.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the bid/offer size ratio, or empty
     */
    public Optional<Double> getBidOfferRatio(String symbol) {
        return getBookEntry(symbol).flatMap(e -> {
            if (e.getOfferLevels().isEmpty() || e.getOfferLevels().get(0).getSize() == 0)
                return Optional.empty();
            long bidSz = e.getBidLevels().isEmpty() ? 0L : e.getBidLevels().get(0).getSize();
            return Optional.of((double) bidSz / e.getOfferLevels().get(0).getSize());
        });
    }

    // -----------------------------------------------------------------------
    // Query API — ladder depth
    // -----------------------------------------------------------------------

    /**
     * Returns the bid price at the given rung (1-based).
     * Rung 1 is the best (highest) bid.
     *
     * @param symbol the instrument symbol
     * @param rung   1-based rung index
     * @return an {@link Optional} containing the bid price at {@code rung}, or empty if unavailable
     */
    public Optional<Double> getBidAtRung(String symbol, int rung) {
        return getBookEntry(symbol)
                .map(BookEntry::getBidLevels)
                .filter(l -> l.size() >= rung)
                .map(l -> l.get(rung - 1).getPrice());
    }

    /**
     * Returns the offer price at the given rung (1-based).
     * Rung 1 is the best (lowest) offer.
     *
     * @param symbol the instrument symbol
     * @param rung   1-based rung index
     * @return an {@link Optional} containing the offer price at {@code rung}, or empty if unavailable
     */
    public Optional<Double> getOfferAtRung(String symbol, int rung) {
        return getBookEntry(symbol)
                .map(BookEntry::getOfferLevels)
                .filter(l -> l.size() >= rung)
                .map(l -> l.get(rung - 1).getPrice());
    }

    /**
     * Returns the full bid ladder for the symbol, sorted best → worst (positionNo ascending).
     * Returns an empty list if the symbol is unknown.
     *
     * @param symbol the instrument symbol
     * @return an unmodifiable list of bid {@link PriceLevel}s, best first; empty if unknown
     */
    public List<PriceLevel> getBidLadder(String symbol) {
        return getBookEntry(symbol)
                .map(BookEntry::getBidLevels)
                .orElse(List.of());
    }

    /**
     * Returns the full offer ladder for the symbol, sorted best → worst (positionNo ascending).
     * Returns an empty list if the symbol is unknown.
     *
     * @param symbol the instrument symbol
     * @return an unmodifiable list of offer {@link PriceLevel}s, best first; empty if unknown
     */
    public List<PriceLevel> getOfferLadder(String symbol) {
        return getBookEntry(symbol)
                .map(BookEntry::getOfferLevels)
                .orElse(List.of());
    }

    /**
     * Returns the number of bid rungs currently available for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return number of bid price levels; 0 if the symbol is unknown
     */
    public int getBidDepth(String symbol) {
        return getBookEntry(symbol)
                .map(e -> e.getBidLevels().size())
                .orElse(0);
    }

    /**
     * Returns the number of offer rungs currently available for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return number of offer price levels; 0 if the symbol is unknown
     */
    public int getOfferDepth(String symbol) {
        return getBookEntry(symbol)
                .map(e -> e.getOfferLevels().size())
                .orElse(0);
    }

    // -----------------------------------------------------------------------
    // Query API — book state
    // -----------------------------------------------------------------------

    /**
     * Returns a snapshot list of all symbols currently held in the book.
     * The list is a stable copy and will not reflect subsequent updates.
     *
     * @return an unmodifiable list of active symbol strings
     */
    public List<String> getActiveSymbols() {
        return List.copyOf(book.keySet());
    }

    /**
     * Returns {@code true} if the symbol is present in the book.
     *
     * @param symbol the instrument symbol
     * @return true if the symbol has been seen at least once
     */
    public boolean hasSymbol(String symbol) {
        return book.containsKey(symbol);
    }

    /**
     * Returns the timestamp of the last update for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the last updated {@link Instant}, or empty
     */
    public Optional<Instant> getLastUpdated(String symbol) {
        return getBookEntry(symbol).map(BookEntry::getLastUpdated);
    }

    /**
     * Returns the name of the market feed that last updated the given symbol.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the market name, or empty if unknown
     */
    public Optional<String> getMarketName(String symbol) {
        return getBookEntry(symbol).map(BookEntry::getMarketName);
    }

    /**
     * Returns {@code true} if the symbol's last update is older than the given threshold,
     * or if the symbol has never been seen.
     *
     * @param symbol    the instrument symbol
     * @param threshold the maximum acceptable age of the last update
     * @return true if the entry is stale or absent
     */
    public boolean isStale(String symbol, Duration threshold) {
        BookEntry e = book.get(symbol);
        if (e == null || e.getLastUpdated() == null) return true;
        return Duration.between(e.getLastUpdated(), Instant.now()).compareTo(threshold) > 0;
    }

    /**
     * Returns {@code true} if the book for the given symbol is crossed,
     * meaning the best bid price &gt;= the best offer price.
     *
     * @param symbol the instrument symbol
     * @return true if the book is crossed; false if either side is absent or symbol unknown
     */
    public boolean isCrossed(String symbol) {
        Optional<Double> bid = getBestBid(symbol);
        Optional<Double> offer = getBestOffer(symbol);
        if (bid.isEmpty() || offer.isEmpty()) return false;
        return bid.get() >= offer.get();
    }

    // -----------------------------------------------------------------------
    // Query API — multi-symbol / aggregation
    // -----------------------------------------------------------------------

    /**
     * Returns all symbols whose last update is older than the given threshold.
     *
     * @param threshold the maximum acceptable age of the last update
     * @return a list of stale symbol strings
     */
    public List<String> getStaleSymbols(Duration threshold) {
        return book.keySet().stream()
                .filter(symbol -> isStale(symbol, threshold))
                .toList();
    }

    /**
     * Returns all symbols currently in a crossed state (bidPrice &gt;= offerPrice).
     *
     * @return a list of crossed symbol strings
     */
    public List<String> getCrossedSymbols() {
        return book.entrySet().stream()
                .filter(entry -> isCrossed(entry.getKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns the symbols for a specific market feed, filtered by market name.
     *
     * @param marketName the name of the feed/market
     * @return a list of symbols last updated by that market
     */
    public List<String> getActiveSymbolsByMarket(String marketName) {
        return book.entrySet().stream()
                .filter(e -> marketName.equals(e.getValue().getMarketName()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns the top {@code n} symbols with the tightest spreads,
     * sorted ascending by {@code (offerPrice - bidPrice)} at rung 1.
     * Symbols with no bid or offer levels are excluded.
     *
     * @param n the number of symbols to return
     * @return an ordered list of symbol strings, tightest spread first
     */
    public List<String> getTopNBySpread(int n) {
        return book.entrySet().stream()
                .filter(e -> !e.getValue().getBidLevels().isEmpty()
                          && !e.getValue().getOfferLevels().isEmpty())
                .sorted(Comparator.comparingDouble(e ->
                        e.getValue().getOfferLevels().get(0).getPrice()
                        - e.getValue().getBidLevels().get(0).getPrice()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns the full {@link BookEntry} snapshot for the given symbol.
     *
     * @param symbol the instrument symbol
     * @return an {@link Optional} containing the {@link BookEntry}, or empty if unknown
     */
    public Optional<BookEntry> getSnapshot(String symbol) {
        return getBookEntry(symbol);
    }

    // -----------------------------------------------------------------------
    // Callback management
    // -----------------------------------------------------------------------

    /**
     * Registers a callback that is invoked every time the {@link BookEntry} for
     * {@code symbol} is updated. Multiple callbacks per symbol are supported.
     *
     * @param symbol   the instrument symbol to watch
     * @param callback the consumer to invoke with the updated {@link BookEntry}
     */
    public void registerCallback(String symbol, Consumer<BookEntry> callback) {
        callbacks.compute(symbol, (k, existing) -> {
            List<Consumer<BookEntry>> list = (existing == null)
                    ? new ArrayList<>()
                    : new ArrayList<>(existing);
            list.add(callback);
            return list;
        });
    }

    /**
     * Removes a previously registered callback for the given symbol.
     * If the callback was not registered, this is a no-op.
     * If removing the callback leaves the list empty, the symbol entry
     * is removed from the callbacks map entirely.
     *
     * @param symbol   the instrument symbol
     * @param callback the consumer to remove
     */
    public void removeCallback(String symbol, Consumer<BookEntry> callback) {
        callbacks.computeIfPresent(symbol, (k, existing) -> {
            List<Consumer<BookEntry>> updated = new ArrayList<>(existing);
            updated.remove(callback);
            return updated.isEmpty() ? null : updated;
        });
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Returns an {@link Optional} wrapping the {@link BookEntry} for {@code symbol},
     * or empty if the symbol has never been seen. Used internally to avoid repeated
     * {@code book.get} + null-checks across query methods.
     *
     * @param symbol the instrument symbol
     * @return {@link Optional} containing the entry, or empty
     */
    private Optional<BookEntry> getBookEntry(String symbol) {
        return Optional.ofNullable(book.get(symbol));
    }

    private void fireCallbacks(String symbol, BookEntry entry) {
        List<Consumer<BookEntry>> registered = callbacks.get(symbol);
        if (registered == null || registered.isEmpty()) return;
        List<Consumer<BookEntry>> snapshot = List.copyOf(registered);
        for (Consumer<BookEntry> cb : snapshot) {
            try {
                cb.accept(entry);
            } catch (Exception ex) {
                log.error("Callback threw an exception for symbol={}", symbol, ex);
            }
        }
    }

    /**
     * Exposes the raw backing map for testing purposes.
     * Not part of the public API surface.
     *
     * @return an unmodifiable view of the internal book map
     */
    Map<String, BookEntry> internalBook() {
        return java.util.Collections.unmodifiableMap(book);
    }
}
