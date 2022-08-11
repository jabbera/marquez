/*
 * Copyright 2018-2022 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.service.models;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

@Value
public class DatasetSymlink {
  @NonNull UUID uuid;
  @NonNull String name;
  @NonNull UUID namespaceUuid;
  @Nullable String type;
  @Getter @NonNull private final Instant createdAt;
  @NonNull private final Instant updatedAt;

  public Optional<String> getType() {
    return Optional.ofNullable(type);
  }
}
