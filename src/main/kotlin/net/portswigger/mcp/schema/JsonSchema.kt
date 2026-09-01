package net.portswigger.mcp.schema

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

private fun opaqueObject(): JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object")))

fun getJsonSchemaForProperty(kType: KType): JsonElement = getJsonSchemaForProperty(kType, emptySet())

private fun getJsonSchemaForProperty(kType: KType, seen: Set<KClass<*>>): JsonElement {
    return when (kType.classifier) {
        String::class ->
            JsonObject(mapOf("type" to JsonPrimitive("string")))

        Int::class, Long::class ->
            JsonObject(mapOf("type" to JsonPrimitive("integer")))

        Float::class, Double::class ->
            JsonObject(mapOf("type" to JsonPrimitive("number")))

        Boolean::class ->
            JsonObject(mapOf("type" to JsonPrimitive("boolean")))

        List::class, Array::class -> {
            val argType = kType.arguments.firstOrNull()?.type
            val itemsSchema = when {
                argType != null -> getJsonSchemaForProperty(argType, seen)
                else -> opaqueObject()
            }
            JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to itemsSchema))
        }

        Map::class -> {
            val valueType = kType.arguments.getOrNull(1)?.type
            val valueSchema = when {
                valueType != null -> getJsonSchemaForProperty(valueType, seen)
                else -> opaqueObject()
            }
            JsonObject(mapOf("type" to JsonPrimitive("object"), "additionalProperties" to valueSchema))
        }

        else -> {
            val kClass = kType.classifier as? KClass<*> ?: return opaqueObject()
            when {
                kClass.java.isEnum -> JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "enum" to JsonArray(
                            kClass.java.enumConstants.map { JsonPrimitive((it as Enum<*>).name) }
                        )
                    )
                )

                // Guard against self-referencing types; describing one level is enough.
                kClass in seen -> opaqueObject()

                kClass.isData -> kClass.describeObject(seen + kClass)

                else -> opaqueObject()
            }
        }
    }
}

/**
 * Names of constructor parameters that carry a default value. These are optional on the wire even
 * though the property type is non-nullable, so they must not be reported as required.
 */
private fun KClass<*>.defaultedParameterNames(): Set<String> =
    runCatching {
        primaryConstructor?.parameters.orEmpty()
            .filter { it.isOptional }
            .mapNotNull { it.name }
            .toSet()
    }.getOrDefault(emptySet())

private fun KClass<*>.schemaFields(seen: Set<KClass<*>>): Pair<Map<String, JsonElement>, List<String>> {
    val properties = mutableMapOf<String, JsonElement>()
    val required = mutableListOf<String>()
    val defaulted = defaultedParameterNames()

    for (prop in memberProperties) {
        properties[prop.name] = getJsonSchemaForProperty(prop.returnType, seen)

        if (!prop.returnType.isMarkedNullable && prop.name !in defaulted) {
            required.add(prop.name)
        }
    }

    return properties to required
}

/**
 * Full JSON Schema for a nested data class, so clients can discover its fields instead of being
 * handed a bare `{"type":"object"}`. Unknown keys are rejected by the deserializer, which
 * `additionalProperties: false` makes visible up front.
 */
private fun KClass<*>.describeObject(seen: Set<KClass<*>>): JsonObject {
    val (properties, required) = schemaFields(seen)

    return JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(properties),
            "required" to JsonArray(required.map { JsonPrimitive(it) }),
            "additionalProperties" to JsonPrimitive(false)
        )
    )
}

fun KClass<*>.asInputSchema(): ToolSchema {
    val (properties, required) = schemaFields(setOf(this))

    return ToolSchema(
        properties = JsonObject(properties),
        required = required
    )
}
