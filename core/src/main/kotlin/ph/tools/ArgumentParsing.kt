package ph.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Tool-argument parsing shared by [BashTool] and [DefaultToolDispatcher]. Arguments arrive as the
 * model's raw JSON string and are never re-serialised; a parse that fails is a model-visible
 * `BAD_ARGUMENT` value, not an exception (SPEC §2.4).
 *
 * The messages name the offending field, so the model can correct itself in one step.
 */
object ArgumentParsing {

    /** The `bash` tool's one parameter (SPEC §2.4). */
    const val COMMAND_FIELD = "command"

    private val json = Json

    /** The arguments object, or `null` when [argumentsJson] is not valid JSON or is not an object. */
    fun objectOrNull(argumentsJson: String): JsonObject? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject
    }.getOrNull()

    /** A non-blank string field, or `null` when absent, blank, or not a JSON string. */
    fun stringField(args: JsonObject, field: String): String? =
        (args[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** [COMMAND_FIELD] from raw arguments, or `null` when it is missing or unusable. */
    fun commandOrNull(argumentsJson: String): String? =
        objectOrNull(argumentsJson)?.let { stringField(it, COMMAND_FIELD) }

    fun invalidArgumentsMessage(field: String = COMMAND_FIELD): String =
        "invalid arguments JSON: expected an object with a '$field' field"

    fun missingFieldMessage(field: String = COMMAND_FIELD): String =
        "missing required field '$field'"
}
