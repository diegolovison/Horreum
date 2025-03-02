package io.hyperfoil.tools.horreum.svc;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;

import org.hibernate.Hibernate;
import org.hibernate.query.NativeQuery;
import org.hibernate.transform.AliasToBeanResultTransformer;
import org.hibernate.type.IntegerType;
import org.hibernate.type.LongType;
import org.hibernate.type.TextType;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.vladmihalcea.hibernate.type.json.JsonNodeBinaryType;

import io.hyperfoil.tools.horreum.api.DatasetService;
import io.hyperfoil.tools.horreum.api.QueryResult;
import io.hyperfoil.tools.horreum.api.SchemaService;
import io.hyperfoil.tools.horreum.entity.PersistentLog;
import io.hyperfoil.tools.horreum.entity.alerting.DatasetLog;
import io.hyperfoil.tools.horreum.entity.json.DataSet;
import io.hyperfoil.tools.horreum.entity.json.Label;
import io.hyperfoil.tools.horreum.entity.json.Test;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.hyperfoil.tools.horreum.server.WithToken;
import io.quarkus.runtime.Startup;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.eventbus.EventBus;

@ApplicationScoped
@Startup
public class DatasetServiceImpl implements DatasetService {
   private static final Logger log = Logger.getLogger(DatasetServiceImpl.class);

   //@formatter:off
   private static final String LABEL_QUERY =
         "WITH used_labels AS (" +
            "SELECT label.id AS label_id, label.name, ds.schema_id, count(le) AS count FROM dataset_schemas ds " +
            "JOIN label ON label.schema_id = ds.schema_id " +
            "LEFT JOIN label_extractors le ON le.label_id = label.id " +
            "WHERE ds.dataset_id = ?1 AND (?2 < 0 OR label.id = ?2) GROUP BY label.id, label.name, ds.schema_id" +
         "), lvalues AS (" +
            "SELECT ul.label_id, le.name, (CASE WHEN le.isarray THEN " +
                  "jsonb_path_query_array(dataset.data -> ds.index, le.jsonpath::::jsonpath) " +
               "ELSE " +
                  "jsonb_path_query_first(dataset.data -> ds.index, le.jsonpath::::jsonpath) " +
               "END) AS value " +
            "FROM dataset JOIN dataset_schemas ds ON dataset.id = ds.dataset_id " +
            "JOIN used_labels ul ON ul.schema_id = ds.schema_id " +
            "LEFT JOIN label_extractors le ON ul.label_id = le.label_id " +
            "WHERE dataset.id = ?1" +
         ") SELECT lvalues.label_id, ul.name, function, (CASE " +
               "WHEN ul.count > 1 THEN jsonb_object_agg(COALESCE(lvalues.name, ''), lvalues.value) " +
               "WHEN ul.count = 1 THEN jsonb_agg(lvalues.value) -> 0 " +
               "ELSE '{}'::::jsonb END" +
            ") AS value FROM label " +
            "JOIN lvalues ON lvalues.label_id = label.id " +
            "JOIN used_labels ul ON label.id = ul.label_id " +
            "GROUP BY lvalues.label_id, ul.name, function, ul.count";
   protected static final String LABEL_PREVIEW = "WITH le AS (" +
            "SELECT * FROM jsonb_populate_recordset(NULL::::extractor, (?1)::::jsonb)" +
         "), lvalues AS (" +
            "SELECT le.name, (CASE WHEN le.isarray THEN " +
               "jsonb_path_query_array(dataset.data -> ds.index, le.jsonpath) " +
            "ELSE " +
               "jsonb_path_query_first(dataset.data -> ds.index, le.jsonpath) " +
            "END) AS value " +
            "FROM le, dataset JOIN dataset_schemas ds ON dataset.id = ds.dataset_id " +
            "WHERE dataset.id = ?2 AND ds.schema_id = ?3" +
         ") SELECT (CASE " +
            "WHEN jsonb_array_length((?1)::::jsonb) > 1 THEN jsonb_object_agg(COALESCE(lvalues.name, ''), lvalues.value) " +
            "WHEN jsonb_array_length((?1)::::jsonb) = 1 THEN jsonb_agg(lvalues.value) -> 0 " +
            "ELSE '{}'::::jsonb END" +
         ") AS value FROM lvalues";

