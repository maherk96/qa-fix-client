package com.marketdatahub.config;

/**
 * Marker interface for transport-specific market configuration.
 * <p>
 * Each concrete implementation carries the parameters required to establish
 * a connection for one particular {@link TransportType}.
 */
public interface MarketConfig {

    /**
     * Returns the transport type associated with this configuration.
     *
     * @return the {@link TransportType} for this market
     */
    TransportType getTransportType();
}
