package ph.tools

import ph.policy.PolicyDecision
import ph.policy.PolicyFloor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The never-allowed floor: catastrophic shapes denied, ordinary work allowed, rules frozen. */
class DefaultPolicyFloorTest {

    private val floor = DefaultPolicyFloor()

    private fun assertDeny(target: PolicyFloor, command: String, rule: String) {
        val decision = target.check(command)
        assertTrue(decision is PolicyDecision.Deny, "expected a denial for: $command")
        assertEquals(rule, decision.rule, "wrong rule for: $command")
    }

    private fun assertDeny(command: String, rule: String) = assertDeny(floor, command, rule)

    @Test
    fun `denies recursive removal of the filesystem root`() {
        assertDeny("rm -rf /", "rm-rf-root")
        assertDeny("sudo rm -rf /*", "rm-rf-root")
        assertDeny("rm -fr / ", "rm-rf-root")
    }

    @Test
    fun `denies formatting and writing to block devices`() {
        assertDeny("mkfs.ext4 /dev/block/sda1", "block-device-write")
        assertDeny("mkfs /dev/block/mmcblk0", "block-device-write")
        assertDeny("dd if=/dev/zero of=/dev/block/sda", "block-device-write")
        assertDeny("cat image.img > /dev/block/sda", "block-device-write")
    }

    @Test
    fun `denies the fork bomb`() {
        assertDeny(":(){ :|:& };:", "fork-bomb")
    }

    @Test
    fun `denies shutdown and friends`() {
        assertDeny("shutdown -h now", "system-power")
        assertDeny("reboot", "system-power")
        assertDeny("poweroff", "system-power")
        assertDeny("halt", "system-power")
    }

    @Test
    fun `a denial names the rule and carries a reason`() {
        val decision = floor.check("rm -rf /") as PolicyDecision.Deny
        assertEquals("rm-rf-root", decision.rule)
        assertTrue(decision.reason.isNotBlank())
    }

    @Test
    fun `ordinary commands are allowed`() {
        listOf(
            "ls -la",
            "echo hi",
            "rm -rf ./build",
            "rm -rf /tmp/scratch",
            "echo x > /dev/null",
            "dd if=in.bin of=out.bin",
            "grep -rn pattern .",
            "cat /etc/hosts",
            "./gradlew test",
        ).forEach { command ->
            assertEquals(PolicyDecision.Allow, floor.check(command), "unexpected denial: $command")
        }
    }

    @Test
    fun `rules are frozen at construction and not widen-able at runtime`() {
        val custom = DefaultPolicyFloor(
            listOf(PolicyRule("quiet", "echo is not allowed here", listOf("\\becho\\b"))),
        )
        assertEquals(listOf("quiet"), custom.rules.map { it.name })
        assertDeny(custom, "echo hi", "quiet")
        // the shipped set is not implied by a custom one, and vice versa
        assertEquals(PolicyDecision.Allow, custom.check("rm -rf /"))
        assertEquals(PolicyDecision.Allow, floor.check("echo hi"))
        assertEquals(
            listOf("rm-rf-root", "block-device-write", "fork-bomb", "system-power"),
            floor.rules.map { it.name },
        )
    }
}