   private static final String SCHEMAS_SELECT = "SELECT dataset_id, jsonb_object_agg(schema_id, uri) AS schemas FROM dataset_schemas ds JOIN dataset ON dataset.id = ds.dataset_id";
   private static final String VALIDATION_SELECT = "validation AS (" +
            "SELECT dataset_id, jsonb_agg(jsonb_build_object('schemaId', schema_id, 'error', error)) AS errors FROM dataset_validationerrors GROUP BY dataset_id" +
         ")";
   private static final String DATASET_SUMMARY_SELECT = " SELECT ds.id, ds.runid AS runId, ds.ordinal, " +
         "ds.testid AS testId, test.name AS testname, ds.description, " +
         "EXTRACT(EPOCH FROM ds.start) * 1000 AS start, EXTRACT(EPOCH FROM ds.stop) * 1000 AS stop, " +
         "ds.owner, ds.access, dv.value AS view, schema_agg.schemas AS schemas, " +
         "COALESCE(validation.errors, '[]') AS validationErrors " +
         "FROM dataset ds LEFT JOIN test ON test.id = ds.testid " +
         "LEFT JOIN schema_agg ON schema_agg.dataset_id = ds.id " +
         "LEFT JOIN validation ON validation.dataset_id = ds.id " +
         "LEFT JOIN dataset_view dv ON dv.dataset_id = ds.id";
   private static final String LIST_SCHEMA_DATASETS =
         "WITH ids AS (" +
            "SELECT dataset_id AS id FROM dataset_schemas WHERE uri = ?1" +
         "), schema_agg AS (" +
            SCHEMAS_SELECT + " WHERE dataset_id IN (SELECT id FROM ids) GROUP BY dataset_id" +
         ") SELECT ds.id, ds.runid AS runId, ds.ordinal, " +
         "ds.testid AS testId, test.name AS testname, ds.description, " +
         "EXTRACT(EPOCH FROM ds.start) * 1000 AS start, EXTRACT(EPOCH FROM ds.stop) * 1000 AS stop, " +
         "ds.owner, ds.access, dv.value AS view, schema_agg.schemas AS schemas " +
         "FROM dataset ds LEFT JOIN test ON test.id = ds.testid " +
         "LEFT JOIN schema_agg ON schema_agg.dataset_id = ds.id " +
         "LEFT JOIN dataset_view dv ON dv.dataset_id = ds.id AND dv.view_id = defaultview_id WHERE ds.id IN (SELECT id FROM ids)";
   private static final String ALL_LABELS_SELECT = "SELECT dataset.id as dataset_id, " +
         "COALESCE(jsonb_object_agg(label.name, lv.value) FILTER (WHERE label.name IS NOT NULL), '{}'::::jsonb) AS values FROM dataset " +
         "LEFT JOIN label_values lv ON dataset.id = lv.dataset_id " +
         "LEFT JOIN label ON label.id = label_id ";

   //@formatter:on
   protected static final AliasToBeanResultTransformer DATASET_SUMMARY_TRANSFORMER = new AliasToBeanResultTransformer(DatasetSummary.class);
   protected static final AliasToBeanResultTransformer DATASET_BY_SCHEMA_TRANSFORMER = new AliasToBeanResultTransformer(DatasetSummary.class);
   @Inject
   EntityManager em;

   @Inject
   SqlServiceImpl sqlService;

   @Inject
   TransactionManager tm;

   @Inject
   EventBus eventBus;

   @Inject
   SecurityIdentity identity;

   // This is a nasty hack that will serialize all run -> dataset transformations and label calculations
   // The problem is that PostgreSQL's SSI will for some (unknown) reason rollback some transactions,
   // probably due to false sharing of locks. For some reason even using advisory locks in DB does not
   // solve the issue so we have to serialize this even outside the problematic transactions.
   private final ReentrantLock recalculationLock = new ReentrantLock();

   @PostConstruct
   void init() {
      sqlService.registerListener("calculate_labels", this::onLabelChanged);
   }

