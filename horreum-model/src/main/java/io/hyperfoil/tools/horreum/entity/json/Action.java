package io.hyperfoil.tools.horreum.entity.json;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;

import javax.persistence.*;
import javax.validation.constraints.NotNull;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Entity
@RegisterForReflection
public class Action extends PanacheEntityBase {
   @JsonProperty(required = true)
   @Id
   @SequenceGenerator(
      name = "actionSequence",
      sequenceName = "action_id_seq",
      allocationSize = 1,
      initialValue = 1)
   @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "actionSequence")
   public Integer id;

   @NotNull
   @Column(name = "event")
   public String event;

   @NotNull
   @Column(name = "type")
   public String type;

   @NotNull
   @Type(type = "io.hyperfoil.tools.horreum.entity.converter.JsonUserType")
   @Column(name = "config")
   public JsonNode config;

   @NotNull
   @Type(type = "io.hyperfoil.tools.horreum.entity.converter.JsonUserType")
   @Column(name = "secrets")
   @JsonIgnore
   public JsonNode secrets;

   @NotNull
   @Column(name = "test_id")
   public Integer testId;

   @NotNull
   @Transient
   public boolean active = true;

   @JsonProperty("secrets")
   public void setSecrets(JsonNode secrets) {
      this.secrets = secrets;
   }

   // Had we called this simply `getSecrets` Quarkus would rewrite (??!!) some property
   // accesses to use of that method
   @JsonProperty("secrets")
   public JsonNode getMaskedSecrets() {
      if (secrets != null && secrets.isObject()) {
         ObjectNode masked = JsonNodeFactory.instance.objectNode();
         secrets.fieldNames().forEachRemaining(name -> {
            masked.put(name, "********");
         });
         return masked;
      } else {
         return JsonNodeFactory.instance.objectNode();
      }
   }
}
