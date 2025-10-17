package com.qa.quick.fix.cfg;

/** Connection environment definition (EMEA, ALGO_UAT, etc.) */
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConnectionEnvironment {

  @JsonProperty("trade")
  private ConnectionDetails trade;

  @JsonProperty("quote")
  private ConnectionDetails quote; // Optional for trade-only clients
}
