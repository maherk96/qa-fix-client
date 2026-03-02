package com.marketdatahub.config;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Top-level, immutable configuration for the {@code MarketDataHub}.
 * <p>
 * Use the fluent {@link Builder} to register one or more named markets:
 *
 * <pre>{@code
 * MarketDataHubConfig config = MarketDataHubConfig.builder()
 *     .addMarket("EBS", TransportType.SOLACE, SolaceConfig.builder()
 *         .host("tcp://broker:55555")
 *         .vpn("default")
 *         .username("user")
 *         .password("pass")
 *         .topic("EQ/marketData/v1/EBS/>")
 *         .build())
 *     .addMarket("REUTERS", TransportType.TIBRV, TibrvConfig.builder()
 *         .service("7500")
 *         .network(";239.255.0.1")
 *         .daemon("localhost:7500")
 *         .subject("REUTERS.>")
 *         .build())
 *     .build();
 * }</pre>
 */
@Getter
public class MarketDataHubConfig {

    /**
     * Unmodifiable map of market name → transport configuration.
     */
    private final Map<String, MarketConfig> markets;

    private MarketDataHubConfig(Builder builder) {
        this.markets = Collections.unmodifiableMap(new LinkedHashMap<>(builder.markets));
    }

    /**
     * Returns a new {@link Builder} instance.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link MarketDataHubConfig}.
     */
    public static final class Builder {

        private final Map<String, MarketConfig> markets = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers a named market with the supplied transport configuration.
         * <p>
         * The {@code transportType} parameter is validated against the config object's
         * own {@link MarketConfig#getTransportType()} to catch mis-wiring at construction time.
         *
         * @param marketName    unique logical name for this market (e.g. {@code "EBS"})
         * @param transportType the intended transport type
         * @param config        transport-specific configuration implementing {@link MarketConfig}
         * @return this builder for chaining
         * @throws IllegalArgumentException if {@code transportType} does not match {@code config.getTransportType()}
         * @throws IllegalStateException    if a market with the same name has already been registered
         */
        public Builder addMarket(String marketName, TransportType transportType, MarketConfig config) {
            if (marketName == null || marketName.isBlank()) {
                throw new IllegalArgumentException("marketName must not be null or blank");
            }
            if (transportType == null) {
                throw new IllegalArgumentException("transportType must not be null");
            }
            if (config == null) {
                throw new IllegalArgumentException("config must not be null");
            }
            if (config.getTransportType() != transportType) {
                throw new IllegalArgumentException(String.format(
                        "TransportType mismatch for market '%s': declared %s but config reports %s",
                        marketName, transportType, config.getTransportType()));
            }
            if (markets.containsKey(marketName)) {
                throw new IllegalStateException("Market '" + marketName + "' has already been registered");
            }
            markets.put(marketName, config);
            return this;
        }

        /**
         * Builds and returns an immutable {@link MarketDataHubConfig}.
         *
         * @return a new config instance
         * @throws IllegalStateException if no markets have been registered
         */
        public MarketDataHubConfig build() {
            if (markets.isEmpty()) {
                throw new IllegalStateException("At least one market must be configured");
            }
            return new MarketDataHubConfig(this);
        }
    }
}
