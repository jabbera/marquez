package marquez.db;

import java.util.List;
import marquez.db.mappers.RawLineageEventMapper;
import marquez.service.models.RawLineageEvent;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

@RegisterRowMapper(RawLineageEventMapper.class)
public interface EventDao extends BaseDao {

  @SqlQuery(
      """
    SELECT *
    FROM lineage_events
    ORDER BY event_time DESC
    LIMIT :limit
    OFFSET :offset""")
  List<RawLineageEvent> getAll(int limit, int offset);

  /**
   * This is a "hack" to get inputs/outputs namespace from jsonb column: <a
   * href="https://github.com/jdbi/jdbi/issues/1510#issuecomment-485423083">explanation</a>
   */
  @SqlQuery(
      """
    SELECT le.*
    FROM lineage_events le, jsonb_array_elements(coalesce(le.event -> 'inputs', '[]'::jsonb) || coalesce(le.event -> 'outputs', '[]'::jsonb)) AS ds
    WHERE le.job_namespace = :namespace
    OR ds ->> 'namespace' =  :namespace
    LIMIT :limit
    OFFSET :offset""")
  List<RawLineageEvent> getByNamespace(
      @Bind("namespace") String namespace, @Bind("limit") int limit, @Bind("offset") int offset);
}
