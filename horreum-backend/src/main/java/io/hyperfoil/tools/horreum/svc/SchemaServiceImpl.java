package io.hyperfoil.tools.horreum.svc;

import io.hyperfoil.tools.horreum.api.SchemaService;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.entity.ValidationError;
import io.hyperfoil.tools.horreum.entity.json.Access;
import io.hyperfoil.tools.horreum.entity.json.DataSet;
import io.hyperfoil.tools.horreum.entity.json.Label;
import io.hyperfoil.tools.horreum.entity.json.Extractor;
import io.hyperfoil.tools.horreum.entity.json.Run;
import io.hyperfoil.tools.horreum.entity.json.Schema;
import io.hyperfoil.tools.horreum.entity.json.Transformer;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.hyperfoil.tools.horreum.server.WithToken;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.Startup;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.Vertx;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.hibernate.ScrollableResults;
import org.hibernate.query.NativeQuery;
import org.hibernate.transform.AliasToBeanResultTransformer;
import org.hibernate.type.IntegerType;
import org.hibernate.type.TextType;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.uri.URIFactory;
import com.networknt.schema.uri.URIFetcher;
import com.networknt.schema.uri.URLFactory;
import com.vladmihalcea.hibernate.type.json.JsonNodeBinaryType;

@Startup
public class SchemaServiceImpl implements SchemaService {
   private static final Logger log = Logger.getLogger(SchemaServiceImpl.class);

   //@formatter:off
   private static final String UPDATE_TOKEN = "UPDATE schema SET token = ? WHERE id = ?";
   private static final String CHANGE_ACCESS = "UPDATE schema SET owner = ?, access = ? WHERE id = ?";
   private static final String FETCH_SCHEMAS_RECURSIVE = "WITH RECURSIVE refs(uri) AS (" +
         "SELECT ? UNION ALL " +
         "SELECT substring(jsonb_path_query(schema, '$.**.\"$ref\" ? (! (@ starts with \"#\"))')#>>'{}' from '[^#]*') as uri " +
            "FROM refs INNER JOIN schema on refs.uri = schema.uri) " +
         "SELECT schema.* FROM schema INNER JOIN refs ON schema.uri = refs.uri";
   //@formatter:on

   private static final JsonSchemaFactory JSON_SCHEMA_FACTORY = new JsonSchemaFactory.Builder()
         .defaultMetaSchemaURI(JsonMetaSchema.getV4().getUri())
         .addMetaSchema(JsonMetaSchema.getV4())
         .addMetaSchema(JsonMetaSchema.getV6())
         .addMetaSchema(JsonMetaSchema.getV7())
         .addMetaSchema(JsonMetaSchema.getV201909()).build();
   private static final URIFactory URN_FACTORY = new URIFactory() {
      @Override
      public URI create(String uri) {
         return URI.create(uri);
      }

      @Override
      public URI create(URI baseURI, String segment) {
         throw new UnsupportedOperationException();
      }
   };
   private static final String[] ALL_URNS = Stream.concat(
         URLFactory.SUPPORTED_SCHEMES.stream(), Stream.of("urn", "uri")
   ).toArray(String[]::new);

   private static final AliasToBeanResultTransformer DESCRIPTOR_TRANSFORMER = new AliasToBeanResultTransformer(SchemaDescriptor.class);

   @Inject
   EntityManager em;

   @Inject
   SecurityIdentity identity;

   @Inject
   SqlServiceImpl sqlService;

   @Inject
   RunServiceImpl runService;

   @Inject
   Vertx vertx;

   @Inject
   TransactionManager tm;

   @PostConstruct
   void init() {
      sqlService.registerListener("validate_run_data", params -> validateRunData(Integer.parseInt(params), null));
      sqlService.registerListener("validate_dataset_data", params -> validateDatasetData(Integer.parseInt(params), null));
      sqlService.registerListener("revalidate_all", this::revalidateAll);
   }

   @WithToken
   @WithRoles
   @PermitAll
   @Override
   public Schema getSchema(int id, String token){
      Schema schema = Schema.find("id", id).firstResult();
      if (schema == null) {
         throw ServiceException.notFound("Schema not found");
      }
      return schema;
   }