   @PermitAll
   @WithRoles
   @Override
   public DatasetService.DatasetList listByTest(int testId, String filter, Integer limit, Integer page, String sort, String direction, Integer viewId) {
      StringBuilder sql = new StringBuilder("WITH schema_agg AS (")
            .append(SCHEMAS_SELECT).append(" WHERE testid = ?1 GROUP BY dataset_id")
            .append("), ").append(VALIDATION_SELECT);
      JsonNode jsonFilter = null;
      if (filter != null && !filter.isBlank()) {
         sql.append(", all_labels AS (").append(ALL_LABELS_SELECT).append(" WHERE testid = ?1 GROUP BY dataset.id)");
         sql.append(DATASET_SUMMARY_SELECT);
         addViewIdCondition(sql, viewId);
         sql.append(" JOIN all_labels ON all_labels.dataset_id = ds.id WHERE testid = ?1 AND all_labels.values @> ?2");
         jsonFilter = Util.parseFingerprint(filter);
      } else {
         sql.append(DATASET_SUMMARY_SELECT);
         addViewIdCondition(sql, viewId);
         sql.append(" WHERE testid = ?1 AND ?2 IS NULL");
      }
      addOrderAndPaging(limit, page, sort, direction, sql);
      Query query = em.createNativeQuery(sql.toString())
            .setParameter(1, testId);
      if (jsonFilter != null) {
         query.unwrap(NativeQuery.class).setParameter(2, jsonFilter, JsonNodeBinaryType.INSTANCE);
      } else {
         query.setParameter(2, null);
      }
      if (viewId != null) {
         query.setParameter(3, viewId);
      }
      markAsSummaryList(query);
      DatasetService.DatasetList list = new DatasetService.DatasetList();
      //noinspection unchecked
      list.datasets = query.getResultList();
      list.total = DataSet.count("testid = ?1", testId);
      return list;
   }

   private void addViewIdCondition(StringBuilder sql, Integer viewId) {
      if (viewId == null) {
         sql.append(" AND dv.view_id = defaultview_id");
      } else {
         sql.append(" AND dv.view_id = ?3");
      }
   }

   private void markAsSummaryList(Query query) {
      //noinspection deprecation
      query.unwrap(NativeQuery.class)
            .addScalar("id", IntegerType.INSTANCE)
            .addScalar("runId", IntegerType.INSTANCE)
            .addScalar("ordinal", IntegerType.INSTANCE)
            .addScalar("testId", IntegerType.INSTANCE)
            .addScalar("testname", TextType.INSTANCE)
            .addScalar("description", TextType.INSTANCE)
            .addScalar("start", LongType.INSTANCE)
            .addScalar("stop", LongType.INSTANCE)
            .addScalar("owner", TextType.INSTANCE)
            .addScalar("access", IntegerType.INSTANCE)
            .addScalar("view", JsonNodeBinaryType.INSTANCE)
            .addScalar("schemas", JsonNodeBinaryType.INSTANCE)
            .addScalar("validationErrors", JsonNodeBinaryType.INSTANCE)
            .setResultTransformer(DATASET_SUMMARY_TRANSFORMER);
   }

   private void addOrderAndPaging(Integer limit, Integer page, String sort, String direction, StringBuilder sql) {
      if (sort != null && sort.startsWith("view_data:")) {
         String[] parts = sort.split(":", 3);
         String vcid = parts[1];
         String label = parts[2];
         sql.append(" ORDER BY");
         // prefer numeric sort
         sql.append(" to_double(dv.value->'").append(vcid).append("'->>'").append(label).append("')");
         Util.addDirection(sql, direction);
         sql.append(", dv.value->'").append(vcid).append("'->>'").append(label).append("'");
         Util.addDirection(sql, direction);
      } else {
         Util.addOrderBy(sql, sort, direction);
      }
      Util.addLimitOffset(sql, limit, page);
   }

   @WithRoles
   @Override
   public QueryResult queryData(int datasetId, String jsonpath, boolean array, String schemaUri) {
      if (schemaUri != null && schemaUri.isBlank()) {
         schemaUri = null;
      }
      QueryResult result = new QueryResult();
      result.jsonpath = jsonpath;
      try {
         if (schemaUri == null) {
            String func = array ? "jsonb_path_query_array" : "jsonb_path_query_first";
            String sqlQuery = "SELECT " + func + "(data, ?::::jsonpath)#>>'{}' FROM dataset WHERE id = ?";
            result.value = String.valueOf(Util.runQuery(em, sqlQuery, jsonpath, datasetId));
         } else {
            // This schema-aware query already assumes that DataSet.data is an array of objects with defined schema
            String schemaQuery = "jsonb_path_query(data, '$[*] ? (@.\"$schema\" == $schema)', ('{\"schema\":\"' || ? || '\"}')::::jsonb)";
            String sqlQuery;
            if (!array) {
               sqlQuery = "SELECT jsonb_path_query_first(" + schemaQuery + ", ?::::jsonpath)#>>'{}' FROM dataset WHERE id = ? LIMIT 1";
            } else {
               sqlQuery = "SELECT jsonb_agg(v)#>>'{}' FROM (SELECT jsonb_path_query(" + schemaQuery + ", ?::::jsonpath) AS v FROM dataset WHERE id = ?) AS values";
            }
            result.value = String.valueOf(Util.runQuery(em, sqlQuery, schemaUri, jsonpath, datasetId));
         }
         result.valid = true;
      } catch (PersistenceException pe) {
         SqlServiceImpl.setFromException(pe, result);
      }
      return result;
   }

