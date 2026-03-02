package com.marketdatahub;

import com.marketdatahub.book.BookEntry;
import com.marketdatahub.book.MarketBook;
import com.marketdatahub.model.MDEntryType;
import com.marketdatahub.model.ParsedMessage;
import com.marketdatahub.model.PriceLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarketBook}.
 *
 * <p>Organised into nested classes by feature area:
 * <ul>
 *   <li>{@link EmptyBook}     — getters on an empty / unknown symbol</li>
 *   <li>{@link Ingestion}     — onMessage storage, ladder population, entry replacement</li>
 *   <li>{@link BestRung}      — getBestBid, getBestOffer, getBidSize, getOfferSize</li>
 *   <li>{@link DerivedPrices} — getMidPrice, getSpread, getSpreadInBps, getWeightedMidPrice</li>
 *   <li>{@link Liquidity}     — getTotalLiquidity, getLiquidityImbalance, getBidOfferRatio</li>
 *   <li>{@link LadderDepth}   — getBidAtRung, getOfferAtRung, getBidLadder, getOfferLadder,
 *                               getBidDepth, getOfferDepth</li>
 *   <li>{@link BookState}     — hasSymbol, getLastUpdated, getMarketName, isStale, isCrossed</li>
 *   <li>{@link Aggregation}   — getStaleSymbols, getCrossedSymbols, getActiveSymbolsByMarket,
 *                               getTopNBySpread</li>
 *   <li>{@link Callbacks}     — register, remove, isolation, exception-safety</li>
 *   <li>{@link ThreadSafety}  — concurrent updates</li>
 * </ul>
 */
class MarketBookTest {

    private MarketBook book;

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a single-rung {@link ParsedMessage}. A TRADE level is only appended
     * when {@code last != 0.0}.
     */
    private static ParsedMessage msg(String symbol, double bid, double offer,
                                     long bidSz, long offerSz, double last) {
        return msg(symbol, "TEST", bid, offer, bidSz, offerSz, last);
    }

    private static ParsedMessage msg(String symbol, String market,
                                     double bid, double offer,
                                     long bidSz, long offerSz, double last) {
        List<PriceLevel> levels = new ArrayList<>();
        levels.add(PriceLevel.builder()
                .entryType(MDEntryType.BID).price(bid).size(bidSz).positionNo(1).build());
        levels.add(PriceLevel.builder()
                .entryType(MDEntryType.OFFER).price(offer).size(offerSz).positionNo(1).build());
        if (last != 0.0) {
            levels.add(PriceLevel.builder()
                    .entryType(MDEntryType.TRADE).price(last).size(0).positionNo(1).build());
        }
        return ParsedMessage.builder()
                .symbol(symbol)
                .levels(levels)
                .marketName(market)
                .timestamp(Instant.now())
                .build();
    }

    /** Builds a multi-rung EUR/USD ladder: 3 bid levels, 2 offer levels. */
    private static ParsedMessage multiRungMsg() {
        return ParsedMessage.builder()
                .symbol("EUR/USD")
                .marketName("EBS")
                .timestamp(Instant.now())
                .levels(List.of(
                        PriceLevel.builder().entryType(MDEntryType.BID).price(1.0850).size(1_000_000L).positionNo(1).build(),
                        PriceLevel.builder().entryType(MDEntryType.BID).price(1.0849).size(2_000_000L).positionNo(2).build(),
                        PriceLevel.builder().entryType(MDEntryType.BID).price(1.0848).size(3_000_000L).positionNo(3).build(),
                        PriceLevel.builder().entryType(MDEntryType.OFFER).price(1.0852).size(1_500_000L).positionNo(1).build(),
                        PriceLevel.builder().entryType(MDEntryType.OFFER).price(1.0853).size(2_500_000L).positionNo(2).build()
                ))
                .build();
    }

    @BeforeEach
    void setUp() {
        book = new MarketBook();
    }

    // =========================================================================
    @Nested
    @DisplayName("Empty book")
    class EmptyBook {

