package com.qa.quick.fix.cfg;

/** Connection details (host/port) - port comes from separate ports config */
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionDetails {

  @JsonProperty("SocketConnectHost")
  private String socketConnectHost;

  @JsonProperty("SocketConnectPort")
  private String socketConnectPort; // May be null, populated from ports config
}