   @WithRoles
   @Override
   public DatasetService.DatasetList listBySchema(String uri, Integer limit, Integer page, String sort, String direction) {
      StringBuilder sql = new StringBuilder(LIST_SCHEMA_DATASETS);
      // TODO: filtering by fingerprint
      addOrderAndPaging(limit, page, sort, direction, sql);
      Query query = em.createNativeQuery(sql.toString()).setParameter(1, uri);
      //noinspection deprecation
      ((NativeQuery<?>) query.unwrap(NativeQuery.class))
            .addScalar("id", IntegerType.INSTANCE)
            .addScalar("runId", IntegerType.INSTANCE)
            .addScalar("ordinal", IntegerType.INSTANCE)
            .addScalar("testId", IntegerType.INSTANCE)
            .addScalar("testname", TextType.INSTANCE)
            .addScalar("description", TextType.INSTANCE)
            .addScalar("start", LongType.INSTANCE)
            .addScalar("stop", LongType.INSTANCE)
            .addScalar("owner", TextType.INSTANCE)
            .addScalar("access", IntegerType.INSTANCE)
            .addScalar("view", JsonNodeBinaryType.INSTANCE)
            .addScalar("schemas", JsonNodeBinaryType.INSTANCE)
            .setResultTransformer(DATASET_BY_SCHEMA_TRANSFORMER);
      DatasetService.DatasetList list = new DatasetService.DatasetList();
      //noinspection unchecked
      list.datasets = query.getResultList();
      list.total = ((Number) em.createNativeQuery("SELECT COUNT(dataset_id) FROM dataset_schemas WHERE uri = ?1")
            .setParameter(1, uri).getSingleResult()).longValue();
      return list;
   }

   @Override
   public List<LabelValue> labelValues(int datasetId) {
      //noinspection unchecked
      Stream<Object[]> stream = em.createNativeQuery("SELECT label_id, label.name AS label_name, schema.id AS schema_id, schema.name AS schema_name, schema.uri, value FROM label_values " +
            "JOIN label ON label.id = label_id JOIN schema ON label.schema_id = schema.id WHERE dataset_id = ?1")
            .setParameter(1, datasetId).unwrap(NativeQuery.class)
            .addScalar("label_id", IntegerType.INSTANCE)
            .addScalar("label_name", TextType.INSTANCE)
            .addScalar("schema_id", IntegerType.INSTANCE)
            .addScalar("schema_name", TextType.INSTANCE)
            .addScalar("uri", TextType.INSTANCE)
            .addScalar("value", JsonNodeBinaryType.INSTANCE)
            .getResultStream();
      return stream.map(row -> {
               LabelValue value = new LabelValue();
               value.id = (int) row[0];
               value.name = (String) row[1];
               value.schema = new SchemaService.SchemaDescriptor((int) row[2], (String) row[3], (String) row[4]);
               value.value = (JsonNode) row[5];
               return value;
            }).collect(Collectors.toList());
   }

   @Override
   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   public LabelPreview previewLabel(int datasetId, Label label) {
      // This is executed with elevated permissions, but with the same as a normal label calculation would use
      // Therefore we need to explicitly check dataset ownership
      DataSet dataset = DataSet.findById(datasetId);
      if (dataset == null || !Roles.hasRoleWithSuffix(identity, dataset.owner, "-tester")) {
         throw ServiceException.badRequest("Dataset not found or insufficient privileges.");
      }

      String extractors;
      try {
         extractors = Util.OBJECT_MAPPER.writeValueAsString(label.extractors);
      } catch (JsonProcessingException e) {
         log.error("Cannot serialize label extractors", e);
         throw ServiceException.badRequest("Cannot serialize label extractors");
      }
      JsonNode extracted = (JsonNode) em.createNativeQuery(LABEL_PREVIEW).unwrap(NativeQuery.class)
            .setParameter(1, extractors)
            .setParameter(2, datasetId)
            .setParameter(3, label.schema.id)
            .addScalar("value", JsonNodeBinaryType.INSTANCE).getSingleResult();

      LabelPreview preview = new LabelPreview();
      if (label.function == null || label.function.isBlank()) {
         preview.value = extracted;
      } else {
         AtomicReference<String> errorRef = new AtomicReference<>();
         AtomicReference<String> outputRef = new AtomicReference<>();
         JsonNode result = Util.evaluateOnce(label.function, extracted, Util::convertToJson,
               (code, exception) -> errorRef.set("Execution failed: " + exception.getMessage() + ":\n" + code), outputRef::set);
         preview.value = errorRef.get() == null ? result : JsonNodeFactory.instance.textNode(errorRef.get());
         preview.output = outputRef.get();
      }
      return preview;
   }