   @Override
   public int idByUri(String uri) {
      try {
         return (Integer) em.createNativeQuery("SELECT id FROM schema WHERE uri = ?").setParameter(1, uri).getSingleResult();
      } catch (NoResultException e) {
         throw ServiceException.notFound("Schema with given uri not found: " + uri);
      }
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public Integer add(Schema schema){
      if (schema.uri == null || Arrays.stream(ALL_URNS).noneMatch(scheme -> schema.uri.startsWith(scheme + ":"))) {
         throw ServiceException.badRequest("Please use URI starting with one of these schemes: " + Arrays.toString(ALL_URNS));
      }
      Schema byName = Schema.find("name", schema.name).firstResult();
      if (byName != null) {
         if (Objects.equals(schema.id, byName.id)) {
            em.merge(schema);
         } else {
            throw ServiceException.serverError("Name already used");
         }
      } else {
         schema.id = null; //remove the id so we don't override an existing entry
         em.persist(schema);
      }
      em.flush();//manually flush to validate constraints
      return schema.id;
   }

   @PermitAll
   @WithRoles
   @Override
   public List<Schema> list(Integer limit, Integer page, String sort, SortDirection direction) {
      if (sort == null || sort.isEmpty()) {
         sort = "name";
      }
      Sort.Direction sortDirection = direction == null ? null : Sort.Direction.valueOf(direction.name());
      if (limit != null && page != null) {
         return Schema.findAll(Sort.by(sort).direction(sortDirection)).page(Page.of(page, limit)).list();
      } else {
         return Schema.listAll(Sort.by(sort).direction(sortDirection));
      }
   }

   @SuppressWarnings({ "deprecation", "unchecked" })
   @WithRoles
   @Override
   public List<SchemaDescriptor> descriptors(List<Integer> ids) {
      String sql = "SELECT id, name, uri FROM schema";
      if (ids != null && !ids.isEmpty()) {
         sql += " WHERE id IN ?1";
      }
      Query query = em.createNativeQuery(sql);
      if (ids != null && !ids.isEmpty()) {
         query.setParameter(1, ids);
      }
      return query.unwrap(org.hibernate.query.Query.class)
            .setResultTransformer(DESCRIPTOR_TRANSFORMER).getResultList();
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

   public String updateToken(int id, String token) {
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
   public void updateAccess(int id,
                            String owner,
                            int access) {
      if (access < Access.PUBLIC.ordinal() || access > Access.PRIVATE.ordinal()) {
         throw ServiceException.badRequest("Access not within bounds");
      }
      Query query = em.createNativeQuery(CHANGE_ACCESS);
      query.setParameter(1, owner);
      query.setParameter(2, access);
      query.setParameter(3, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Access change failed (missing permissions?)");
      }
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void validateRunData(int runId, Predicate<String> schemaFilter) {
      log.infof("About to validate data for run %d", runId);
      Run run = Run.findById(runId);
      if (run == null) {
         log.errorf("Cannot load run %d for schema validation", runId);
         return;
      }
      run.validationErrors.removeIf(e -> schemaFilter == null || schemaFilter.test(e.schema.uri));
      validateData(run.data, schemaFilter, run.validationErrors::add, true);
      run.persist();
      Util.publishLater(tm, vertx.eventBus(), Run.EVENT_VALIDATED, new Schema.ValidationEvent(run.id, run.validationErrors));
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void validateDatasetData(int datasetId, Predicate<String> schemaFilter) {
      log.infof("About to validate data for dataset %d", datasetId);
      DataSet dataset = DataSet.findById(datasetId);
      if (dataset == null) {
         // Don't log error when the dataset is not present and we're revalidating all datasets - it might be
         // concurrently removed because of URI change
         if (schemaFilter != null) {
            log.errorf("Cannot load dataset %d for schema validation", datasetId);
         }
         return;
      }
      dataset.validationErrors.removeIf(e -> schemaFilter == null || schemaFilter.test(e.schema.uri));
      validateData(dataset.data, schemaFilter, dataset.validationErrors::add, false);
      for (var item : dataset.data) {
         String uri = item.path("$schema").asText();
         if (uri == null || uri.isBlank()) {
            ValidationError error = new ValidationError();
            error.error = JsonNodeFactory.instance.objectNode().put("type", "No schema").put("message", "Element in the dataset does not reference any schema through the '$schema' property.");
            dataset.validationErrors.add(error);
         }
      }
      dataset.persist();
      Util.publishLater(tm, vertx.eventBus(), DataSet.EVENT_VALIDATED, new Schema.ValidationEvent(dataset.id, dataset.validationErrors));
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   @TransactionConfiguration(timeout = 3600) // 1 hour, this may run a long time
   void revalidateAll(String params) {
      int schemaId = Integer.parseInt(params);
      Schema schema = Schema.findById(schemaId);
      if (schema == null) {
         log.errorf("Cannot load schema %d for validation", schemaId);
         return;
      }
      Predicate<String> schemaFilter = uri -> uri.equals(schema.uri);
      // If the URI was updated together with JSON schema run_schemas are removed and filled-in asynchronously
      // so we cannot rely on run_schemas
      runService.findRunsWithUri(schema.uri, runId -> {
         Util.executeBlocking(vertx, CachedSecurityIdentity.ANONYMOUS, () -> validateRunData(runId, schemaFilter));
      });
      // Datasets might be re-created if URI is changing, so we might work on old, non-existent ones
      ScrollableResults results = Util.scroll(em.createNativeQuery("SELECT id FROM dataset WHERE ?1 IN (SELECT jsonb_array_elements(data)->>'$schema')").setParameter(1, schema.uri));
      while (results.next()) {
         int datasetId = (int) results.get(0);
         Util.executeBlocking(vertx, CachedSecurityIdentity.ANONYMOUS, () -> validateDatasetData(datasetId, schemaFilter));
      }
   }

   @ConsumeEvent(Run.EVENT_VALIDATED)
   void consumeValidation(Schema.ValidationEvent e) {
      // just to let Quarkus register codec for ValidationEvent
   }

   private void validateData(JsonNode data, Predicate<String> filter, Consumer<ValidationError> consumer, boolean allowUnknownSchema) {
      Map<String, List<JsonNode>> toCheck = new HashMap<>();
      addIfHasSchema(toCheck, data);
      for (JsonNode child : data) {
         addIfHasSchema(toCheck, child);
      }

      for (String schemaUri: toCheck.keySet()) {
         if (filter != null && !filter.test(schemaUri)) {
            continue;
         }
         Query fetchSchemas = em.createNativeQuery(FETCH_SCHEMAS_RECURSIVE, Schema.class);
         fetchSchemas.setParameter(1, schemaUri);
         @SuppressWarnings("unchecked")
         Map<String, Schema> schemas = ((Stream<Schema>) fetchSchemas.getResultStream())
               .collect(Collectors.toMap(s -> s.uri, Function.identity()));

         // this is root in the sense of JSON schema referencing other schemas, NOT Horreum first-level schema
         Schema rootSchema = schemas.get(schemaUri);
         if (rootSchema == null || rootSchema.schema == null) {
            if (!allowUnknownSchema) {
               ValidationError error = new ValidationError();
               error.error = JsonNodeFactory.instance.objectNode().put("type", "Schema not defined").put("message", "Schema '" + schemaUri + "' is not defined in Horreum");
               consumer.accept(error);
            }
            continue;
         }
         try {
            URIFetcher uriFetcher = uri -> {
               byte[] jsonSchema = schemas.get(uri.toString()).schema.toString().getBytes(StandardCharsets.UTF_8);
               return new ByteArrayInputStream(jsonSchema);
            };

            JsonSchemaFactory factory = JsonSchemaFactory.builder(JSON_SCHEMA_FACTORY)
                  .uriFactory(URN_FACTORY, "urn", "uri")
                  .uriFetcher(uriFetcher, ALL_URNS).build();

            for (JsonNode node : toCheck.get(schemaUri)) {
               factory.getSchema(rootSchema.schema).validate(node).forEach(msg -> {
                  ValidationError error = new ValidationError();
                  error.schema = rootSchema;
                  error.error = Util.OBJECT_MAPPER.valueToTree(msg);
                  consumer.accept(error);
               });
            }
         } catch (Throwable e) {
            // Do not let messed up schemas fail the upload
            log.error("Schema validation failed", e);
            ValidationError error = new ValidationError();
            error.schema = rootSchema;
            error.error = JsonNodeFactory.instance.objectNode().put("type", "Execution error").put("message", e.getMessage());
            consumer.accept(error);
         }
         log.info("Validation completed");
      }
   }

   private void addIfHasSchema(Map<String, List<JsonNode>> toCheck, JsonNode node) {
      String uri = node.path("$schema").asText();
      if (uri != null && !uri.isBlank()) {
         toCheck.computeIfAbsent(uri, u -> new ArrayList<>()).add(node);
      }
   }

   @RolesAllowed("tester")
   @WithRoles
   @Transactional
   @Override
   public void delete(int id){
      Schema schema = Schema.find("id", id).firstResult();
      if (schema == null) {
         throw ServiceException.notFound("Schema not found");
      } else {
         em.createNativeQuery("DELETE FROM label_extractors WHERE label_id IN (SELECT id FROM label WHERE schema_id = ?1)")
               .setParameter(1, id).executeUpdate();
         Label.delete("schema_id", id);
         Transformer.delete("schema_id", id);
         schema.delete();
      }
   }

   @PermitAll
   @WithRoles
   @Override
   public List<LabelLocation> findUsages(String label) {
      if (label == null) {
         throw ServiceException.badRequest("No label");
      }
      label = label.trim();
      List<LabelLocation> result = new ArrayList<>();
      for (Object row: em.createNativeQuery("SELECT id, name FROM test WHERE json_contains(fingerprint_labels, ?1)")
            .setParameter(1, label).getResultList()) {
         Object[] columns = (Object[]) row;
         result.add(new LabelInFingerprint((int) columns[0], (String) columns[1]));
      }
      for (Object row: em.createNativeQuery("SELECT test.id as testid, test.name as testname, mdr.id, mdr.name FROM missingdata_rule mdr JOIN test ON mdr.test_id = test.id WHERE json_contains(mdr.labels, ?1)")
            .setParameter(1, label).getResultList()) {
         Object[] columns = (Object[]) row;
         result.add(new LabelInRule((int) columns[0], (String) columns[1], (int) columns[2], (String) columns[3]));
      }
      for (Object row: em.createNativeQuery("SELECT test.id as testid, test.name as testname, v.id as varid, v.name as varname FROM variable v " +
            "JOIN test ON test.id = v.testid WHERE json_contains(v.labels, ?1)")
            .setParameter(1, label).getResultList()) {
         Object[] columns = (Object[]) row;
         result.add(new LabelInVariable((int) columns[0], (String) columns[1], (int) columns[2], (String) columns[3]));
      }
      for (Object row: em.createNativeQuery("SELECT test.id as testid, test.name as testname, view.id as viewid, view.name as viewname, vc.id as componentid, vc.headername FROM viewcomponent vc " +
            "JOIN view ON vc.view_id = view.id JOIN test ON test.id = view.test_id WHERE json_contains(vc.labels, ?1)")
            .setParameter(1, label).getResultList()) {
         Object[] columns = (Object[]) row;
         result.add(new LabelInView((int) columns[0], (String) columns[1], (int) columns[2], (String) columns[3], (int) columns[4], (String) columns[5]));
      }
      for (Object row: em.createNativeQuery("SELECT test.id as testid, test.name as testname, trc.id as configid, trc.title, " +
            "filterlabels, categorylabels, serieslabels, scalelabels FROM tablereportconfig trc JOIN test ON test.id = trc.testid " +
            "WHERE json_contains(filterlabels, ?1) OR json_contains(categorylabels, ?1) OR json_contains(serieslabels, ?1) OR json_contains(scalelabels, ?1);")
            .setParameter(1, label).unwrap(NativeQuery.class)
            .addScalar("testid", IntegerType.INSTANCE)
            .addScalar("testname", TextType.INSTANCE)
            .addScalar("configid", IntegerType.INSTANCE)
            .addScalar("title", TextType.INSTANCE)
            .addScalar("filterlabels", JsonNodeBinaryType.INSTANCE)
            .addScalar("categorylabels", JsonNodeBinaryType.INSTANCE)
            .addScalar("serieslabels", JsonNodeBinaryType.INSTANCE)
            .addScalar("scalelabels", JsonNodeBinaryType.INSTANCE)
            .getResultList()) {
         Object[] columns = (Object[]) row;
         StringBuilder where = new StringBuilder();
         addPart(where, (ArrayNode) columns[4], label, "filter");
         addPart(where, (ArrayNode) columns[5], label, "series");
         addPart(where, (ArrayNode) columns[6], label, "category");
         addPart(where, (ArrayNode) columns[7], label, "label");
         result.add(new LabelInReport((int) columns[0], (String) columns[1], (int) columns[2], (String) columns[3], where.toString(), null));
      }
      for (Object row: em.createNativeQuery("SELECT test.id as testid, test.name as testname, trc.id as configid, trc.title, rc.name FROM reportcomponent rc " +
            "JOIN tablereportconfig trc ON rc.reportconfig_id = trc.id JOIN test ON test.id = trc.testid " +
            "WHERE json_contains(rc.labels, ?1)")
            .setParameter(1, label).getResultList()) {
         Object[] columns = (Object[]) row;
         result.add(new LabelInReport((int) columns[0], (String) columns[1], (int) columns[2], (String) columns[3], "component", (String) columns[4]));
      }
      return result;
   }

   @PermitAll
   @WithRoles
   @Override
   public List<Transformer> listTransformers(int schemaId) {
      return Transformer.find("schema_id", Sort.by("name"), schemaId).list();
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public int addOrUpdateTransformer(int schemaId, Transformer transformer) {
      if (!identity.hasRole(transformer.owner)) {
         throw ServiceException.forbidden("This user is not a member of team " + transformer.owner);
      }
      if (transformer.extractors == null) {
         // Transformer without an extractor is an edge case, but replacing the schema with explicit null/undefined could make sense.
         transformer.extractors = Collections.emptyList();
      }
      if (transformer.name == null || transformer.name.isBlank()) {
         throw ServiceException.badRequest("Transformer must have a name!");
      }
      validateExtractors(transformer.extractors);
      if (transformer.id == null || transformer.id < 0) {
         transformer.id = null;
         transformer.schema = em.getReference(Schema.class, schemaId);
         transformer.persistAndFlush();
      } else {
         Transformer existing = Transformer.findById(transformer.id);
         if (!Objects.equals(existing.schema.id, schemaId)) {
            throw ServiceException.badRequest("Transformer id=" + transformer.id + ", name=" + existing.name +
                  " belongs to a different schema: " + existing.schema.id + "(" + existing.schema.uri + ")");
         }
         if (!identity.hasRole(existing.owner)) {
            throw ServiceException.forbidden("Cannot transfer ownership: this user is not a member of team " + existing.owner);
         }
         existing.name = transformer.name;
         existing.description = transformer.description;
         existing.owner = transformer.owner;
         existing.access = transformer.access;
         existing.targetSchemaUri = transformer.targetSchemaUri;
         existing.function = transformer.function;
         existing.extractors.clear();
         existing.extractors.addAll(transformer.extractors);
         existing.persist();
      }
      return transformer.id;
   }

   private void validateExtractors(Collection<Extractor> extractors) {
      for (Extractor extractor : extractors) {
         if (extractor.name == null || extractor.name.isBlank()) {
            throw ServiceException.badRequest("One of the extractors does not have a name!");
         } else if (extractor.jsonpath == null || extractor.jsonpath.isBlank()) {
            throw ServiceException.badRequest("One of the extractors is missing JSONPath!");
         }
      }
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public void deleteTransformer(int schemaId, int transformerId) {
      Transformer t = Transformer.findById(transformerId);
      if (t == null) {
         throw ServiceException.notFound("Transformer " + transformerId + " not found");
      }
      if (t.schema.id != schemaId) {
         throw ServiceException.badRequest("Transformer " + transformerId + " does not belong to schema " + schemaId);
      }
      String testerRole = t.owner.substring(0, t.owner.length() - 5) + "-tester";
      if (!identity.hasRole(testerRole)) {
         throw ServiceException.forbidden("You are not an owner of transfomer " + transformerId + "(" + t.owner + "); missing role " + testerRole + ", available roles: " + identity.getRoles());
      }
      @SuppressWarnings("unchecked") List<Object[]> testsUsingTransformer =
            em.createNativeQuery("SELECT test.id, test.name FROM test_transformers JOIN test ON test_id = test.id WHERE transformer_id = ?1")
            .setParameter(1, transformerId).getResultList();
      if (!testsUsingTransformer.isEmpty()) {
         throw ServiceException.badRequest("This transformer is still referenced in some tests: " +
         testsUsingTransformer.stream().map(row -> {
            int id = (int) row[0];
            String name = (String) row[1];
            return "<a href=\"/test/" + id + "\">" + name + "</a>";
         }).collect(Collectors.joining(", ")) + "; please remove them before deleting it.");
      }
      t.delete();
   }

   @WithRoles
   @Override
   public List<Label> labels(int schemaId) {
      return Label.find("schema_id", schemaId).list();
   }

   @WithRoles
   @Transactional
   @Override
   public Integer addOrUpdateLabel(int schemaId, Label label) {
      if (label == null) {
         throw ServiceException.badRequest("No label?");
      }
      if (!identity.hasRole(label.owner)) {
         throw ServiceException.forbidden("This user is not a member of team " + label.owner);
      }
      if (label.name == null || label.name.isBlank()) {
         throw ServiceException.badRequest("Label must have a non-blank name");
      }
      validateExtractors(label.extractors);

      if (label.id == null || label.id < 0) {
         label.id = null;
         label.schema = em.getReference(Schema.class, schemaId);
         checkSameName(label);
         label.persistAndFlush();
      } else {
         Label existing = Label.findById(label.id);
         if (!Objects.equals(existing.schema.id, schemaId)) {
            throw ServiceException.badRequest("Label id=" + label.id + ", name=" + existing.name +
                  " belongs to a different schema: " + existing.schema.id + "(" + existing.schema.uri + ")");
         }
         if (!identity.hasRole(existing.owner)) {
            throw ServiceException.forbidden("Cannot transfer ownership: this user is not a member of team " + existing.owner);
         }
         if (!existing.name.equals(label.name)) {
            checkSameName(label);
         }
         existing.name = label.name;
         existing.extractors.clear();
         existing.extractors.addAll(label.extractors);
         existing.function = label.function;
         existing.owner = label.owner;
         existing.access = label.access;
         existing.filtering = label.filtering;
         existing.metrics = label.metrics;
         existing.persistAndFlush();
      }
      return label.id;
   }

   private void checkSameName(Label label) {
      Label sameName = Label.find("schema = ?1 AND name = ?2", label.schema, label.name).firstResult();
      if (sameName != null) {
         throw ServiceException.badRequest("There is an existing label with the same name (" + label.name + ") in this schema; please choose different name.");
      }
   }

   @WithRoles
   @Transactional
   @Override
   public void deleteLabel(int schemaId, int labelId) {
      Label label = Label.findById(labelId);
      if (label == null) {
         throw ServiceException.notFound("Label " + labelId + " not found");
      }
      if (label.schema.id != schemaId) {
         throw ServiceException.badRequest("Label " + labelId + " does not belong to schema " + schemaId);
      }
      String testerRole = label.owner.substring(0, label.owner.length() - 5) + "-tester";
      if (!identity.hasRole(testerRole)) {
         throw ServiceException.forbidden("You are not an owner of label " + labelId + "(" + label.owner + "); missing role " + testerRole + ", available roles: " + identity.getRoles());
      }
      label.delete();
   }

   @PermitAll
   @WithRoles
   @Override
   public Collection<LabelInfo> allLabels(String filterName) {
      String sqlQuery = "SELECT label.name, label.metrics, label.filtering, schema_id, schema.name as schemaName, schema.uri FROM label JOIN schema ON schema.id = label.schema_id";
      if (filterName != null && !filterName.isBlank()) {
         sqlQuery += " WHERE label.name = ?1";
      }
      Query query = em.createNativeQuery(sqlQuery);
      if (filterName != null) {
         query.setParameter(1, filterName.trim());
      }
      @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
      Map<String, LabelInfo> labels = new TreeMap<>();
      for (Object[] row : rows) {
         String name = (String) row[0];
         LabelInfo info = labels.computeIfAbsent(name, LabelInfo::new);
         info.metrics = info.metrics || (boolean) row[1];
         info.filtering = info.filtering || (boolean) row[2];
         int schemaId = (int) row[3];
         String schemaName = (String) row[4];
         String uri = (String) row[5];
         info.schemas.add(new SchemaDescriptor(schemaId, schemaName, uri));
      }
      return labels.values();
   }

   @PermitAll
   @WithRoles
   @Override
   public List<TransformerInfo> allTransformers() {
      List<TransformerInfo> transformers = new ArrayList<>();
      @SuppressWarnings("unchecked") List<Object[]> rows = em.createNativeQuery(
            "SELECT s.id as sid, s.uri, s.name as schemaName, t.id as tid, t.name as transformerName FROM schema s JOIN transformer t ON s.id = t.schema_id").getResultList();
      for (Object[] row: rows) {
         TransformerInfo info = new TransformerInfo();
         info.schemaId = (int) row[0];
         info.schemaUri = (String) row[1];
         info.schemaName = (String) row[2];
         info.transformerId = (int) row[3];
         info.transformerName = (String)  row[4];
         transformers.add(info);
      }
      return transformers;
   }

   private void addPart(StringBuilder where, ArrayNode column, String label, String type) {
      if (StreamSupport.stream(column.spliterator(), false).map(JsonNode::asText).anyMatch(label::equals)) {
         if (where.length() > 0) {
            where.append(", ");
         }
         where.append(type);
      }
   }
}
