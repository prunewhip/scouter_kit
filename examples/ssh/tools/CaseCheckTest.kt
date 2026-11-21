import scouterkit.ssh.CaseCheck
import scouterkit.ssh.CaseCheck.Arm
import scouterkit.ssh.CaseCheck.Event
import scouterkit.ssh.CaseCheck.State

// CaseCheck's verdicts (kotlinc CaseCheckTest.kt ../app/src/main/kotlin/scouterkit/ssh/CaseCheck.kt).
var failures = 0
fun check(name: String, got: Any?, want: Any?) {
    if (got != want) { failures++; println("FAIL $name: got $got, want $want") }
}
fun arm(inCase: Int, charge: Int, pct: Int, connected: Boolean = true) = Arm(connected, inCase, charge, if (charge == 1) -30 else 10, pct, 0)

fun main() {
    // out of the case: nothing
    var c = CaseCheck()
    check("out", c.step(0, arm(0, 0, 50), arm(0, 0, 50)), emptyList<Event>())
    // both charging at once: one Seated
    check("seated", c.step(1000, arm(1, 1, 37), arm(1, 1, 75)), listOf(Event.Seated(State.CHARGING, State.CHARGING, 37, 75)))
    check("seated once", c.step(6000, arm(1, 1, 38), arm(1, 1, 75)), emptyList<Event>())
    // left stops charging (today's log: 46% -> not charging, VRECT fine): Problem after 30 s, not before
    check("stall early", c.step(10_000, arm(1, 0, 46), arm(1, 1, 76)), emptyList<Event>())
    check("stall 25s", c.step(35_000, arm(1, 0, 42), arm(1, 1, 76)), emptyList<Event>())
    check("stall 30s", c.step(40_000, arm(1, 0, 40), arm(1, 1, 76)), listOf(Event.Problem(0, State.NOT_CHARGING, 40)))
    check("stall once", c.step(55_000, arm(1, 0, 38), arm(1, 1, 77)), emptyList<Event>())
    check("fixed", c.step(70_000, arm(1, 1, 38), arm(1, 1, 77)), listOf(Event.Fixed(0, 38)))
    // taken out: reset; put back with the left not reaching the charger
    check("out again", c.step(80_000, arm(0, 0, 38), arm(0, 0, 77)), emptyList<Event>())
    check("no contact early", c.step(90_000, arm(0, 0, 38), arm(1, 1, 77)), emptyList<Event>())
    check("no contact", c.step(121_000, arm(0, 0, 38), arm(1, 1, 77)), listOf(Event.Problem(0, State.NO_CONTACT, 38)))
    check("reseated", c.step(130_000, arm(1, 1, 38), arm(1, 1, 77)), listOf(Event.Fixed(0, 38)))
    // full arms are fine (the case stops powering them)
    c = CaseCheck()
    check("full", c.step(0, arm(1, 2, 100), arm(1, 0, 99)), listOf(Event.Seated(State.FULL, State.FULL, 100, 99)))
    check("full later", c.step(60_000, arm(1, 0, 100), arm(1, 0, 99)), emptyList<Event>())
    // one arm not connected to the phone: judged on the other
    c = CaseCheck()
    check("one arm", c.step(0, arm(-1, -1, -1, connected = false), arm(1, 1, 60)), listOf(Event.Seated(State.UNKNOWN, State.CHARGING, -1, 60)))
    // never charging from the start: no Seated, a Problem at 30 s
    c = CaseCheck()
    check("bad start", c.step(0, arm(1, 0, 20), arm(1, 1, 60)), emptyList<Event>())
    check("bad start 30", c.step(30_000, arm(1, 0, 20), arm(1, 1, 60)), listOf(Event.Problem(0, State.NOT_CHARGING, 20)))
    check("then good", c.step(35_000, arm(1, 1, 20), arm(1, 1, 60)), listOf(Event.Fixed(0, 20)))
    println(if (failures == 0) "case check: ok" else "case check: $failures failures")
}