   @WithRoles
   @Override
   public DatasetSummary getSummary(int datasetId, int viewId) {
      try {
         Query query = em.createNativeQuery("WITH schema_agg AS (" + SCHEMAS_SELECT + " WHERE ds.dataset_id = ?1 GROUP BY ds.dataset_id) " +
               VALIDATION_SELECT + DATASET_SUMMARY_SELECT + " AND dv.view_id = ?2 WHERE ds.id = ?1")
               .setParameter(1, datasetId).setParameter(2, viewId);
         markAsSummaryList(query);
         return (DatasetSummary) query.getSingleResult();
      } catch (NoResultException e) {
         throw ServiceException.notFound("Cannot find dataset " + datasetId);
      }
   }

   @WithToken
   @WithRoles
   @Override
   public DataSet getDataSet(int datasetId) {
      DataSet dataset = DataSet.findById(datasetId);
      if (dataset != null) {
         Hibernate.initialize(dataset.data);
      }
      return dataset;
   }

   private void onLabelChanged(String param) {
      String[] parts = param.split(";");
      if (parts.length != 2) {
         log.errorf("Invalid parameter to onLabelChanged: %s", param);
         return;
      }
      int datasetId = Integer.parseInt(parts[0]);
      int labelId = Integer.parseInt(parts[1]);
      // This is invoked when the label is added/updated. We won't send notifications
      // for that (user can check if there are any changes on his own).
      calculateLabels(datasetId, labelId, true);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void calculateLabels(int datasetId, int queryLabelId, boolean isRecalculation) {
      log.infof("Calculating labels for dataset %d, label %d", datasetId, queryLabelId);
      // Note: we are fetching even labels that are marked as private/could be otherwise inaccessible
      // to the uploading user. However, the uploader should not have rights to fetch these anyway...
      @SuppressWarnings("unchecked") List<Object[]> extracted =
            (List<Object[]>) em.createNativeQuery(LABEL_QUERY)
                  .setParameter(1, datasetId)
                  .setParameter(2, queryLabelId)
                  .unwrap(NativeQuery.class)
                  .addScalar("label_id", IntegerType.INSTANCE)
                  .addScalar("name", TextType.INSTANCE)
                  .addScalar("function", TextType.INSTANCE)
                  .addScalar("value", JsonNodeBinaryType.INSTANCE)
                  .getResultList();

      Util.evaluateMany(extracted, row -> (String) row[2], row -> (JsonNode) row[3],
            (row, result) -> createLabel(datasetId, (int) row[0], Util.convertToJson(result)),
            row -> createLabel(datasetId, (int) row[0], (JsonNode) row[3]),
            (row, e, jsCode) -> logMessage(datasetId, PersistentLog.ERROR,
                  "Evaluation of label %s failed: '%s' Code:<pre>%s</pre>", row[0], e.getMessage(), jsCode),
            out -> logMessage(datasetId, PersistentLog.DEBUG, "Output while calculating labels: <pre>%s</pre>", out));
      Util.publishLater(tm, eventBus, DataSet.EVENT_LABELS_UPDATED, new DataSet.LabelsUpdatedEvent(datasetId, isRecalculation));
   }

   private void createLabel(int datasetId, int labelId, JsonNode value) {
      Label.Value labelValue = new Label.Value();
      labelValue.datasetId = datasetId;
      labelValue.labelId = labelId;
      labelValue.value = value;
      labelValue.persist();
   }

   void withRecalculationLock(Runnable runnable) {
      recalculationLock.lock();
      try {
         runnable.run();
      } finally {
         recalculationLock.unlock();
      }
   }

   @ConsumeEvent(value = DataSet.EVENT_NEW, blocking = true)
   public void onNewDataset(DataSet.EventNew event) {
      withRecalculationLock(() -> calculateLabels(event.dataset.id, -1, event.isRecalculation));
   }

   private void logMessage(int datasetId, int level, String message, Object... params) {
      String msg = String.format(message, params);
      int testId = (int) em.createNativeQuery("SELECT testid FROM dataset WHERE id = ?1").setParameter(1, datasetId).getSingleResult();
      log.tracef("Logging %s for test %d, dataset %d: %s", PersistentLog.logLevel(level), testId, datasetId, msg);
      new DatasetLog(em.getReference(Test.class, testId), em.getReference(DataSet.class, datasetId), level, "labels", msg).persist();
   }
}
