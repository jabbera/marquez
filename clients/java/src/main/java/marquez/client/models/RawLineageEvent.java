/*
 * Copyright 2018-2022 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.client.models;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import marquez.client.Utils;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@ToString
public class RawLineageEvent{
  private String eventType;
  private ZonedDateTime eventTime;
  private Map<String, Object> run;
  private Map<String, Object> job;
  private List<Object> inputs;
  private List<Object> outputs;
  private String producer;

  public static RawLineageEvent fromJson(@NonNull final String json) {
    return Utils.fromJson(json, new TypeReference<RawLineageEvent>() {});
  }

}
