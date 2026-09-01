package net.portswigger.mcp.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.tools.AnnotateBurpEvidence
import net.portswigger.mcp.tools.ListBurpEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonSchemaTest {

    @Test
    fun `nested data class in a list is described field by field`() {
        val schema = AnnotateBurpEvidence::class.asInputSchema()
        val annotations = schema.properties!!["annotations"] as JsonObject

        assertEquals(JsonPrimitive("array"), annotations["type"])

        val items = annotations["items"] as JsonObject
        assertEquals(JsonPrimitive("object"), items["type"])

        val properties = items["properties"] as JsonObject
        assertTrue("type" in properties, "annotation item should expose its own 'type' field")
        assertTrue("x" in properties, "annotation item should expose 'x'")
        assertTrue("strokeWidth" in properties, "annotation item should expose 'strokeWidth'")
        assertTrue("endX" in properties, "annotation item should expose 'endX'")

        assertEquals(JsonPrimitive("integer"), (properties["x"] as JsonObject)["type"])
        assertEquals(JsonPrimitive("string"), (properties["text"] as JsonObject)["type"])
    }

    @Test
    fun `unknown keys are advertised as rejected`() {
        val schema = AnnotateBurpEvidence::class.asInputSchema()
        val items = (schema.properties!!["annotations"] as JsonObject)["items"] as JsonObject

        assertEquals(JsonPrimitive(false), items["additionalProperties"])
    }

    @Test
    fun `only fields without a default are required`() {
        val schema = AnnotateBurpEvidence::class.asInputSchema()
        val items = (schema.properties!!["annotations"] as JsonObject)["items"] as JsonObject
        val required = (items["required"] as JsonArray).map { (it as JsonPrimitive).content }

        assertEquals(setOf("type", "x", "y"), required.toSet())
        assertFalse("strokeWidth" in required, "strokeWidth has a default and must not be required")
        assertFalse("color" in required, "color has a default and must not be required")
    }

    @Test
    fun `top level defaults are not required either`() {
        val schema = ListBurpEvidence::class.asInputSchema()

        assertFalse("limit" in schema.required.orEmpty(), "limit defaults to 20 and must not be required")
    }
}
