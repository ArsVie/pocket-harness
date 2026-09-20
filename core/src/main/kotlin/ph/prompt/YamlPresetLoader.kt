package ph.prompt

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import ph.model.ModelRoute
import java.io.File
import java.io.IOException

/**
 * A loud configuration failure (SPEC §2.5/§3). Preset loading is startup, not a model-visible path,
 * so it does not use the "errors are values" rule: a missing, unreadable or unparsable preset, or one
 * that forgot a required key, must never silently fall back to defaults.
 */
class PresetLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Reads a preset YAML document into [Preset].
 *
 * The model *route* is deliberately **not** read from the file (ADR-003 §6): `baseUrl`, `model`,
 * `apiKeyRef` and `reasoningEfforts` are user settings and are passed in by the caller and placed on
 * the returned [Preset]. A file that happens to carry route keys is simply not asked for them.
 *
 * Required keys: `id`, `persona`, `complete`, `includeRuntimeContext`, `tools`.
 *
 * `budgets` and `loop` are OPTIONAL sub-trees, and the distinction is explicit here:
 *  - absent entirely  -> the [Budgets] / [LoopConfig] data-class defaults are used;
 *  - present but missing one of its fields -> loud failure. A field the file forgot is never quietly
 *    filled in with a default.
 */
class YamlPresetLoader {

    fun load(file: File, route: ModelRoute): Preset {
        val source = file.path
        val text = try {
            file.readText()
        } catch (e: IOException) {
            throw PresetLoadException("preset file unreadable: $source", e)
        }
        val root = parseRoot(text, source)
        val id = requiredString(root, "id", source)
        val persona = requiredString(root, "persona", source)
        val complete = requiredBoolean(root, "complete", source)
        val includeRuntimeContext = requiredBoolean(root, "includeRuntimeContext", source)
        val tools = requiredStringList(root, "tools", source)
        val budgets = optionalMapping(root, "budgets")?.let { parseBudgets(it, source) } ?: Budgets()
        val loop = optionalMapping(root, "loop")?.let { parseLoop(it, source) } ?: LoopConfig()
        return Preset(
            id = id,
            persona = persona,
            complete = complete,
            includeRuntimeContext = includeRuntimeContext,
            tools = tools,
            budgets = budgets,
            loop = loop,
            route = route,
        )
    }

    // ----- parsing ---------------------------------------------------------------------------

    private fun parseRoot(text: String, source: String): Map<*, *> {
        val loaded = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
        } catch (e: Exception) {
            throw PresetLoadException("preset YAML unparsable: $source", e)
        }
        return loaded as? Map<*, *>
            ?: throw PresetLoadException("preset YAML is not a mapping: $source")
    }

    private fun parseBudgets(map: Map<*, *>, source: String): Budgets = Budgets(
        commandTimeoutMs = number(map, "commandTimeoutMs", "budgets", source).toLong(),
        maxOutputLines = number(map, "maxOutputLines", "budgets", source).toInt(),
        maxOutputChars = number(map, "maxOutputChars", "budgets", source).toInt(),
        spillHeadChars = number(map, "spillHeadChars", "budgets", source).toInt(),
        spillTailChars = number(map, "spillTailChars", "budgets", source).toInt(),
        contextCapTokens = number(map, "contextCapTokens", "budgets", source).toInt(),
        charsPerToken = number(map, "charsPerToken", "budgets", source).toDouble(),
        pruneThresholdChars = number(map, "pruneThresholdChars", "budgets", source).toInt(),
        pruneHeadChars = number(map, "pruneHeadChars", "budgets", source).toInt(),
        pruneTailChars = number(map, "pruneTailChars", "budgets", source).toInt(),
        verbatimToolResults = number(map, "verbatimToolResults", "budgets", source).toInt(),
        modelConnectTimeoutMs = number(map, "modelConnectTimeoutMs", "budgets", source).toLong(),
        modelReadTimeoutMs = number(map, "modelReadTimeoutMs", "budgets", source).toLong(),
    )

    private fun parseLoop(map: Map<*, *>, source: String): LoopConfig = LoopConfig(
        // turnCap is an explicitly nullable key: present-as-null is a value, absent is a mistake.
        turnCap = (requiredValue(map, "turnCap", "loop", source) as? Number)?.toInt(),
        toolCallsPerStepCap = number(map, "toolCallsPerStepCap", "loop", source).toInt(),
        repeatWarnAfter = number(map, "repeatWarnAfter", "loop", source).toInt(),
        modelRetries = number(map, "modelRetries", "loop", source).toInt(),
        retryBackoffMs = longList(map, "retryBackoffMs", "loop", source),
    )

    // ----- required-key helpers ---------------------------------------------------------------

    private fun requiredValue(map: Map<*, *>, key: String, block: String, source: String): Any? {
        if (!map.containsKey(key)) {
            throw PresetLoadException("preset $source: $block is missing required key '$key'")
        }
        return map[key]
    }

    private fun requiredString(map: Map<*, *>, key: String, source: String): String =
        requiredValue(map, key, "preset", source) as? String
            ?: throw PresetLoadException("preset $source: '$key' must be a string")

    private fun requiredBoolean(map: Map<*, *>, key: String, source: String): Boolean =
        requiredValue(map, key, "preset", source) as? Boolean
            ?: throw PresetLoadException("preset $source: '$key' must be a boolean")

    private fun requiredStringList(map: Map<*, *>, key: String, source: String): List<String> {
        val value = requiredValue(map, key, "preset", source) as? List<*>
            ?: throw PresetLoadException("preset $source: '$key' must be a list")
        return value.map {
            it as? String ?: throw PresetLoadException("preset $source: '$key' must be a list of strings")
        }
    }

    private fun number(map: Map<*, *>, key: String, block: String, source: String): Number =
        requiredValue(map, key, block, source) as? Number
            ?: throw PresetLoadException("preset $source: $block.$key must be a number")

    private fun longList(map: Map<*, *>, key: String, block: String, source: String): List<Long> {
        val value = requiredValue(map, key, block, source) as? List<*>
            ?: throw PresetLoadException("preset $source: $block.$key must be a list of numbers")
        return value.map {
            (it as? Number)?.toLong()
                ?: throw PresetLoadException("preset $source: $block.$key must be a list of numbers")
        }
    }

    /** Absent sub-tree -> null (defaults apply); present-but-not-a-mapping -> loud failure. */
    private fun optionalMapping(root: Map<*, *>, key: String): Map<*, *>? {
        if (!root.containsKey(key)) return null
        return root[key] as? Map<*, *>
            ?: throw PresetLoadException("preset: '$key' must be a mapping")
    }
}
