package com.qa.quick.fix.cfg;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionConfig {

  @JsonProperty("SenderCompID")
  private String senderCompID;

  @JsonProperty("TargetCompID")
  private String targetCompID;
}
