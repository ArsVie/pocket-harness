package ph.tools

import ph.policy.PolicyDecision
import ph.policy.PolicyFloor

/**
 * One never-allowed rule, as **data** rather than code (ADR-002 / Rec 11). [patterns] are regular
 * expressions matched against the raw command text; a rule denies when any of its patterns matches.
 * Rule content is a policy file's business — this type only carries it.
 */
data class PolicyRule(
    val name: String,
    val reason: String,
    val patterns: List<String>,
)

/**
 * The never-allowed floor (ADR-002, ADR-004 §4). A small set of catastrophic shapes is frozen at
 * construction and evaluated for every `bash` command in **both** modes; the mode switch sits below
 * the floor and can never relax it. There is deliberately no setter and no bypass flag — nothing a
 * model, a fetched page, or a settings edit can reach can widen this set at runtime.
 *
 * Denials name the rule that matched, so the model-visible error is actionable.
 */
class DefaultPolicyFloor(rules: List<PolicyRule> = DEFAULT_RULES) : PolicyFloor {

    /** Compiled once, at construction; the regexes are never rebuilt and cannot be replaced. */
    private val compiled: List<Pair<PolicyRule, List<Regex>>> =
        rules.map { rule -> rule to rule.patterns.map { pattern -> Regex(pattern) } }

    /** The frozen rules, in match order. */
    val rules: List<PolicyRule> get() = compiled.map { (rule, _) -> rule }

    override fun check(command: String): PolicyDecision {
        for ((rule, patterns) in compiled) {
            if (patterns.any { it.containsMatchIn(command) }) {
                return PolicyDecision.Deny(rule = rule.name, reason = rule.reason)
            }
        }
        return PolicyDecision.Allow
    }

    companion object {

        /**
         * The shipped floor. Shapes, in order: recursive removal of the filesystem root; writing to
         * or formatting a block device; the classic fork bomb; shutting the device down. Each is a
         * match on the *text* of the command — the command itself is never rewritten (ADR-004 §5).
         */
        val DEFAULT_RULES: List<PolicyRule> = listOf(
            PolicyRule(
                name = "rm-rf-root",
                reason = "recursive removal of the filesystem root is never allowed",
                patterns = listOf(
                    "\\brm\\s+(?:-[A-Za-z]+\\s+)*/(?:\\s|$|\\*)",
                ),
            ),
            PolicyRule(
                name = "block-device-write",
                reason = "writing to or formatting a block device is never allowed",
                patterns = listOf(
                    "\\bmkfs(?:\\.[A-Za-z0-9_]+)?\\b",
                    "\\bdd\\b[^\\n]*\\bof=/dev/",
                    ">\\s*/dev/(?:block|sd|mmcblk|nvme|disk)",
                ),
            ),
            PolicyRule(
                name = "fork-bomb",
                reason = "a fork bomb is never allowed",
                patterns = listOf(
                    ":\\s*\\(\\s*\\)\\s*\\{[^}]*:\\s*\\|\\s*:\\s*&[^}]*\\}\\s*;?\\s*:?",
                ),
            ),
            PolicyRule(
                name = "system-power",
                reason = "shutting down, rebooting or halting the device is never allowed",
                patterns = listOf(
                    "\\b(?:shutdown|reboot|poweroff|halt)\\b",
                ),
            ),
        )
    }
}
