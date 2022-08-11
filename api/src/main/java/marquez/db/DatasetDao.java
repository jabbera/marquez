/*
 * Copyright 2018-2022 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.db;

import static marquez.db.OpenLineageDao.DEFAULT_NAMESPACE_OWNER;
import static org.jdbi.v3.sqlobject.customizer.BindList.EmptyHandling.NULL_STRING;

import com.google.common.collect.ImmutableList;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Value;
import marquez.common.models.DatasetName;
import marquez.common.models.DatasetType;
import marquez.common.models.NamespaceName;
import marquez.common.models.TagName;
import marquez.db.mappers.DatasetMapper;
import marquez.db.mappers.DatasetRowMapper;
import marquez.db.models.DatasetRow;
import marquez.db.models.DatasetSymlinkRow;
import marquez.db.models.DatasetVersionRow;
import marquez.db.models.NamespaceRow;
import marquez.db.models.SourceRow;
import marquez.db.models.TagRow;
import marquez.service.DatasetService;
import marquez.service.models.Dataset;
import marquez.service.models.DatasetMeta;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

@RegisterRowMapper(DatasetRowMapper.class)
@RegisterRowMapper(DatasetMapper.class)
public interface DatasetDao extends BaseDao {
  @SqlQuery(
      "WITH symlink AS (\n"
          + "    SELECT symlink_uuid\n"
          + "    FROM dataset_symlinks\n"
          + "    JOIN namespaces ON namespaces.uuid = dataset_symlinks.namespace_uuid\n"
          + "    WHERE namespaces.name=:namespaceName and dataset_symlinks.name=:datasetName\n"
          + ") SELECT EXISTS ("
          + "SELECT 1 FROM datasets AS d "
          + "WHERE d.symlink_uuid IN (SELECT symlink_uuid FROM symlink))")
  boolean exists(String namespaceName, String datasetName);

  @SqlBatch(
      "INSERT INTO datasets_tag_mapping (dataset_uuid, tag_uuid, tagged_at) "
          + "VALUES (:rowUuid, :tagUuid, :taggedAt) "
          + "ON CONFLICT DO NOTHING")
  void updateTagMapping(@BindBean List<DatasetTagMapping> datasetTagMappings);

  @SqlUpdate(
      "UPDATE datasets "
          + "SET updated_at = :lastModifiedAt, "
          + "    last_modified_at = :lastModifiedAt "
          + "WHERE uuid IN (<rowUuids>)")
  void updateLastModifiedAt(
      @BindList(onEmpty = NULL_STRING) List<UUID> rowUuids, Instant lastModifiedAt);

  @SqlUpdate(
      "UPDATE datasets "
          + "SET updated_at = :updatedAt, "
          + "    current_version_uuid = :currentVersionUuid "
          + "WHERE uuid = :rowUuid")
  void updateVersion(UUID rowUuid, Instant updatedAt, UUID currentVersionUuid);

  @SqlQuery(
      "WITH symlinks AS (\n"
          + "    SELECT ds_joined.symlink_uuid AS uuid, ds_joined.name AS name, namespaces.name AS namespace\n"
          + "    FROM dataset_symlinks ds\n"
          + "    JOIN dataset_symlinks ds_joined ON ds.symlink_uuid = ds_joined.symlink_uuid\n"
          + "    JOIN namespaces ON namespaces.uuid = ds_joined.namespace_uuid\n"
          + "    WHERE namespaces.name=:namespaceName and ds.name=:datasetName\n"
          + "), selected_datasets AS (\n"
          + "    SELECT d.*\n"
          + "    FROM datasets d\n"
          + "    INNER JOIN symlinks ON d.symlink_uuid = symlinks.uuid\n"
          + "), dataset_runs AS (\n"
          + "    SELECT d.uuid, dsl.name, d.namespace_name, dv.run_uuid, dv.lifecycle_state, event_time, event\n"
          + "    FROM selected_datasets d\n"
          + "    INNER JOIN dataset_symlinks dsl ON d.symlink_uuid = dsl.symlink_uuid AND dsl.is_primary=true\n"
          + "    INNER JOIN dataset_versions dv ON dv.uuid = d.current_version_uuid\n"
          + "    LEFT JOIN LATERAL (\n"
          + "        SELECT run_uuid, event_time, event FROM lineage_events\n"
          + "        WHERE run_uuid = dv.run_uuid\n"
          + "    ) e ON e.run_uuid = dv.run_uuid\n"
          + "    UNION\n"
          + "    SELECT d.uuid, dsl.name, d.namespace_name, rim.run_uuid, lifecycle_state, event_time, event\n"
          + "    FROM selected_datasets d\n"
          + "    INNER JOIN dataset_symlinks dsl ON d.symlink_uuid = dsl.symlink_uuid AND dsl.is_primary=true\n"
          + "    INNER JOIN dataset_versions dv ON dv.uuid = d.current_version_uuid\n"
          + "    LEFT JOIN runs_input_mapping rim ON dv.uuid = rim.dataset_version_uuid\n"
          + "    LEFT JOIN LATERAL (\n"
          + "        SELECT run_uuid, event_time, event FROM lineage_events\n"
          + "        WHERE run_uuid = rim.run_uuid\n"
          + "    ) e ON e.run_uuid = rim.run_uuid\n"
          + ")\n"
          + "SELECT d.*, :datasetName as name, dv.fields, dv.lifecycle_state, sv.schema_location, t.tags, facets\n"
          + "FROM selected_datasets d\n"
          + "LEFT JOIN dataset_versions dv ON d.current_version_uuid = dv.uuid\n"
          + "LEFT JOIN stream_versions AS sv ON sv.dataset_version_uuid = dv.uuid\n"
          + "LEFT JOIN (\n"
          + "    SELECT ARRAY_AGG(t.name) AS tags, m.dataset_uuid\n"
          + "    FROM tags AS t\n"
          + "             INNER JOIN datasets_tag_mapping AS m ON m.tag_uuid = t.uuid\n"
          + "    GROUP BY m.dataset_uuid\n"
          + ") t ON t.dataset_uuid = d.uuid\n"
          + "LEFT JOIN (\n"
          + "    SELECT d2.uuid AS dataset_uuid, JSONB_AGG(ds->'facets' ORDER BY event_time ASC) AS facets\n"
          + "    FROM dataset_runs d2,\n"
          + "         jsonb_array_elements(coalesce(d2.event -> 'inputs', '[]'::jsonb) || coalesce(d2.event -> 'outputs', '[]'::jsonb)) AS ds\n"
          + "    WHERE d2.run_uuid = d2.run_uuid\n"
          + "      AND ds -> 'facets' IS NOT NULL\n"
          + "    AND ds ->> 'name' = d2.name\n"
          + "    AND ds ->> 'namespace' = d2.namespace_name\n" // joining on name/namespace is OK,
          // as the same run rows are joined
          + "    GROUP BY d2.uuid\n"
          + ") f ON f.dataset_uuid = d.uuid")
  Optional<Dataset> findDatasetByName(String namespaceName, String datasetName);

  default Optional<Dataset> findWithTags(String namespaceName, String datasetName) {
    Optional<Dataset> dataset = findDatasetByName(namespaceName, datasetName);
    dataset.ifPresent(this::setFields);
    return dataset;
  }

  default void setFields(Dataset ds) {
    DatasetFieldDao datasetFieldDao = createDatasetFieldDao();

    ds.getCurrentVersion()
        .ifPresent(
            dsv -> {
              ds.setFields(datasetFieldDao.find(dsv));
            });
  }

  @SqlQuery(
      """
      SELECT d.*
      FROM datasets AS d
      JOIN dataset_symlinks dsl ON d.symlink_uuid = dsl.symlink_uuid
      WHERE dsl.name = :datasetName AND d.namespace_name = :namespaceName
    """)
  Optional<DatasetRow> findDatasetAsRow(String namespaceName, String datasetName);

  @SqlQuery(
      "WITH selected_datasets AS (\n"
          + "    SELECT d.*, dsl.name\n"
          + "    FROM datasets d\n"
          + "    INNER JOIN dataset_symlinks dsl ON d.symlink_uuid = dsl.symlink_uuid AND dsl.is_primary=true\n"
          + "    WHERE d.namespace_name = :namespaceName\n"
          + "    ORDER BY dsl.name\n"
          + "    LIMIT :limit OFFSET :offset\n"
          + "), dataset_runs AS (\n"
          + "    SELECT d.uuid, dsl.name, d.namespace_name, dv.run_uuid, dv.lifecycle_state, event_time, event\n"
          + "    FROM selected_datasets d\n"
          + "    INNER JOIN dataset_symlinks dsl ON d.symlink_uuid = dsl.symlink_uuid AND dsl.is_primary=true\n"
          + "    INNER JOIN dataset_versions dv ON dv.uuid = d.current_version_uuid\n"
          + "    LEFT JOIN LATERAL (\n"
          + "        SELECT run_uuid, event_time, event FROM lineage_events\n"
          + "        WHERE run_uuid = dv.run_uuid\n"
          + "    ) e ON e.run_uuid = dv.run_uuid\n"
          + "    UNION\n"
          + "    SELECT d.uuid, dsl.name, d.namespace_name, rim.run_uuid, lifecycle_state, event_time, event\n"
          + "    FROM selected_datasets d\n"
          + "    INNER JOIN dataset_symlinks dsl ON d.symlink_uuid = dsl.symlink_uuid AND dsl.is_primary=true\n"
          + "    INNER JOIN dataset_versions dv ON dv.uuid = d.current_version_uuid\n"
          + "    LEFT JOIN runs_input_mapping rim ON dv.uuid = rim.dataset_version_uuid\n"
          + "    LEFT JOIN LATERAL (\n"
          + "        SELECT run_uuid, event_time, event FROM lineage_events\n"
          + "        WHERE run_uuid = rim.run_uuid\n"
          + "    ) e ON e.run_uuid = rim.run_uuid\n"
          + ")\n"
          + "SELECT d.*, dv.fields, dv.lifecycle_state, sv.schema_location, t.tags, facets\n"
          + "FROM selected_datasets d\n"
          + "LEFT JOIN dataset_versions dv ON d.current_version_uuid = dv.uuid\n"
          + "LEFT JOIN stream_versions AS sv ON sv.dataset_version_uuid = dv.uuid\n"
          + "LEFT JOIN (\n"
          + "    SELECT ARRAY_AGG(t.name) AS tags, m.dataset_uuid\n"
          + "    FROM tags AS t\n"
          + "    INNER JOIN datasets_tag_mapping AS m ON m.tag_uuid = t.uuid\n"
          + "    GROUP BY m.dataset_uuid\n"
          + ") t ON t.dataset_uuid = d.uuid\n"
          + "LEFT JOIN (\n"
          + "    SELECT d2.uuid AS dataset_uuid, JSONB_AGG(ds->'facets' ORDER BY event_time) AS facets\n"
          + "    FROM dataset_runs d2,\n"
          + "         jsonb_array_elements(coalesce(d2.event -> 'inputs', '[]'::jsonb) || coalesce(d2.event -> 'outputs', '[]'::jsonb)) AS ds\n"
          + "    WHERE d2.run_uuid = d2.run_uuid\n"
          + "    AND ds -> 'facets' IS NOT NULL\n"
          + "    AND ds ->> 'name' = d2.name\n"
          + "    AND ds ->> 'namespace' = d2.namespace_name\n" // joining on name/namespace is OK,
          // as the same run rows are joined
          + "    GROUP BY d2.uuid\n"
          + ") f ON f.dataset_uuid = d.uuid\n"
          + "ORDER BY d.name")
  List<Dataset> findAll(String namespaceName, int limit, int offset);

  @SqlQuery("SELECT count(*) FROM datasets")
  int count();

  @SqlQuery("SELECT count(*) FROM datasets AS j WHERE j.namespace_name = :namespaceName")
  int countFor(String namespaceName);

  default List<Dataset> findAllWithTags(String namespaceName, int limit, int offset) {
    List<Dataset> datasets = findAll(namespaceName, limit, offset);
    return datasets.stream().peek(this::setFields).collect(Collectors.toList());
  }

  @SqlQuery(
      "INSERT INTO datasets ("
          + "uuid, "
          + "type, "
          + "created_at, "
          + "updated_at, "
          + "namespace_uuid, "
          + "namespace_name, "
          + "source_uuid, "
          + "source_name, "
          + "symlink_uuid, "
          + "physical_name, "
          + "description, "
          + "is_deleted "
          + ") VALUES ( "
          + ":uuid, "
          + ":type, "
          + ":now, "
          + ":now, "
          + ":namespaceUuid, "
          + ":namespaceName, "
          + ":sourceUuid, "
          + ":sourceName, "
          + ":symlinkUuid, "
          + ":physicalName, "
          + ":description, "
          + ":isDeleted) "
          + "ON CONFLICT (namespace_uuid, symlink_uuid) "
          + "DO UPDATE SET "
          + "type = EXCLUDED.type, "
          + "updated_at = EXCLUDED.updated_at, "
          + "physical_name = EXCLUDED.physical_name, "
          + "description = EXCLUDED.description, "
          + "is_deleted = EXCLUDED.is_deleted "
          + "RETURNING *")
  DatasetRow upsert(
      UUID uuid,
      DatasetType type,
      Instant now,
      UUID namespaceUuid,
      String namespaceName,
      UUID symlinkUuid,
      UUID sourceUuid,
      String sourceName,
      String physicalName,
      String description,
      boolean isDeleted);

  @SqlQuery(
      "INSERT INTO datasets ("
          + "uuid, "
          + "type, "
          + "created_at, "
          + "updated_at, "
          + "namespace_uuid, "
          + "namespace_name, "
          + "source_uuid, "
          + "source_name, "
          + "symlink_uuid, "
          + "physical_name "
          + ") VALUES ( "
          + ":uuid, "
          + ":type, "
          + ":now, "
          + ":now, "
          + ":namespaceUuid, "
          + ":namespaceName, "
          + ":sourceUuid, "
          + ":sourceName, "
          + ":symlinkUuid, "
          + ":physicalName) "
          + "ON CONFLICT (namespace_uuid, symlink_uuid) "
          + "DO UPDATE SET "
          + "type = EXCLUDED.type, "
          + "updated_at = EXCLUDED.updated_at, "
          + "physical_name = EXCLUDED.physical_name "
          + "RETURNING *")
  DatasetRow upsert(
      UUID uuid,
      DatasetType type,
      Instant now,
      UUID namespaceUuid,
      String namespaceName,
      UUID symlinkUuid,
      UUID sourceUuid,
      String sourceName,
      String physicalName);

  @Transaction
  default Dataset upsertDatasetMeta(
      NamespaceName namespaceName, DatasetName datasetName, DatasetMeta datasetMeta) {
    Instant now = Instant.now();
    NamespaceRow namespaceRow =
        createNamespaceDao()
            .upsertNamespaceRow(
                UUID.randomUUID(), now, namespaceName.getValue(), DEFAULT_NAMESPACE_OWNER);
    DatasetSymlinkRow symlinkRow =
        createDatasetSymlinkDao()
            .upsertDatasetSymlinkRow(
                UUID.randomUUID(), datasetName.getValue(), namespaceRow.getUuid(), true, now);
    SourceRow sourceRow =
        createSourceDao()
            .upsertOrDefault(
                UUID.randomUUID(),
                toDefaultSourceType(datasetMeta.getType()),
                now,
                datasetMeta.getSourceName().getValue(),
                "");
    UUID newDatasetUuid = UUID.randomUUID();
    DatasetRow datasetRow;

    if (datasetMeta.getDescription().isPresent()) {
      datasetRow =
          upsert(
              newDatasetUuid,
              datasetMeta.getType(),
              now,
              namespaceRow.getUuid(),
              namespaceRow.getName(),
              symlinkRow.getUuid(),
              sourceRow.getUuid(),
              sourceRow.getName(),
              datasetMeta.getPhysicalName().getValue(),
              datasetMeta.getDescription().orElse(null),
              false);
    } else {
      datasetRow =
          upsert(
              newDatasetUuid,
              datasetMeta.getType(),
              now,
              namespaceRow.getUuid(),
              namespaceRow.getName(),
              symlinkRow.getUuid(),
              sourceRow.getUuid(),
              sourceRow.getName(),
              datasetMeta.getPhysicalName().getValue());
    }

    updateDatasetMetric(namespaceName, datasetMeta.getType(), newDatasetUuid, datasetRow.getUuid());

    TagDao tagDao = createTagDao();
    List<DatasetTagMapping> datasetTagMappings = new ArrayList<>();
    for (TagName tagName : datasetMeta.getTags()) {
      TagRow tag = tagDao.upsert(UUID.randomUUID(), now, tagName.getValue());
      datasetTagMappings.add(new DatasetTagMapping(datasetRow.getUuid(), tag.getUuid(), now));
    }
    updateTagMapping(datasetTagMappings);

    DatasetVersionRow dvRow =
        createDatasetVersionDao()
            .upsertDatasetVersion(
                datasetRow.getUuid(),
                now,
                namespaceName.getValue(),
                datasetName.getValue(),
                null,
                datasetMeta);

    return findWithTags(namespaceName.getValue(), datasetName.getValue()).get();
  }

  default String toDefaultSourceType(DatasetType type) {
    return "POSTGRES";
  }

  default void updateDatasetMetric(
      NamespaceName namespaceName,
      DatasetType datasetType,
      UUID newDatasetUuid,
      UUID currentDatasetUuid) {
    if (newDatasetUuid != currentDatasetUuid) {
      DatasetService.datasets.labels(namespaceName.getValue(), datasetType.toString()).inc();
    }
  }

  default Dataset updateTags(String namespaceName, String datasetName, String tagName) {
    Instant now = Instant.now();
    DatasetRow datasetRow = findDatasetAsRow(namespaceName, datasetName).get();
    TagRow tagRow = createTagDao().upsert(UUID.randomUUID(), now, tagName);
    updateTagMapping(
        ImmutableList.of(new DatasetTagMapping(datasetRow.getUuid(), tagRow.getUuid(), now)));
    return findDatasetByName(namespaceName, datasetName).get();
  }

  @Value
  class DatasetTagMapping {
    UUID rowUuid;
    UUID tagUuid;
    Instant taggedAt;
  }
}