        @Test
        @DisplayName("All Optional getters return empty for an unknown symbol")
        void allOptionalGettersReturnEmpty() {
            String s = "FOO/BAR";
            assertTrue(book.getBestBid(s).isEmpty());
            assertTrue(book.getBestOffer(s).isEmpty());
            assertTrue(book.getMidPrice(s).isEmpty());
            assertTrue(book.getSpread(s).isEmpty());
            assertTrue(book.getSpreadInBps(s).isEmpty());
            assertTrue(book.getWeightedMidPrice(s).isEmpty());
            assertTrue(book.getLastTradePrice(s).isEmpty());
            assertTrue(book.getBidSize(s).isEmpty());
            assertTrue(book.getOfferSize(s).isEmpty());
            assertTrue(book.getTotalLiquidity(s).isEmpty());
            assertTrue(book.getLiquidityImbalance(s).isEmpty());
            assertTrue(book.getBidOfferRatio(s).isEmpty());
            assertTrue(book.getSnapshot(s).isEmpty());
            assertTrue(book.getLastUpdated(s).isEmpty());
            assertTrue(book.getMarketName(s).isEmpty());
            assertTrue(book.getActiveSymbols().isEmpty());
        }

        @Test
        @DisplayName("Boolean state methods return safe defaults for an unknown symbol")
        void booleanStateMethodsReturnSafeDefaults() {
            assertFalse(book.hasSymbol("UNKNOWN"));
            assertTrue(book.isStale("UNKNOWN", Duration.ofSeconds(60)));
            assertFalse(book.isCrossed("UNKNOWN"));
            assertEquals(0, book.getBidDepth("UNKNOWN"));
            assertEquals(0, book.getOfferDepth("UNKNOWN"));
            assertTrue(book.getBidLadder("UNKNOWN").isEmpty());
            assertTrue(book.getOfferLadder("UNKNOWN").isEmpty());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Ingestion — onMessage")
    class Ingestion {

        @Test
        @DisplayName("onMessage stores a BookEntry with correct ladder values")
        void storesCorrectBookEntry() {
            book.onMessage(msg("EUR/USD", 1.0850, 1.0852, 1_000_000L, 2_000_000L, 1.0851));

            BookEntry e = book.getSnapshot("EUR/USD").orElseThrow();
            assertEquals("EUR/USD", e.getSymbol());
            assertEquals("TEST",    e.getMarketName());
            assertNotNull(e.getLastUpdated());

            assertFalse(e.getBidLevels().isEmpty());
            assertFalse(e.getOfferLevels().isEmpty());
            assertEquals(1.0850,       e.getBidLevels().get(0).getPrice(),  1e-6);
            assertEquals(1.0852,       e.getOfferLevels().get(0).getPrice(), 1e-6);
            assertEquals(1_000_000L,   e.getBidLevels().get(0).getSize());
            assertEquals(2_000_000L,   e.getOfferLevels().get(0).getSize());
            assertEquals(1.0851,       e.getLastTradePrice(), 1e-6);
        }

        @Test
        @DisplayName("Multi-rung message populates full bid and offer ladders")
        void multiRungMessagePopulatesLadder() {
            book.onMessage(multiRungMsg());

            assertEquals(3, book.getBidDepth("EUR/USD"));
            assertEquals(2, book.getOfferDepth("EUR/USD"));

            assertEquals(1.0850, book.getBidAtRung("EUR/USD", 1).orElseThrow(), 1e-6);
            assertEquals(1.0849, book.getBidAtRung("EUR/USD", 2).orElseThrow(), 1e-6);
            assertEquals(1.0848, book.getBidAtRung("EUR/USD", 3).orElseThrow(), 1e-6);
            assertTrue(book.getBidAtRung("EUR/USD", 4).isEmpty());

            assertEquals(1.0852, book.getOfferAtRung("EUR/USD", 1).orElseThrow(), 1e-6);
            assertEquals(1.0853, book.getOfferAtRung("EUR/USD", 2).orElseThrow(), 1e-6);
            assertTrue(book.getOfferAtRung("EUR/USD", 3).isEmpty());

            assertEquals(3, book.getBidLadder("EUR/USD").size());
            assertEquals(2, book.getOfferLadder("EUR/USD").size());
        }

        @Test
        @DisplayName("Successive updates atomically replace the previous BookEntry")
        void replacesExistingEntry() {
            book.onMessage(msg("EUR/USD", 1.0850, 1.0852, 1_000L, 2_000L, 0));
            book.onMessage(msg("EUR/USD", 1.0860, 1.0862, 1_500L, 2_500L, 1.0861));

            assertEquals(1.0860, book.getBestBid("EUR/USD").orElseThrow(),   1e-6);
            assertEquals(1.0862, book.getBestOffer("EUR/USD").orElseThrow(), 1e-6);
        }

        @Test
        @DisplayName("onMessage with null message does not throw")
        void nullMessageDoesNotThrow() {
            assertDoesNotThrow(() -> book.onMessage(null));
        }

        @Test
        @DisplayName("onMessage with null symbol does not throw or corrupt the book")
        void nullSymbolDoesNotThrow() {
            ParsedMessage bad = ParsedMessage.builder()
                    .symbol(null).levels(List.of())
                    .marketName("TEST").timestamp(Instant.now()).build();
            assertDoesNotThrow(() -> book.onMessage(bad));
            assertTrue(book.getActiveSymbols().isEmpty());
        }

        @Test
        @DisplayName("getActiveSymbols includes every updated symbol")
        void activeSymbolsIncludesAll() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            book.onMessage(msg("USD/JPY", 149.0, 150.0, 0L, 0L, 0));
            book.onMessage(msg("GBP/USD", 1.27, 1.28, 0L, 0L, 0));

            List<String> symbols = book.getActiveSymbols();
            assertEquals(3, symbols.size());
            assertTrue(symbols.containsAll(List.of("EUR/USD", "USD/JPY", "GBP/USD")));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Best rung — getBestBid / getBestOffer / getBidSize / getOfferSize")
    class BestRung {

        @Test
        @DisplayName("getBestBid returns rung-1 bid price")
        void getBestBidReturnsRung1() {
            book.onMessage(msg("USD/JPY", 149.50, 149.55, 500L, 500L, 149.52));
            assertEquals(149.50, book.getBestBid("USD/JPY").orElseThrow(), 1e-6);
        }

        @Test
        @DisplayName("getBestOffer returns rung-1 offer price")
        void getBestOfferReturnsRung1() {
            book.onMessage(msg("GBP/USD", 1.2700, 1.2705, 100L, 200L, 0));
            assertEquals(1.2705, book.getBestOffer("GBP/USD").orElseThrow(), 1e-6);
        }

        @Test
        @DisplayName("getBidSize returns rung-1 bid quantity")
        void getBidSizeReturnsRung1() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 1_000_000L, 2_000_000L, 0));
            assertEquals(1_000_000L, book.getBidSize("EUR/USD").orElseThrow());
        }

