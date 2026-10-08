package dev.mstaszew.campaign.common

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bson.Document
import org.springframework.data.convert.PropertyValueConversions
import org.springframework.data.convert.PropertyValueConverter
import org.springframework.data.mapping.PersistentProperty

/**
 * Property-value conversion for every JsonNode field (salary, evidence,
 * followUps, EventEntity.record, JobListingEntity.raw).
 *
 * This went through a property-VALUE converter and not the plain custom
 * conversions used at first, for a reason the Mongo docs do not spell out:
 * custom Converter<Object, JsonNode> conversions are consulted by Spring Data
 * per stored element, never with the array itself, so a stored
 * ["gmail_auto_reply"] came back as a TextNode wrapping the first element and
 * the round-trip tests failed. A property converter owns the property as a
 * whole, arrays included, on both the read and the write side.
 *
 * Stored shapes are the driver's own Document/list/scalars, so "salary.min" is
 * a queryable path rather than an opaque string. The object/number/boolean
 * scalar mapping keeps the import-keyed null-safety of the old Postgres jsonb
 * storage while allowing the tracker's bare-string "salarySeen".
 */
class JsonNodePropertyValueConversions : PropertyValueConversions {

    private val mapper = ObjectMapper()

    val jsonNodeConverter = object : PropertyValueConverter<JsonNode, Any, org.springframework.data.convert.ValueConversionContext<org.springframework.data.mongodb.core.mapping.MongoPersistentProperty>> {
        override fun read(
            value: Any,
            context: org.springframework.data.convert.ValueConversionContext<org.springframework.data.mongodb.core.mapping.MongoPersistentProperty>,
        ): JsonNode = mapper.valueToTree(value)

        override fun write(
            value: JsonNode,
            context: org.springframework.data.convert.ValueConversionContext<org.springframework.data.mongodb.core.mapping.MongoPersistentProperty>,
        ): Any? = toBson(value, mapper)
    }

    override fun hasValueConverter(property: PersistentProperty<*>): Boolean =
        property.type == JsonNode::class.java

    override fun <DV, SV, P : PersistentProperty<P>, VCC : org.springframework.data.convert.ValueConversionContext<P>> getValueConverter(
        property: P,
    ): PropertyValueConverter<DV, SV, VCC> {
        @Suppress("UNCHECKED_CAST")
        return jsonNodeConverter as PropertyValueConverter<DV, SV, VCC>
    }

    companion object {
        fun toBson(source: JsonNode, mapper: ObjectMapper): Any? = when {
            source.isNull -> null
            source.isObject -> Document.parse(source.toString())
            source.isArray -> mapper.convertValue(source, List::class.java)
            source.isNumber -> source.decimalValue()
            source.isBoolean -> source.booleanValue()
            else -> source.asText()
        }
    }
}