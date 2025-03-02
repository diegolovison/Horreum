package io.hyperfoil.tools.horreum.svc;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TransactionRequiredException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.vladmihalcea.hibernate.type.json.JsonNodeBinaryType;
import com.vladmihalcea.hibernate.type.util.MapResultTransformer;
import io.hyperfoil.tools.horreum.api.QueryResult;
import io.hyperfoil.tools.horreum.api.RunService;
import io.hyperfoil.tools.horreum.api.SqlService;
import io.hyperfoil.tools.horreum.entity.PersistentLog;
import io.hyperfoil.tools.horreum.entity.alerting.TransformationLog;
import io.hyperfoil.tools.horreum.entity.json.Access;
import io.hyperfoil.tools.horreum.entity.json.DataSet;
import io.hyperfoil.tools.horreum.entity.json.Run;
import io.hyperfoil.tools.horreum.entity.json.Schema;
import io.hyperfoil.tools.horreum.entity.json.Test;
import io.hyperfoil.tools.horreum.entity.json.Transformer;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.hyperfoil.tools.horreum.server.WithToken;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.runtime.Startup;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import org.hibernate.ScrollableResults;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.BooleanType;
import org.hibernate.type.IntegerType;
import org.hibernate.type.TextType;
import org.hibernate.type.TimestampType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;

import static com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
import static io.hyperfoil.tools.horreum.entity.json.Schema.QUERY_1ST_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID;
import static io.hyperfoil.tools.horreum.entity.json.Schema.QUERY_2ND_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID;
import static io.hyperfoil.tools.horreum.entity.json.Schema.QUERY_TRANSFORMER_TARGETS;

@ApplicationScoped
@Startup
public class RunServiceImpl implements RunService {
   private static final Logger log = Logger.getLogger(RunServiceImpl.class);

   //@formatter:off
   private static final String FIND_AUTOCOMPLETE =
         "SELECT * FROM (" +
            "SELECT DISTINCT jsonb_object_keys(q) AS key " +
            "FROM run, jsonb_path_query(run.data, ? ::::jsonpath) q " +
            "WHERE jsonb_typeof(q) = 'object') AS keys " +
         "WHERE keys.key LIKE CONCAT(?, '%');";
   protected static final String FIND_RUNS_WITH_URI = "SELECT id FROM run WHERE data->>'$schema' = ?1 OR (" +
         "CASE WHEN jsonb_typeof(data) = 'object' THEN ?1 IN (SELECT values.value->>'$schema' FROM jsonb_each(data) as values) " +
         "WHEN jsonb_typeof(data) = 'array' THEN ?1 IN (SELECT jsonb_array_elements(data)->>'$schema') ELSE false END)";
   //@formatter:on
   private static final String[] CONDITION_SELECT_TERMINAL = { "==", "!=", "<>", "<", "<=", ">", ">=", " " };
   private static final String UPDATE_TOKEN = "UPDATE run SET token = ? WHERE id = ?";
   private static final String CHANGE_ACCESS = "UPDATE run SET owner = ?, access = ? WHERE id = ?";

   @Inject
   EntityManager em;

   @Inject
   SecurityIdentity identity;

   @Inject
   TransactionManager tm;

   @Inject
   SqlServiceImpl sqlService;

   @Inject
   Vertx vertx;

   @Inject
   EventBus eventBus;

   @Inject
   TestServiceImpl testService;


   @PostConstruct
   void init() {
      sqlService.registerListener("calculate_datasets", this::onCalculateDataSets);
      sqlService.registerListener("new_or_updated_schema", this::onNewOrUpdatedSchema);
   }

   // We cannot run this without a transaction (to avoid timeout) because we have not request going on
   // and EM has to bind its lifecycle either to current request or transaction.
   @Transactional(Transactional.TxType.REQUIRES_NEW)
   @TransactionConfiguration(timeout = 3600) // 1 hour, this may run a long time
   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   void onNewOrUpdatedSchema(String schemaIdString) {
      int schemaId;
      try {
         schemaId = Integer.parseInt(schemaIdString);
      } catch (NumberFormatException e) {
         log.errorf("Cannot process schema add/update: invalid id %s", schemaIdString);
         return;
      }
      Schema schema = Schema.findById(schemaId);
      if (schema == null) {
         log.errorf("Cannot process schema add/update: cannot load schema %d", schemaId);
         return;
      }
      // we don't have to care about races with new runs
      findRunsWithUri(schema.uri, runId -> {
         log.infof("Recalculate DataSets for run %d - schema %d (%s) changed", runId, schema.id, schema.uri);
         Util.executeBlocking(vertx, CachedSecurityIdentity.ANONYMOUS, () -> onNewOrUpdatedSchemaForRun(runId));
      });
   }

