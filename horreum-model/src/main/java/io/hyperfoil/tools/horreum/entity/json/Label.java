package io.hyperfoil.tools.horreum.entity.json;

import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;

@Entity(name="label")
@RegisterForReflection
public class Label extends OwnedEntityBase {
   @JsonProperty(required = true)
   @Id
   @SequenceGenerator(
         name="labelSequence",
         sequenceName="label_id_seq",
         allocationSize=1)
   @GeneratedValue(strategy=GenerationType.SEQUENCE,
         generator= "labelSequence")
   public Integer id;

   @NotNull
   public String name;

   @ManyToOne(optional = false)
   @JoinColumn(name = "schema_id")
   @JsonIgnore
   public Schema schema;

   @NotNull
   @ElementCollection(fetch = FetchType.EAGER)
   @CollectionTable(name = "label_extractors")
   public Collection<Extractor> extractors;

   public String function;

   @NotNull
   public boolean filtering = true;

   @NotNull
   public boolean metrics = true;

   @JsonProperty(value = "schemaId", required = true)
   public int getSchemaId() {
      return schema.id;
   }

   @JsonProperty("schemaId")
   public void setSchema(int schemaId) {
      schema = Schema.getEntityManager().getReference(Schema.class, schemaId);
   }

   @Entity
   @Table(name = "label_values")
   public static class Value extends PanacheEntityBase implements Serializable {
      @Id
      @NotNull
      @Column(name = "dataset_id")
      public int datasetId;

      @Id
      @NotNull
      @Column(name = "label_id")
      public int labelId;

      @Type(type = "io.hyperfoil.tools.horreum.entity.converter.JsonUserType")
      public JsonNode value;

      @Override
      public boolean equals(Object o) {
         if (this == o) return true;
         if (o == null || getClass() != o.getClass()) return false;
         Value value1 = (Value) o;
         return datasetId == value1.datasetId && labelId == value1.labelId && Objects.equals(value, value1.value);
      }

      @Override
      public int hashCode() {
         return Objects.hash(datasetId, labelId, value);
      }

      @Override
      public String toString() {
         return "Value{" +
               "datasetId=" + datasetId +
               ", labelId=" + labelId +
               ", value=" + value +
               '}';
      }
   }
}
