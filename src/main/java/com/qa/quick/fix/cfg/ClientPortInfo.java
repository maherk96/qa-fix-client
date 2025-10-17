package com.qa.quick.fix.cfg;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientPortInfo {

  @JsonProperty("name")
  private String name;

  @JsonProperty("port")
  private String port;

  @JsonProperty("location")
  private String location;
}
