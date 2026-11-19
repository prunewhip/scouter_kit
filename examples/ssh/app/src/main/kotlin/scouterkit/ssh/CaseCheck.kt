package scouterkit.ssh

// Whether the arms really charge in the case, said on the phone (a better sign than the case's light): put in, one
// short buzz and "Charging ✓" once both charge; an arm in the case but not charging (it is not full), or not
// reaching the case at all while the other does, for 30 s: three buzzes and "Left arm isn't charging: seat it
// again"; charging again: one buzz and it says so. Pure (the service reads the arms and shows what comes out).
class CaseCheck {
    // One arm as read (Native.caseStats): -1 unknown.
    data class Arm(val connected: Boolean, val inCase: Int, val charge: Int, val ma: Int, val pct: Int, val lid: Int)

    enum class State { UNKNOWN, CHARGING, FULL, NOT_CHARGING, NO_CONTACT }

    sealed class Event {
        data class Seated(val left: State, val right: State, val leftPct: Int, val rightPct: Int) : Event()
        data class Problem(val arm: Int, val state: State, val pct: Int) : Event() // arm 0 left, 1 right
        data class Fixed(val arm: Int, val pct: Int) : Event()
    }

    private var session = false
    private var sessionAt = 0L
    private var announced = false
    private val badSince = longArrayOf(-1, -1)
    private val alerted = booleanArrayOf(false, false)
    var states = arrayOf(State.UNKNOWN, State.UNKNOWN)
        private set

    val inCase get() = session

    fun state(a: Arm, other: Arm): State = when {
        !a.connected || a.inCase < 0 -> State.UNKNOWN
        a.inCase != 1 -> if (other.connected && other.inCase == 1) State.NO_CONTACT else State.UNKNOWN
        a.charge == 1 -> State.CHARGING
        a.charge == 2 || a.pct >= 98 -> State.FULL
        else -> State.NOT_CHARGING
    }

    fun step(now: Long, l: Arm, r: Arm): List<Event> {
        val anyIn = (l.connected && l.inCase == 1) || (r.connected && r.inCase == 1)
        if (!anyIn) {
            session = false
            announced = false
            badSince.fill(-1)
            alerted.fill(false)
            states = arrayOf(State.UNKNOWN, State.UNKNOWN)
            return emptyList()
        }
        if (!session) {
            session = true
            sessionAt = now
        }
        val arms = arrayOf(l, r)
        states = arrayOf(state(l, r), state(r, l))
        val out = ArrayList<Event>()
        for (i in 0..1) {
            val bad = states[i] == State.NOT_CHARGING || states[i] == State.NO_CONTACT
            if (bad) {
                if (badSince[i] < 0) badSince[i] = now
                if (!alerted[i] && now - badSince[i] >= BAD_MS) {
                    alerted[i] = true
                    out.add(Event.Problem(i, states[i], arms[i].pct))
                }
            } else {
                badSince[i] = -1
                if (alerted[i] && (states[i] == State.CHARGING || states[i] == State.FULL)) {
                    alerted[i] = false
                    out.add(Event.Fixed(i, arms[i].pct))
                }
            }
        }
        // both (the ones connected) charging or full: said once a time in the case
        val good = states.all { it == State.CHARGING || it == State.FULL || it == State.UNKNOWN } &&
            states.any { it == State.CHARGING || it == State.FULL }
        if (!announced && good && alerted.none { it }) {
            announced = true
            // just reseated after a problem: its "charging ✓" says it already
            if (out.none { it is Event.Fixed }) out.add(Event.Seated(states[0], states[1], l.pct, r.pct))
        }
        return out
    }

    companion object {
        const val BAD_MS = 30_000L
        fun name(arm: Int) = if (arm == 0) "Left" else "Right"
    }
}
