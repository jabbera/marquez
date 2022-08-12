package marquez.db.mappers;

import static marquez.db.Columns.stringOrThrow;
import static marquez.db.Columns.zonedDateTimeOrThrow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.db.Columns;
import marquez.service.models.RawLineageEvent;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

@Slf4j
public class RawLineageEventMapper implements RowMapper<RawLineageEvent> {
  @Override
  public RawLineageEvent map(ResultSet rs, StatementContext ctx) throws SQLException {
    String rawEvent = stringOrThrow(rs, Columns.EVENT);
    Map<String, Object> run = Collections.emptyMap();
    Map<String, Object> job = Collections.emptyMap();

    List<Object> inputs = Collections.emptyList();
    List<Object> outputs = Collections.emptyList();
    try {
      ObjectMapper mapper = Utils.getMapper();
      Map<String, Object> event = mapper.readValue(rawEvent, Map.class);
      run = (Map<String, Object>) event.getOrDefault("run", Collections.emptyMap());
      job = (Map<String, Object>) event.getOrDefault("job", Collections.emptyMap());
      inputs = (List<Object>) event.getOrDefault("inputs", Collections.emptyList());
      outputs = (List<Object>) event.getOrDefault("outputs", Collections.emptyList());
    } catch (JsonProcessingException e) {
      log.error("Failed to process json", e);
    }

    return RawLineageEvent.builder()
        .eventTime(zonedDateTimeOrThrow(rs, Columns.EVENT_TIME))
        .eventType(stringOrThrow(rs, Columns.EVENT_TYPE))
        .run(run)
        .job(job)
        .inputs(inputs)
        .outputs(outputs)
        .producer(stringOrThrow(rs, Columns.PRODUCER))
        .build();
  }
}
