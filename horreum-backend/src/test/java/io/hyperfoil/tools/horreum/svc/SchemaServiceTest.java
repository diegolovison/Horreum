package io.hyperfoil.tools.horreum.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.hyperfoil.tools.horreum.entity.json.DataSet;
import io.hyperfoil.tools.horreum.entity.json.Run;
import io.hyperfoil.tools.horreum.entity.json.Schema;
import io.hyperfoil.tools.horreum.entity.json.Test;
import io.hyperfoil.tools.horreum.test.NoGrafanaProfile;
import io.hyperfoil.tools.horreum.test.PostgresResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(NoGrafanaProfile.class)
public class SchemaServiceTest extends BaseServiceTest {
   @org.junit.jupiter.api.Test
   public void testValidateRun() throws IOException, InterruptedException {
      JsonNode allowAny = load("/allow-any.json");
      Schema allowAnySchema = createSchema("any", allowAny.path("$id").asText(), allowAny);
      JsonNode allowNone = load("/allow-none.json");
      Schema allowNoneSchema = createSchema("none", allowNone.path("$id").asText(), allowNone);

      BlockingQueue<Schema.ValidationEvent> runValidations = eventConsumerQueue(Schema.ValidationEvent.class, Run.EVENT_VALIDATED);
      BlockingQueue<Schema.ValidationEvent> datasetValidations = eventConsumerQueue(Schema.ValidationEvent.class, DataSet.EVENT_VALIDATED);

      Test test = createTest(createExampleTest("schemaTest"));
      ArrayNode data = JsonNodeFactory.instance.arrayNode();
      data.addObject().put("$schema", allowAnySchema.uri).put("foo", "bar");
      data.addObject().put("$schema", allowNoneSchema.uri).put("foo", "bar");
      data.addObject().put("$schema", "urn:unknown:schema").put("foo", "bar");
      int runId = uploadRun(data.toString(), test.name);

      Schema.ValidationEvent runValidation = runValidations.poll(10, TimeUnit.SECONDS);
      assertNotNull(runValidation);
      assertEquals(runId, runValidation.id);
      // one error for extra "foo" and one for "$schema"
      assertEquals(2, runValidation.errors.size());
      runValidation.errors.forEach(e -> {
         assertEquals(allowNoneSchema.id, e.schema.id);
         assertNotNull(e.error);
      });

      Schema.ValidationEvent dsValidation = datasetValidations.poll(10, TimeUnit.SECONDS);
      assertNotNull(dsValidation);
      assertEquals(2, dsValidation.errors.size());
      dsValidation.errors.forEach(e -> {
         assertEquals(allowNoneSchema.id, e.schema.id);
         assertNotNull(e.error);
      });
      assertEquals(0, runValidations.drainTo(new ArrayList<>()));
      assertEquals(0, datasetValidations.drainTo(new ArrayList<>()));

      allowAnySchema.schema = allowNone.deepCopy();
      ((ObjectNode) allowAnySchema.schema).set("$id", allowAny.path("$id").deepCopy());
      addOrUpdateSchema(allowAnySchema);

      Schema.ValidationEvent runValidation2 = runValidations.poll(10, TimeUnit.SECONDS);
      assertNotNull(runValidation2);
      assertEquals(runId, runValidation2.id);
      // This time we get errors for both schemas
      assertEquals(4, runValidation2.errors.size());

      Schema.ValidationEvent datasetValidation2 = datasetValidations.poll(10, TimeUnit.SECONDS);
      assertNotNull(datasetValidation2);
      assertEquals(4, datasetValidation2.errors.size());

      assertEquals(4, em.createNativeQuery("SELECT COUNT(*)::::int FROM run_validationerrors").getSingleResult());
      assertEquals(4, em.createNativeQuery("SELECT COUNT(*)::::int FROM dataset_validationerrors").getSingleResult());
   }

   private static JsonNode load(String resource) throws IOException {
      try (InputStream stream = SchemaServiceTest.class.getResourceAsStream(resource)) {
         return Util.OBJECT_MAPPER.reader().readValue(stream, JsonNode.class);
      }
   }
}
