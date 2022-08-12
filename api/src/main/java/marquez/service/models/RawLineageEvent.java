/*
 * Copyright 2018-2022 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service.models;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Valid
@ToString
public class RawLineageEvent extends BaseJsonModel {
  @NotNull private String eventType;
  @NotNull private ZonedDateTime eventTime;
  @NotNull private Map<String, Object> run;
  @NotNull private Map<String, Object> job;
  private List<Object> inputs;
  private List<Object> outputs;
  @NotNull private String producer;
}