   void findRunsWithUri(String uri, IntConsumer consumer) {
      ScrollableResults results = Util.scroll(em.createNativeQuery(FIND_RUNS_WITH_URI).setParameter(1, uri));
      while (results.next()) {
         consumer.accept((int) results.get(0));
      }
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void onNewOrUpdatedSchemaForRun(int runId) {
      em.createNativeQuery("SELECT update_run_schemas(?1)::::text").setParameter(1, runId).getSingleResult();
      transform(runId, true);
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public Object getRun(int id, String token) {
      return Util.runQuery(em, "SELECT (to_jsonb(run) || jsonb_build_object(" +
            "'schema', (SELECT COALESCE(jsonb_object_agg(schemaid, uri), '{}') FROM run_schemas WHERE runid = run.id), " +
            "'testname', (SELECT name FROM test WHERE test.id = run.testid), " +
            "'datasets', (SELECT jsonb_agg(id ORDER BY id) FROM dataset WHERE runid = run.id), " +
            "'validationErrors', (SELECT jsonb_agg(jsonb_build_object('schemaId', schema_id, 'error', error)) FROM run_validationerrors WHERE run_id = ?1)" +
            "))::::text FROM run WHERE id = ?1", id);
   }

   @WithRoles
   @Override
   public RunSummary getRunSummary(int id, String token) {
      // TODO: define the result set mapping properly without transforming jsonb and int[] to text
      Query query = em.createNativeQuery("SELECT run.id, run.start, run.stop, run.testid, " +
            "run.owner, run.access, run.token, run.trashed, run.description, " +
            "(SELECT name FROM test WHERE test.id = run.testid) as testname, " +
            "(SELECT COALESCE(jsonb_object_agg(schemaid, uri), '{}') FROM run_schemas WHERE runid = run.id)::::text as schema, " +
            "(SELECT json_agg(id ORDER BY id) FROM dataset WHERE runid = run.id)::::text as datasets " +
            " FROM run where id = ?").setParameter(1, id);
      query.unwrap(org.hibernate.query.Query.class);

      Object[] row = (Object[]) query.getSingleResult();
      RunSummary summary = new RunSummary();
      initSummary(row, summary);
      summary.schema = Util.toJsonNode((String) row[10]);
      summary.datasets = (ArrayNode) Util.toJsonNode((String) row[11]);
      return summary;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public Object getData(int id, String token) {
      return Util.runQuery(em, "SELECT data#>>'{}' from run where id = ?", id);
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public QueryResult queryData(int id, String jsonpath, String schemaUri, boolean array) {
      String func = array ? "jsonb_path_query_array" : "jsonb_path_query_first";
      QueryResult result = new QueryResult();
      result.jsonpath = jsonpath;
      try {
         if (schemaUri != null && !schemaUri.isEmpty()) {
            String sqlQuery = "SELECT " + func + "((CASE " +
                  "WHEN rs.type = 0 THEN run.data WHEN rs.type = 1 THEN run.data->rs.key ELSE run.data->(rs.key::::integer) END)" +
                  ", (?1)::::jsonpath)#>>'{}' FROM run JOIN run_schemas rs ON rs.runid = run.id WHERE id = ?2 AND rs.uri = ?3";
            result.value = String.valueOf(Util.runQuery(em, sqlQuery, jsonpath, id, schemaUri));
         } else {
            String sqlQuery = "SELECT " + func + "(data, (?1)::::jsonpath)#>>'{}' FROM run WHERE id = ?2";
            result.value = String.valueOf(Util.runQuery(em, sqlQuery, jsonpath, id));
         }
         result.valid = true;
      } catch (PersistenceException pe) {
         SqlServiceImpl.setFromException(pe, result);
      }
      return result;
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public String resetToken(int id) {
      return updateToken(id, Tokens.generateToken());
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public String dropToken(int id) {
      return updateToken(id, null);
   }

   private String updateToken(int id, String token) {
      Query query = em.createNativeQuery(UPDATE_TOKEN);
      query.setParameter(1, token);
      query.setParameter(2, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Token reset failed (missing permissions?)");
      } else {
         return token;
      }
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   // TODO: it would be nicer to use @FormParams but fetchival on client side doesn't support that
   public void updateAccess(int id, String owner, Access access) {
      Query query = em.createNativeQuery(CHANGE_ACCESS);
      query.setParameter(1, owner);
      query.setParameter(2, access.ordinal());
      query.setParameter(3, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Access change failed (missing permissions?)");
      }
   }

   @PermitAll // all because of possible token-based upload
   @WithRoles
   @WithToken
   @Transactional
   @Override
   public Response add(String testNameOrId, String owner, Access access, String token, Run run) {
      if (owner != null) {
         run.owner = owner;
      }
      if (access != null) {
         run.access = access;
      }
      log.debugf("About to add new run to test %s using owner", testNameOrId, owner);
      Test test = testService.ensureTestExists(testNameOrId, token);
      run.testid = test.id;
      Integer runId = addAuthenticated(run, test);
      return Response.status(Response.Status.OK).entity(String.valueOf(runId)).header(HttpHeaders.LOCATION, "/run/" + runId).build();
   }

   @PermitAll // all because of possible token-based upload
   @Transactional
   @WithRoles
   @WithToken
   @Override
   public Response addRunFromData(String start, String stop, String test,
                                String owner, Access access, String token,
                                String schemaUri, String description,
                                JsonNode data) {
      if (data == null) {
         log.debugf("Failed to upload for test %s with description %s because of missing data.", test, description);
         throw ServiceException.badRequest("No data!");
      }
      Object foundTest = findIfNotSet(test, data);
      Object foundStart = findIfNotSet(start, data);
      Object foundStop = findIfNotSet(stop, data);
      Object foundDescription = findIfNotSet(description, data);

      if (schemaUri != null && !schemaUri.isEmpty()) {
         if (data.isObject()) {
            ((ObjectNode) data).set("$schema", TextNode.valueOf(schemaUri));
         }
      }

      String testNameOrId = foundTest == null ? null : foundTest.toString().trim();
      if (testNameOrId == null || testNameOrId.isEmpty()) {
         log.debugf("Failed to upload for test %s with description %s as the test cannot be identified.", test, description);
         throw ServiceException.badRequest("Cannot identify test name.");
      }

      Instant startInstant = toInstant(foundStart);
      Instant stopInstant = toInstant(foundStop);
      if (startInstant == null) {
         log.debugf("Failed to upload for test %s with description %s; cannot parse start time %s (%s)", test, description, foundStart, start);
         throw ServiceException.badRequest("Cannot parse start time from " + foundStart + " (" + start + ")");
      } else if (stopInstant == null) {
         log.debugf("Failed to upload for test %s with description %s; cannot parse start time %s (%s)", test, description, foundStop,stop);
         throw ServiceException.badRequest("Cannot parse stop time from " + foundStop + " (" + stop + ")");
      }

      Test testEntity = testService.ensureTestExists(testNameOrId, token);

      log.debugf("Creating new run for test %s(%d) with description %s", testEntity.name, testEntity.id, foundDescription);

      Run run = new Run();
      run.testid = testEntity.id;
      run.start = startInstant;
      run.stop = stopInstant;
      run.description = foundDescription != null ? foundDescription.toString() : null;
      run.data = data;
      run.owner = owner;
      run.access = access;
      // Some triggered functions in the database need to be able to read the just-inserted run
      // otherwise RLS policies will fail. That's why we reuse the token for the test and later wipe it out.
      run.token = token;

      Integer runId = addAuthenticated(run, testEntity);
      if (token != null) {
         // TODO: remove the token
      }
      return Response.status(Response.Status.OK).entity(String.valueOf(runId)).header(HttpHeaders.LOCATION, "/run/" + runId).build();
   }

   private Object findIfNotSet(String value, JsonNode data) {
      if (value != null && !value.isEmpty()) {
         if (value.startsWith("$.")) {
            return Util.findJsonPath(data, value);
         } else {
            return value;
         }
      } else {
         return null;
      }
   }

   private Instant toInstant(Object time) {
      if (time == null) {
         return null;
      } else if (time instanceof Number) {
         return Instant.ofEpochMilli(((Number) time).longValue());
      } else {
         try {
            return Instant.ofEpochMilli(Long.parseLong((String) time));
         } catch (NumberFormatException e) {
            // noop
         }
         try {
            return ZonedDateTime.parse(time.toString().trim(), DateTimeFormatter.ISO_DATE_TIME).toInstant();
         } catch (DateTimeParseException e) {
            return null;
         }
      }
   }

   private Integer addAuthenticated(Run run, Test test) {
      // Id will be always generated anew
      run.id = null;

      if (run.owner == null) {
         List<String> uploaders = identity.getRoles().stream().filter(role -> role.endsWith("-uploader")).collect(Collectors.toList());
         if (uploaders.size() != 1) {
            log.debugf("Failed to upload for test %s: no owner, available uploaders: %s", test.name, uploaders);
            throw ServiceException.badRequest("Missing owner and cannot select single default owners; this user has these uploader roles: " + uploaders);
         }
         String uploader = uploaders.get(0);
         run.owner = uploader.substring(0, uploader.length() - 9) + "-team";
      } else if (!Objects.equals(test.owner, run.owner) && !identity.getRoles().contains(run.owner)) {
         log.debugf("Failed to upload for test %s: requested owner %s, available roles: %s", test.name, run.owner, identity.getRoles());
         throw ServiceException.badRequest("This user does not have permissions to upload run for owner=" + run.owner);
      }
      if (run.access == null) {
         run.access = Access.PRIVATE;
      }
      log.debugf("Uploading with owner=%s and access=%s", run.owner, run.access);

      try {
         if (run.id == null) {
            em.persist(run);
         } else {
            em.merge(run);
         }
         em.flush();
      } catch (Exception e) {
         log.error("Failed to persist run.", e);
         throw ServiceException.serverError("Failed to persist run");
      }
      log.debugf("Upload flushed, run ID %d", run.id);
      Util.publishLater(tm, eventBus, Run.EVENT_NEW, run);

      return run.id;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public List<String> autocomplete(String query) {
      if (query == null || query.isEmpty()) {
         return null;
      }
      String jsonpath = query.trim();
      String incomplete = "";
      if (jsonpath.endsWith(".")) {
         jsonpath = jsonpath.substring(0, jsonpath.length() - 1);
      } else {
         int lastDot = jsonpath.lastIndexOf('.');
         if (lastDot > 0) {
            incomplete = jsonpath.substring(lastDot + 1);
            jsonpath = jsonpath.substring(0, lastDot);
         } else {
            incomplete = jsonpath;
            jsonpath = "$.**";
         }
      }
      int conditionIndex = jsonpath.indexOf('@');
      if (conditionIndex >= 0) {
         int conditionSelectEnd = jsonpath.length();
         for (String terminal : CONDITION_SELECT_TERMINAL) {
            int ti = jsonpath.indexOf(terminal, conditionIndex + 1);
            if (ti >= 0) {
               conditionSelectEnd = Math.min(conditionSelectEnd, ti);
            }
         }
         String conditionSelect = jsonpath.substring(conditionIndex + 1, conditionSelectEnd);
         int queryIndex = jsonpath.indexOf('?');
         if (queryIndex < 0) {
            // This is a shortcut query '@.foo...'
            jsonpath = "$.**" + conditionSelect;
         } else if (queryIndex > conditionIndex) {
            // Too complex query with multiple conditions
            return Collections.emptyList();
         } else {
            while (queryIndex > 0 && Character.isWhitespace(jsonpath.charAt(queryIndex - 1))) {
               --queryIndex;
            }
            jsonpath = jsonpath.substring(0, queryIndex) + conditionSelect;
         }
      }
      if (!jsonpath.startsWith("$")) {
         jsonpath = "$.**." + jsonpath;
      }
      try {
         Query findAutocomplete = em.createNativeQuery(FIND_AUTOCOMPLETE);
         findAutocomplete.setParameter(1, jsonpath);
         findAutocomplete.setParameter(2, incomplete);
         @SuppressWarnings("unchecked")
         List<String> results = findAutocomplete.getResultList();
         return results.stream().map(option ->
               option.matches("^[a-zA-Z0-9_-]*$") ? option : "\"" + option + "\"")
               .collect(Collectors.toList());
      } catch (PersistenceException e) {
         throw ServiceException.badRequest("Failed processing query '" + query + "':\n" + e.getLocalizedMessage());
      }
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunsSummary listAllRuns(String query, boolean matchAll, String roles, boolean trashed,
                                  Integer limit, Integer page, String sort, String direction) {
      StringBuilder sql = new StringBuilder("SELECT run.id, run.start, run.stop, run.testId, ")
         .append("run.owner, run.access, run.token, run.trashed, run.description, test.name AS testname ")
         .append("FROM run JOIN test ON test.id = run.testId WHERE ");
      String[] queryParts;
      boolean whereStarted = false;
      if (query == null || query.isEmpty()) {
         queryParts = new String[0];
      } else {
         query = query.trim();
         if (query.startsWith("$") || query.startsWith("@")) {
            queryParts = new String[] { query };
         } else {
            queryParts = query.split("([ \t\n,]+)|\\bOR\\b");
         }
         sql.append("(");
         for (int i = 0; i < queryParts.length; ++i) {
            if (i != 0) {
               sql.append(matchAll ? " AND " : " OR ");
            }
            sql.append("jsonb_path_exists(data, ?").append(i + 1).append(" ::::jsonpath)");
            if (queryParts[i].startsWith("$")) {
               // no change
            } else if (queryParts[i].startsWith("@")) {
               queryParts[i] = "$.** ? (" + queryParts[i] + ")";
            } else {
               queryParts[i] = "$.**." + queryParts[i];
            }
         }
         sql.append(")");
         whereStarted = true;
      }

      whereStarted = Roles.addRolesSql(identity, "run", sql, roles, queryParts.length + 1, whereStarted ? " AND" : null) || whereStarted;
      if (!trashed) {
         if (whereStarted) {
            sql.append(" AND ");
         }
         sql.append(" trashed = false ");
      }
      Util.addPaging(sql, limit, page, sort, direction);

      Query sqlQuery = em.createNativeQuery(sql.toString());
      for (int i = 0; i < queryParts.length; ++i) {
         sqlQuery.setParameter(i + 1, queryParts[i]);
      }

      Roles.addRolesParam(identity, sqlQuery, queryParts.length + 1, roles);

      try {
         @SuppressWarnings("unchecked")
         List<Object[]> runs = sqlQuery.getResultList();

         RunsSummary summary = new RunsSummary();
         // TODO: total does not consider the query but evaluating all the expressions would be expensive
         summary.total = trashed ? Run.count() : Run.count("trashed = false");
         summary.runs = runs.stream().map(row -> {
            RunSummary run = new RunSummary();
            initSummary(row, run);
            return run;
         }).collect(Collectors.toList());
         return summary;
      } catch (PersistenceException pe) {
         // In case of an error PostgreSQL won't let us execute another query in the same transaction
         try {
            Transaction old = tm.suspend();
            try {
               for (String jsonpath : queryParts) {
                  SqlService.JsonpathValidation result = sqlService.testJsonPathInternal(jsonpath);
                  if (!result.valid) {
                     throw new WebApplicationException(Response.status(400).entity(result).build());
                  }
               }
            } finally {
               tm.resume(old);
            }
         } catch (InvalidTransactionException | SystemException e) {
            // ignore
         }
         throw new WebApplicationException(pe, 500);
      }
   }

   private void initSummary(Object[] row, RunSummary run) {
      run.id = (int) row[0];
      run.start = ((Timestamp) row[1]).getTime();
      run.stop = ((Timestamp) row[2]).getTime();
      run.testid = (int) row[3];
      run.owner = (String) row[4];
      run.access = (int) row[5];
      run.token = (String) row[6];
      run.trashed = (boolean) row[7];
      run.description = (String) row[8];
      run.testname = (String) row[9];
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunCount runCount(int testId) {
      RunCount counts = new RunCount();
      counts.total = Run.count("testid = ?1", testId);
      counts.active = Run.count("testid = ?1 AND trashed = false", testId);
      counts.trashed = counts.total - counts.active;
      return counts;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunsSummary listTestRuns(int testId, boolean trashed,
                                   Integer limit, Integer page, String sort, String direction) {
      StringBuilder sql = new StringBuilder("WITH schema_agg AS (")
            .append("    SELECT COALESCE(jsonb_object_agg(schemaid, uri), '{}') AS schemas, rs.runid FROM run_schemas rs GROUP BY rs.runid")
            .append("), dataset_agg AS (")
            .append("    SELECT runid, jsonb_agg(id ORDER BY id)::::text as datasets FROM dataset WHERE testid = ?1 GROUP BY runid")
            .append("), validation AS (")
            .append("    SELECT run_id, jsonb_agg(jsonb_build_object('schemaId', schema_id, 'error', error)) AS errors FROM run_validationerrors GROUP BY run_id")
            .append(") SELECT run.id, run.start, run.stop, run.access, run.owner, schema_agg.schemas AS schemas, ")
            .append("run.trashed, run.description, COALESCE(dataset_agg.datasets, '[]') AS datasets, ")
            .append("COALESCE(validation.errors, '[]') AS validationErrors FROM run ")
            .append("LEFT JOIN schema_agg ON schema_agg.runid = run.id ")
            .append("LEFT JOIN dataset_agg ON dataset_agg.runid = run.id ")
            .append("LEFT JOIN validation ON validation.run_id = run.id ")
            .append("WHERE run.testid = ?1 ");
      if (!trashed) {
         sql.append(" AND NOT run.trashed ");
      }
      Util.addOrderBy(sql, sort, direction);
      Util.addLimitOffset(sql, limit, page);
      Test test = Test.find("id", testId).firstResult();
      if (test == null) {
         throw ServiceException.notFound("Cannot find test ID " + testId);
      }
      Query query = em.createNativeQuery(sql.toString());
      query.setParameter(1, testId);
      query.unwrap(NativeQuery.class)
            .addScalar("id", IntegerType.INSTANCE)
            .addScalar("start", TimestampType.INSTANCE)
            .addScalar("stop", TimestampType.INSTANCE)
            .addScalar("access", IntegerType.INSTANCE)
            .addScalar("owner", TextType.INSTANCE)
            .addScalar("schemas", JsonNodeBinaryType.INSTANCE)
            .addScalar("trashed", BooleanType.INSTANCE)
            .addScalar("description", TextType.INSTANCE)
            .addScalar("datasets", JsonNodeBinaryType.INSTANCE)
            .addScalar("validationErrors", JsonNodeBinaryType.INSTANCE);
      @SuppressWarnings("unchecked")
      List<Object[]> resultList = query.getResultList();
      List<RunSummary> runs = new ArrayList<>();
      for (Object[] row : resultList) {
         RunSummary run = new RunSummary();
         run.id = (int) row[0];
         run.start = ((Timestamp) row[1]).getTime();
         run.stop = ((Timestamp) row[2]).getTime();
         run.testid = testId;
         run.access = (int) row[3];
         run.owner = (String) row[4];
         run.schema = (JsonNode) row[5];
         run.trashed = (boolean) row[6];
         run.description = (String) row[7];
         run.datasets = (ArrayNode) row[8];
         run.validationErrors = (ArrayNode) row[9];
         runs.add(run);
      }
      RunsSummary summary = new RunsSummary();
      summary.total = trashed ? Run.count("testid = ?1", testId) : Run.count("testid = ?1 AND trashed = false", testId);
      summary.runs = runs;
      return summary;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunsSummary listBySchema(String uri, Integer limit, Integer page, String sort, String direction) {
      if (uri == null || uri.isEmpty()) {
         throw ServiceException.badRequest("No `uri` query parameter given.");
      }
      StringBuilder sql = new StringBuilder("SELECT run.id, run.start, run.stop, run.testId, ")
            .append("run.owner, run.access, run.token, test.name AS testname, run.description ")
            .append("FROM run_schemas rs JOIN run ON rs.runid = run.id JOIN test ON rs.testid = test.id ")
            .append("WHERE uri = ? AND NOT run.trashed");
      Util.addPaging(sql, limit, page, sort, direction);
      Query query = em.createNativeQuery(sql.toString());
      query.setParameter(1, uri);
      @SuppressWarnings("unchecked")
      List<Object[]> runs = query.getResultList();

      RunsSummary summary = new RunsSummary();
      summary.runs = runs.stream().map(row -> {
         RunSummary run = new RunSummary();
         run.id = (int) row[0];
         run.start = ((Timestamp) row[1]).getTime();
         run.stop = ((Timestamp) row[2]).getTime();
         run.testid = (int) row[3];
         run.owner = (String) row[4];
         run.access = (int) row[5];
         run.token = (String) row[6];
         run.testname = (String) row[7];
         run.description = (String) row[8];
         return run;
      }).collect(Collectors.toList());
      summary.total = ((BigInteger) em.createNativeQuery("SELECT count(*) FROM run_schemas WHERE uri = ?")
            .setParameter(1, uri).getSingleResult()).longValue();
      return summary;
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public void trash(int id, Boolean isTrashed) {
      boolean trashed = isTrashed == null || isTrashed;
      updateRun(id, run -> run.trashed = trashed);
      if (trashed) {
         for (var dataset : DataSet.<DataSet>list("run.id", id)) {
            Util.publishLater(tm, eventBus, DataSet.EVENT_DELETED, dataset.getInfo());
            dataset.delete();
         }
         Util.publishLater(tm, eventBus, Run.EVENT_TRASHED, id);
      } else {
         transform(id, true);
      }
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public void updateDescription(int id, String description) {
      // FIXME: fetchival stringifies the body into JSON string :-/
      updateRun(id, run -> run.description = Util.destringify(description));
   }

   public void updateRun(int id, Consumer<Run> consumer) {
      Run run = Run.findById(id);
      if (run == null) {
         throw ServiceException.notFound("Run not found");
      }
      consumer.accept(run);
      run.persistAndFlush();
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public Map<Integer, String> updateSchema(int id, String path, String schemaUri) {
      // FIXME: fetchival stringifies the body into JSON string :-/
      Run run = Run.findById(id);
      if (run == null) {
         throw ServiceException.notFound("Run not found.");
      }
      String uri = Util.destringify(schemaUri);
      // Triggering dirty property on Run
      JsonNode updated = run.data.deepCopy();
      JsonNode item;
      if (updated.isObject()) {
         item = path == null ? updated : updated.path(path);
      } else if (updated.isArray()) {
         if (path == null) {
            throw ServiceException.badRequest("Cannot update root schema in an array.");
         }
         item = updated.get(Integer.parseInt(path));
      } else {
         throw ServiceException.serverError("Cannot update run data with path " + path);
      }
      if (item.isObject()) {
         if (uri != null && !uri.isEmpty()) {
            ((ObjectNode) item).set("$schema", new TextNode(uri));
         } else {
            ((ObjectNode) item).remove("$schema");
         }
      } else {
         throw ServiceException.badRequest("Cannot update schema at " + (path == null ? "<root>" : path) + " as the target is not an object");
      }
      run.data = updated;
      run.persist();
      Query query = em.createNativeQuery("SELECT schemaid AS key, uri AS value FROM run_schemas WHERE runid = ?");
      query.setParameter(1, run.id);
      query.unwrap(NativeQuery.class).setResultTransformer(new MapResultTransformer<Integer, String>());
      @SuppressWarnings("unchecked") Map<Integer, String> schemas = (Map<Integer, String>) query.getSingleResult();
      em.flush();
      return schemas;
   }

   @WithRoles
   @Transactional
   @Override
   public List<Integer> recalculateDatasets(int runId) {
      transform(runId, true);
      //noinspection unchecked
      return em.createNativeQuery("SELECT id FROM dataset WHERE runid = ? ORDER BY ordinal")
            .setParameter(1, runId).getResultList();
   }

   private void onCalculateDataSets(String param) {
      String[] parts = param.split(";", 2);
      int runId;
      try {
         runId = Integer.parseInt(parts[0]);
      } catch (NumberFormatException e) {
         log.errorf("Received notification to calculate dataset for run but cannot parse as run ID.", parts[0]);
         return;
      }
      boolean isRecalculation = parts.length > 1 && Boolean.parseBoolean(parts[1]);
      transform(runId, isRecalculation);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   int transform(int runId, boolean isRecalculation) {
      if (runId < 1) {
         log.errorf("Transformation parameters error: run %s", runId);
         return 0;
      }
      log.infof("Transforming run ID %d, recalculation? %s", runId, isRecalculation);
      // We need to make sure all old datasets are gone before creating new; otherwise we could
      // break the runid,ordinal uniqueness constraint
      for (DataSet old : DataSet.<DataSet>list("runid", runId)) {
         Util.publishLater(tm, eventBus, DataSet.EVENT_DELETED, old.getInfo());
         old.delete();
      }

      Run run = Run.findById(runId);
      int ordinal = 0;
      Map<Integer, JsonNode> transformerResults = new TreeMap<>();
      // naked nodes (those produced by implicit identity transformers) are all added to each dataset
      List<JsonNode> nakedNodes = new ArrayList<>();

      List<Object[]> relevantSchemas = unchecked(em.createNamedQuery(QUERY_TRANSFORMER_TARGETS)
            .setParameter(1, run.id)
            .unwrap(NativeQuery.class)
            .addScalar("type", IntegerType.INSTANCE)
            .addScalar("key", TextType.INSTANCE)
            .addScalar("transformer_id", IntegerType.INSTANCE)
            .addScalar("uri", TextType.INSTANCE)
            .getResultList() );

      int schemasAndTransformers = relevantSchemas.size();
      for (Object[] relevantSchema : relevantSchemas) {
         int type = (int) relevantSchema[0];
         String key = (String) relevantSchema[1];
         Integer transformerId = (Integer) relevantSchema[2];
         String uri = (String) relevantSchema[3];

         Transformer t;
         if (transformerId != null) {
            t = Transformer.findById(transformerId);
            if (t == null) {
               log.errorf("Missing transformer with ID %d", transformerId);
            }
         } else {
            t = null;
         }
         if (t != null) {
            JsonNode root = JsonNodeFactory.instance.objectNode();
            JsonNode result;
            if (t.extractors != null && !t.extractors.isEmpty()) {
               List<Object[]> extractedData;
               if (type == Schema.TYPE_1ST_LEVEL) {
                  extractedData = unchecked(em.createNamedQuery(QUERY_1ST_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID)
                        .setParameter(1, run.id).setParameter(2, transformerId)
                        .unwrap(NativeQuery.class)
                        .addScalar("name", TextType.INSTANCE)
                        .addScalar("value", JsonNodeBinaryType.INSTANCE)
                        .getResultList());
               } else {
                  extractedData = unchecked(em.createNamedQuery(QUERY_2ND_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID)
                        .setParameter(1, run.id).setParameter(2, transformerId)
                        .setParameter(3, type == Schema.TYPE_2ND_LEVEL ? key : Integer.parseInt(key))
                        .unwrap(NativeQuery.class)
                        .addScalar("name", TextType.INSTANCE)
                        .addScalar("value", JsonNodeBinaryType.INSTANCE)
                        .getResultList());
               }
               addExtracted((ObjectNode) root, extractedData);
            }
            // In Horreum it's customary that when a single extractor is used we pass the result directly to the function
            // without wrapping it in an extra object.
            if (t.extractors.size() == 1) {
               if (root.size() != 1) {
                  // missing results should be null nodes
                  log.errorf("Unexpected result for single extractor: %s", root.toPrettyString());
               } else {
                  root = root.iterator().next();
               }
            }
            logMessage(run, PersistentLog.DEBUG, "Run transformer %s/%s with input: <pre>%s</pre>, function: <pre>%s</pre>",
                  uri, t.name, limitLength(root.toPrettyString()), t.function);
            if (t.function != null && !t.function.isBlank()) {
               result = Util.evaluateOnce(t.function, root, Util::convertToJson,
                     (code, e) -> logMessage(run, PersistentLog.ERROR,
                           "Evaluation of transformer %s/%s failed: '%s' Code: <pre>%s</pre>", uri, t.name, e.getMessage(), code),
                     output -> logMessage(run, PersistentLog.DEBUG, "Output while running transformer %s/%s: <pre>%s</pre>", uri, t.name, output));
            } else {
               result = root;
            }
            if (t.targetSchemaUri != null) {
               if (result.isObject()) {
                  putIfAbsent(t.targetSchemaUri, (ObjectNode) result);
               } else if (result.isArray()) {
                  ArrayNode array = (ArrayNode) result;
                  for (JsonNode node : array) {
                     if (node.isObject()) {
                        putIfAbsent(t.targetSchemaUri, (ObjectNode) node);
                     }
                  }
               } else {
                  result = instance.objectNode()
                        .put("$schema", t.targetSchemaUri).set("value", result);
               }
            } else if (!result.isContainerNode() || (result.isObject() && !result.has("$schema")) ||
                  (result.isArray() && StreamSupport.stream(result.spliterator(), false).anyMatch(item -> !item.has("$schema")))) {
               logMessage(run, PersistentLog.WARN, "Dataset will contain element without a schema.");
            }
            JsonNode existing = transformerResults.get(transformerId);
            if (existing == null) {
               transformerResults.put(transformerId, result);
            } else if (existing.isArray()) {
               if (result.isArray()) {
                  ((ArrayNode) existing).addAll((ArrayNode) result);
               } else {
                  ((ArrayNode) existing).add(result);
               }
            } else {
               if (result.isArray()) {
                  ((ArrayNode) result).insert(0, existing);
                  transformerResults.put(transformerId, result);
               } else {
                  transformerResults.put(transformerId, instance.arrayNode().add(existing).add(result));
               }
            }
         } else {
            JsonNode node;
            switch (type) {
               case Schema.TYPE_1ST_LEVEL:
                  node = run.data;
                  break;
               case Schema.TYPE_2ND_LEVEL:
                  node = run.data.path(key);
                  break;
               case Schema.TYPE_ARRAY_ELEMENT:
                  node = run.data.path(Integer.parseInt(key));
                  break;
               default:
                  throw new IllegalStateException("Unknown type " + type);
            }
            nakedNodes.add(node);
            logMessage(run, PersistentLog.DEBUG, "No transformer for schema %s (key %s), passing as-is.", uri, key);
         }
      }
      if (schemasAndTransformers > 0) {
         int max = transformerResults.values().stream().filter(JsonNode::isArray).mapToInt(JsonNode::size).max().orElse(1);

         for (int position = 0; position < max; position += 1) {
            ArrayNode all = instance.arrayNode(max + nakedNodes.size());
            for (var entry: transformerResults.entrySet()) {
               JsonNode node = entry.getValue();
               if (node.isObject()) {
                  all.add(node);
               } else if (node.isArray()) {
                  if (position < node.size()) {
                     all.add(node.get(position));
                  } else {
                     String message = String.format("Transformer %d produced an array of %d elements but other transformer " +
                                 "produced %d elements; dataset %d/%d might be missing some data.",
                           entry.getKey(), node.size(), max, run.id, ordinal);
                     logMessage(run, PersistentLog.WARN, "%s", message);
                     log.warnf(message);
                  }
               } else {
                  log.warnf("Unexpected result provided by one of the transformers: %s", node);
               }
            }
            nakedNodes.forEach(all::add);
            createDataset(new DataSet(run, ordinal++, run.description,
                  all), isRecalculation);
         }
         return ordinal;
      } else {
         logMessage(run, PersistentLog.INFO, "No applicable schema, dataset will be empty.");
         createDataset(new DataSet(
               run, 0, "Empty DataSet for run data without any schema.",
               instance.arrayNode()), isRecalculation);
         return 1;
      }
   }

   private String limitLength(String str) {
      return str.length() > 1024 ? str.substring(0, 1024) + "...(truncated)" : str;
   }

   private void createDataset(DataSet ds, boolean isRecalculation) {
      try {
         ds.persist();
         Util.publishLater(tm, eventBus, DataSet.EVENT_NEW, new DataSet.EventNew(ds, isRecalculation));
      } catch (TransactionRequiredException tre) {
         log.error("Failed attempt to persist and send DataSet event during inactive Transaction. Likely due to prior error.", tre);
      }
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional(Transactional.TxType.REQUIRES_NEW)
   protected void logMessage(Run run, int level, String format, Object... args) {
      String msg = args.length > 0 ? String.format(format, args) : format;
      new TransformationLog(em.getReference(Test.class, run.testid), run, level, msg).persist();
   }

   @SuppressWarnings("unchecked")
   private List<Object[]> unchecked(@SuppressWarnings("rawtypes") List list) {
      return (List<Object[]>)list;
   }

   private void addExtracted(ObjectNode root, List<Object[]> resultSet) {
      for (Object[] labelValue : resultSet) {
         String name = (String)labelValue[0];
         JsonNode value = (JsonNode) labelValue[1];
         root.set(name, value);
      }
   }

   private void putIfAbsent(String uri, ObjectNode node) {
      if (uri != null && !uri.isBlank() && node != null && node.path("$schema").isMissingNode()) {
         node.put("$schema", uri);
      }
   }
}