        @Test
        @DisplayName("getOfferSize returns rung-1 offer quantity")
        void getOfferSizeReturnsRung1() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 1_000_000L, 2_000_000L, 0));
            assertEquals(2_000_000L, book.getOfferSize("EUR/USD").orElseThrow());
        }

        @Test
        @DisplayName("getBestBid returns empty when only offer levels present")
        void getBestBidEmptyWhenOneSided() {
            ParsedMessage bidOnly = ParsedMessage.builder()
                    .symbol("EUR/USD").marketName("TEST").timestamp(Instant.now())
                    .levels(List.of(
                            PriceLevel.builder().entryType(MDEntryType.OFFER)
                                    .price(1.09).size(100L).positionNo(1).build()))
                    .build();
            book.onMessage(bidOnly);
            assertTrue(book.getBestBid("EUR/USD").isEmpty());
            assertTrue(book.getBestOffer("EUR/USD").isPresent());
        }

        @Test
        @DisplayName("getBestOffer returns empty when only bid levels present")
        void getBestOfferEmptyWhenOneSided() {
            ParsedMessage offerOnly = ParsedMessage.builder()
                    .symbol("EUR/USD").marketName("TEST").timestamp(Instant.now())
                    .levels(List.of(
                            PriceLevel.builder().entryType(MDEntryType.BID)
                                    .price(1.08).size(100L).positionNo(1).build()))
                    .build();
            book.onMessage(offerOnly);
            assertTrue(book.getBestOffer("EUR/USD").isEmpty());
            assertTrue(book.getBestBid("EUR/USD").isPresent());
        }

        @Test
        @DisplayName("getLastTradePrice returns the TRADE level price")
        void getLastTradePriceReturnsTradeLevel() {
            book.onMessage(msg("AUD/USD", 0.6500, 0.6502, 0L, 0L, 0.6501));
            assertEquals(0.6501, book.getLastTradePrice("AUD/USD").orElseThrow(), 1e-6);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Derived prices — getMidPrice / getSpread / getSpreadInBps / getWeightedMidPrice")
    class DerivedPrices {

        @Test
        @DisplayName("getMidPrice returns (bid + offer) / 2")
        void getMidPriceCorrect() {
            book.onMessage(msg("EUR/USD", 1.0800, 1.0900, 0L, 0L, 0));
            assertEquals(1.0850, book.getMidPrice("EUR/USD").orElseThrow(), 1e-6);
        }

        @Test
        @DisplayName("getMidPrice returns empty when only one side present")
        void getMidPriceEmptyWhenOneSided() {
            ParsedMessage bidOnly = ParsedMessage.builder()
                    .symbol("EUR/USD").marketName("TEST").timestamp(Instant.now())
                    .levels(List.of(PriceLevel.builder().entryType(MDEntryType.BID)
                            .price(1.08).size(0L).positionNo(1).build()))
                    .build();
            book.onMessage(bidOnly);
            assertTrue(book.getMidPrice("EUR/USD").isEmpty());
        }

        @Test
        @DisplayName("getSpread returns offer - bid")
        void getSpreadCorrect() {
            book.onMessage(msg("EUR/USD", 1.0800, 1.0810, 0L, 0L, 0));
            assertEquals(0.0010, book.getSpread("EUR/USD").orElseThrow(), 1e-6);
        }

        @Test
        @DisplayName("getSpread still returns a value (negative) for a crossed book")
        void getSpreadReturnValueForCrossedBook() {
            book.onMessage(msg("EUR/USD", 1.0855, 1.0850, 0L, 0L, 0));
            Optional<Double> spread = book.getSpread("EUR/USD");
            assertTrue(spread.isPresent());
            assertTrue(spread.get() < 0, "spread should be negative for crossed book");
        }

        @Test
        @DisplayName("getSpreadInBps returns (spread / mid) * 10000")
        void getSpreadInBpsCorrect() {
            // bid=1.0, offer=1.002 → spread=0.002, mid=1.001
            // bps = (0.002 / 1.001) * 10000 ≈ 19.98
            book.onMessage(msg("EUR/USD", 1.0000, 1.0020, 0L, 0L, 0));
            Optional<Double> bps = book.getSpreadInBps("EUR/USD");
            assertTrue(bps.isPresent());
            assertEquals(((1.0020 - 1.0000) / ((1.0000 + 1.0020) / 2)) * 10_000,
                    bps.get(), 1e-4);
        }

        @Test
        @DisplayName("getSpreadInBps returns empty for unknown symbol")
        void getSpreadInBpsEmptyForUnknown() {
            assertTrue(book.getSpreadInBps("UNKNOWN").isEmpty());
        }

        @Test
        @DisplayName("getWeightedMidPrice is skewed toward the larger side")
        void getWeightedMidPriceSkewedCorrectly() {
            // bid=1.08, bidSz=1; offer=1.09, offerSz=3
            // wMid = (1.08*3 + 1.09*1) / (1+3) = (3.24 + 1.09) / 4 = 4.33/4 = 1.0825
            book.onMessage(msg("EUR/USD", 1.0800, 1.0900, 1L, 3L, 0));
            Optional<Double> wMid = book.getWeightedMidPrice("EUR/USD");
            assertTrue(wMid.isPresent());
            assertEquals(1.0825, wMid.get(), 1e-6);
            // weighted mid should be closer to bid because offer side is heavier
            assertTrue(wMid.get() < book.getMidPrice("EUR/USD").orElseThrow());
        }

        @Test
        @DisplayName("getWeightedMidPrice returns empty when total size is zero")
        void getWeightedMidPriceEmptyWhenZeroSize() {
            book.onMessage(msg("EUR/USD", 1.0800, 1.0900, 0L, 0L, 0));
            assertTrue(book.getWeightedMidPrice("EUR/USD").isEmpty());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Liquidity — getTotalLiquidity / getLiquidityImbalance / getBidOfferRatio")
    class Liquidity {

        @Test
        @DisplayName("getTotalLiquidity returns bidSize + offerSize at rung 1")
        void getTotalLiquidityCorrect() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 1_000_000L, 2_000_000L, 0));
            assertEquals(3_000_000L, book.getTotalLiquidity("EUR/USD").orElseThrow());
        }

        @Test
        @DisplayName("getTotalLiquidity returns empty for unknown symbol")
        void getTotalLiquidityEmptyForUnknown() {
            assertTrue(book.getTotalLiquidity("UNKNOWN").isEmpty());
        }

        @Test
        @DisplayName("getLiquidityImbalance is positive when bid size dominates")
        void imbalancePositiveWhenBidHeavy() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 3_000L, 1_000L, 0));
            // (3000 - 1000) / 4000 = 0.5
            assertEquals(0.5, book.getLiquidityImbalance("EUR/USD").orElseThrow(), 1e-9);
        }

        @Test
        @DisplayName("getLiquidityImbalance is negative when offer size dominates")
        void imbalanceNegativeWhenOfferHeavy() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 1_000L, 3_000L, 0));
            // (1000 - 3000) / 4000 = -0.5
            assertEquals(-0.5, book.getLiquidityImbalance("EUR/USD").orElseThrow(), 1e-9);
        }

        @Test
        @DisplayName("getLiquidityImbalance returns zero when sizes are equal")
        void imbalanceZeroWhenEqual() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 2_000L, 2_000L, 0));
            assertEquals(0.0, book.getLiquidityImbalance("EUR/USD").orElseThrow(), 1e-9);
        }

        @Test
        @DisplayName("getLiquidityImbalance returns empty when total size is zero")
        void imbalanceEmptyWhenZeroSize() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            assertTrue(book.getLiquidityImbalance("EUR/USD").isEmpty());
        }

        @Test
        @DisplayName("getBidOfferRatio returns bidSize / offerSize")
        void getBidOfferRatioCorrect() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 2_000L, 1_000L, 0));
            assertEquals(2.0, book.getBidOfferRatio("EUR/USD").orElseThrow(), 1e-9);
        }

        @Test
        @DisplayName("getBidOfferRatio returns empty when offer size is zero")
        void getBidOfferRatioEmptyWhenOfferSizeZero() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 500L, 0L, 0));
            assertTrue(book.getBidOfferRatio("EUR/USD").isEmpty());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Ladder depth — getBidAtRung / getOfferAtRung / getBidLadder / getOfferLadder")
    class LadderDepth {

        @Test
        @DisplayName("Rung accessors return correct price at each level")
        void rungAccessorsReturnCorrectPrices() {
            book.onMessage(multiRungMsg());

            assertEquals(1.0850, book.getBidAtRung("EUR/USD", 1).orElseThrow(), 1e-6);
            assertEquals(1.0849, book.getBidAtRung("EUR/USD", 2).orElseThrow(), 1e-6);
            assertEquals(1.0848, book.getBidAtRung("EUR/USD", 3).orElseThrow(), 1e-6);
            assertTrue(book.getBidAtRung("EUR/USD", 4).isEmpty());

            assertEquals(1.0852, book.getOfferAtRung("EUR/USD", 1).orElseThrow(), 1e-6);
            assertEquals(1.0853, book.getOfferAtRung("EUR/USD", 2).orElseThrow(), 1e-6);
            assertTrue(book.getOfferAtRung("EUR/USD", 3).isEmpty());
        }

        @Test
        @DisplayName("getBidLadder and getOfferLadder return all levels in order")
        void ladderReturnAllLevels() {
            book.onMessage(multiRungMsg());

            List<com.marketdatahub.model.PriceLevel> bidLadder = book.getBidLadder("EUR/USD");
            assertEquals(3, bidLadder.size());
            assertTrue(bidLadder.get(0).getPrice() > bidLadder.get(1).getPrice(),
                    "bid ladder should be sorted descending by price (best first)");

            List<com.marketdatahub.model.PriceLevel> offerLadder = book.getOfferLadder("EUR/USD");
            assertEquals(2, offerLadder.size());
            assertTrue(offerLadder.get(0).getPrice() < offerLadder.get(1).getPrice(),
                    "offer ladder should be sorted ascending by price (best first)");
        }

        @Test
        @DisplayName("getBidLadder / getOfferLadder return empty list for unknown symbol")
        void ladderEmptyForUnknown() {
            assertTrue(book.getBidLadder("UNKNOWN").isEmpty());
            assertTrue(book.getOfferLadder("UNKNOWN").isEmpty());
            assertEquals(0, book.getBidDepth("UNKNOWN"));
            assertEquals(0, book.getOfferDepth("UNKNOWN"));
        }

        @Test
        @DisplayName("getBidDepth and getOfferDepth reflect actual rung counts")
        void depthMatchesLadderSize() {
            book.onMessage(multiRungMsg());
            assertEquals(3, book.getBidDepth("EUR/USD"));
            assertEquals(2, book.getOfferDepth("EUR/USD"));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Book state — hasSymbol / getLastUpdated / getMarketName / isStale / isCrossed")
    class BookState {

        @Test
        @DisplayName("hasSymbol returns true after a message, false before")
        void hasSymbolTrueAfterUpdate() {
            assertFalse(book.hasSymbol("EUR/USD"));
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            assertTrue(book.hasSymbol("EUR/USD"));
        }

        @Test
        @DisplayName("getLastUpdated returns the timestamp from the message")
        void getLastUpdatedReturnsTimestamp() {
            Instant before = Instant.now().minusMillis(10);
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            Instant after = Instant.now().plusMillis(10);

            Instant ts = book.getLastUpdated("EUR/USD").orElseThrow();
            assertFalse(ts.isBefore(before), "timestamp should be after test start");
            assertFalse(ts.isAfter(after),   "timestamp should be before test end");
        }

        @Test
        @DisplayName("getMarketName returns the originating market")
        void getMarketNameReturnsCorrectMarket() {
            book.onMessage(msg("EUR/USD", "EBS", 1.08, 1.09, 0L, 0L, 0));
            assertEquals("EBS", book.getMarketName("EUR/USD").orElseThrow());
        }

        @Test
        @DisplayName("isStale returns true for an unknown symbol")
        void isStaleForUnknownSymbol() {
            assertTrue(book.isStale("UNKNOWN", Duration.ofSeconds(60)));
        }

        @Test
        @DisplayName("isStale returns false for a freshly updated symbol")
        void isStaleReturnsFalseForFreshEntry() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            assertFalse(book.isStale("EUR/USD", Duration.ofSeconds(60)));
        }

        @Test
        @DisplayName("isStale returns true when threshold is extremely short")
        void isStaleReturnsTrueForExpiredThreshold() throws InterruptedException {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            Thread.sleep(5);  // ensure at least 1 ms has elapsed
            assertTrue(book.isStale("EUR/USD", Duration.ofNanos(1)));
        }

        @Test
        @DisplayName("isCrossed returns true when bid >= offer")
        void isCrossedReturnsTrueForCrossedBook() {
            book.onMessage(msg("EUR/USD", 1.0855, 1.0850, 0L, 0L, 0));
            assertTrue(book.isCrossed("EUR/USD"));
        }

        @Test
        @DisplayName("isCrossed returns true when bid equals offer (locked market)")
        void isCrossedReturnsTrueForLockedMarket() {
            book.onMessage(msg("EUR/USD", 1.0850, 1.0850, 0L, 0L, 0));
            assertTrue(book.isCrossed("EUR/USD"));
        }

        @Test
        @DisplayName("isCrossed returns false for a normal two-sided book")
        void isCrossedReturnsFalseForNormalBook() {
            book.onMessage(msg("EUR/USD", 1.0850, 1.0852, 0L, 0L, 0));
            assertFalse(book.isCrossed("EUR/USD"));
        }

        @Test
        @DisplayName("isCrossed returns false for unknown symbol")
        void isCrossedReturnsFalseForUnknown() {
            assertFalse(book.isCrossed("UNKNOWN"));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Aggregation — getStaleSymbols / getCrossedSymbols / getActiveSymbolsByMarket / getTopNBySpread")
    class Aggregation {

        @Test
        @DisplayName("getStaleSymbols returns symbols whose entry is older than the threshold")
        void getStaleSymbolsReturnsExpiredEntries() throws InterruptedException {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            Thread.sleep(5);
            book.onMessage(msg("USD/JPY", 149.0, 150.0, 0L, 0L, 0));

            // 1 ns threshold — everything is stale
            List<String> stale = book.getStaleSymbols(Duration.ofNanos(1));
            assertTrue(stale.contains("EUR/USD"));
            assertTrue(stale.contains("USD/JPY"));

            // 60 s threshold — nothing is stale
            List<String> fresh = book.getStaleSymbols(Duration.ofSeconds(60));
            assertTrue(fresh.isEmpty());
        }

        @Test
        @DisplayName("getCrossedSymbols returns only crossed symbols")
        void getCrossedSymbolsFiltersCorrectly() {
            book.onMessage(msg("EUR/USD", 1.0855, 1.0850, 0L, 0L, 0)); // crossed
            book.onMessage(msg("USD/JPY", 149.00, 149.05, 0L, 0L, 0)); // normal

            List<String> crossed = book.getCrossedSymbols();
            assertEquals(1, crossed.size());
            assertTrue(crossed.contains("EUR/USD"));
            assertFalse(crossed.contains("USD/JPY"));
        }

        @Test
        @DisplayName("getActiveSymbolsByMarket filters symbols by originating market")
        void getActiveSymbolsByMarketFiltersCorrectly() {
            book.onMessage(msg("EUR/USD", "EBS",      1.08, 1.09, 0L, 0L, 0));
            book.onMessage(msg("USD/JPY", "REUTERS",  149.0, 149.1, 0L, 0L, 0));
            book.onMessage(msg("GBP/USD", "EBS",      1.27, 1.28, 0L, 0L, 0));

            List<String> ebsSymbols = book.getActiveSymbolsByMarket("EBS");
            assertEquals(2, ebsSymbols.size());
            assertTrue(ebsSymbols.containsAll(List.of("EUR/USD", "GBP/USD")));

            List<String> reutersSymbols = book.getActiveSymbolsByMarket("REUTERS");
            assertEquals(1, reutersSymbols.size());
            assertTrue(reutersSymbols.contains("USD/JPY"));

            assertTrue(book.getActiveSymbolsByMarket("CME").isEmpty());
        }

        @Test
        @DisplayName("getTopNBySpread returns N symbols with tightest spreads in ascending order")
        void getTopNBySpreadReturnsTightestFirst() {
            book.onMessage(msg("EUR/USD", 1.0800, 1.0810, 0L, 0L, 0)); // spread = 0.0010
            book.onMessage(msg("USD/JPY", 149.00, 149.05, 0L, 0L, 0)); // spread = 0.05
            book.onMessage(msg("GBP/USD", 1.2700, 1.2701, 0L, 0L, 0)); // spread = 0.0001

            List<String> top2 = book.getTopNBySpread(2);
            assertEquals(2, top2.size());
            assertEquals("GBP/USD", top2.get(0), "tightest spread should be first");
            assertEquals("EUR/USD", top2.get(1));
            assertFalse(top2.contains("USD/JPY"));
        }

        @Test
        @DisplayName("getTopNBySpread excludes symbols with no bid or offer levels")
        void getTopNBySpreadExcludesOneSidedSymbols() {
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            // symbol with only offer side
            ParsedMessage oneSided = ParsedMessage.builder()
                    .symbol("AUD/USD").marketName("TEST").timestamp(Instant.now())
                    .levels(List.of(PriceLevel.builder()
                            .entryType(MDEntryType.OFFER).price(0.65).size(0L).positionNo(1).build()))
                    .build();
            book.onMessage(oneSided);

            List<String> top = book.getTopNBySpread(5);
            assertEquals(1, top.size());
            assertTrue(top.contains("EUR/USD"));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Callbacks — register / remove / isolation / exception-safety")
    class Callbacks {

        @Test
        @DisplayName("Registered callback fires on symbol update")
        void callbackFiresOnUpdate() {
            List<BookEntry> received = new ArrayList<>();
            book.registerCallback("EUR/USD", received::add);

            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));

            assertEquals(1, received.size());
            assertEquals("EUR/USD", received.get(0).getSymbol());
        }

        @Test
        @DisplayName("Multiple callbacks for the same symbol all fire")
        void multipleCallbacksAllFire() {
            AtomicInteger counter = new AtomicInteger(0);
            book.registerCallback("USD/JPY", e -> counter.incrementAndGet());
            book.registerCallback("USD/JPY", e -> counter.incrementAndGet());
            book.registerCallback("USD/JPY", e -> counter.incrementAndGet());

            book.onMessage(msg("USD/JPY", 149.0, 149.1, 0L, 0L, 0));

            assertEquals(3, counter.get());
        }

        @Test
        @DisplayName("Callback for symbol X does not fire when symbol Y is updated")
        void callbackDoesNotFireForDifferentSymbol() {
            AtomicInteger counter = new AtomicInteger(0);
            book.registerCallback("EUR/USD", e -> counter.incrementAndGet());

            book.onMessage(msg("USD/JPY", 149.0, 149.1, 0L, 0L, 0));

            assertEquals(0, counter.get());
        }

        @Test
        @DisplayName("Throwing callback does not prevent subsequent callbacks from firing")
        void throwingCallbackDoesNotBlockOthers() {
            AtomicInteger counter = new AtomicInteger(0);
            book.registerCallback("EUR/USD", e -> { throw new RuntimeException("boom"); });
            book.registerCallback("EUR/USD", e -> counter.incrementAndGet());

            assertDoesNotThrow(() -> book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0)));
            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("removeCallback stops the callback from firing")
        void removeCallbackStopsFiring() {
            AtomicInteger counter = new AtomicInteger(0);
            Consumer<BookEntry> cb = e -> counter.incrementAndGet();

            book.registerCallback("EUR/USD", cb);
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));
            assertEquals(1, counter.get());

            book.removeCallback("EUR/USD", cb);
            book.onMessage(msg("EUR/USD", 1.09, 1.10, 0L, 0L, 0));
            assertEquals(1, counter.get(), "callback should not fire after removal");
        }

        @Test
        @DisplayName("removeCallback is a no-op when the callback was never registered")
        void removeCallbackNoOpForUnregistered() {
            Consumer<BookEntry> cb = e -> {};
            assertDoesNotThrow(() -> book.removeCallback("EUR/USD", cb));
        }

        @Test
        @DisplayName("removeCallback of one callback leaves other callbacks intact")
        void removeCallbackLeavesOthersIntact() {
            AtomicInteger c1 = new AtomicInteger(0);
            AtomicInteger c2 = new AtomicInteger(0);
            Consumer<BookEntry> cb1 = e -> c1.incrementAndGet();
            Consumer<BookEntry> cb2 = e -> c2.incrementAndGet();

            book.registerCallback("EUR/USD", cb1);
            book.registerCallback("EUR/USD", cb2);

            book.removeCallback("EUR/USD", cb1);
            book.onMessage(msg("EUR/USD", 1.08, 1.09, 0L, 0L, 0));

            assertEquals(0, c1.get(), "cb1 should be silent after removal");
            assertEquals(1, c2.get(), "cb2 should still fire");
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("Thread safety — concurrent updates")
    class ThreadSafety {

        @Test
        @DisplayName("10 threads × 1000 updates do not corrupt the book")
        void concurrentUpdatesRemainsConsistent() throws InterruptedException {
            int threads = 10;
            int updatesPerThread = 1_000;
            CountDownLatch latch = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < updatesPerThread; i++) {
                            double price = threadId + i * 0.0001;
                            book.onMessage(msg("EUR/USD", price, price + 0.0002, 1000L, 1000L, price + 0.0001));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent updates did not complete in time");
            pool.shutdown();

            BookEntry entry = book.getSnapshot("EUR/USD").orElseThrow();
            assertFalse(entry.getBidLevels().isEmpty());
            assertFalse(entry.getOfferLevels().isEmpty());
            double bestBid   = entry.getBidLevels().get(0).getPrice();
            double bestOffer = entry.getOfferLevels().get(0).getPrice();
            assertTrue(bestBid >= 0);
            assertTrue(bestOffer >= bestBid);
        }
    }
}
