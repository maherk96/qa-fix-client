package com.qa.quick.fix.cfg;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PortsConfiguration {

  @JsonProperty("clients")
  private List<ClientPortInfo> clients;
}
