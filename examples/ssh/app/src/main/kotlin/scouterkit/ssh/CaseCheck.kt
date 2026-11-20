package scouterkit.ssh

// Whether the arms really charge in the case, said on the phone (a better sign than the case's light): put in, one
// short buzz and "Charging ✓" once both have charged for SEAT_MS; an arm in the case but not charging (it is not
// full), or not reaching the case at all while the other does, for BAD_MS (its charger on but no current flowing:
// STALL_MS; firmware 0.5.69+ restarts such a charger itself after 60 s): three buzzes and "Left arm isn't charging:
// seat it again"; charging steadily again for GOOD_MS: one buzz and it says so. Charging means current flowing
// (BATT_MA -2 mA or less), not the charger's phase alone: before 1.20, 0 mA in constant current counted as charging,
// and an arm flapping between the two buzzed "isn't charging" / "charging ✓" over and over. A trouble ends only after
// GOOD_MS of steady charge, so flapping counts as trouble. Pure (the service reads the arms and shows the result).
class CaseCheck {
    // One arm as read (Native.caseStats): -1 unknown.
    data class Arm(val connected: Boolean, val inCase: Int, val charge: Int, val ma: Int, val pct: Int, val lid: Int)

    enum class State { UNKNOWN, CHARGING, FULL, NOT_CHARGING, NO_CURRENT, NO_CONTACT }

    sealed class Event {
        data class Seated(val left: State, val right: State, val leftPct: Int, val rightPct: Int) : Event()
        data class Problem(val arm: Int, val state: State, val pct: Int) : Event() // arm 0 left, 1 right
        data class Fixed(val arm: Int, val pct: Int) : Event()
    }

    private var session = false
    private var sessionAt = 0L
    private var announced = false
    private val badSince = longArrayOf(-1, -1)
    private val goodSince = longArrayOf(-1, -1)
    private val alerted = booleanArrayOf(false, false)
    var states = arrayOf(State.UNKNOWN, State.UNKNOWN)
        private set

    val inCase get() = session

    fun state(a: Arm, other: Arm): State = when {
        !a.connected || a.inCase < 0 -> State.UNKNOWN
        a.inCase != 1 -> if (other.connected && other.inCase == 1) State.NO_CONTACT else State.UNKNOWN
        a.charge == 1 && a.ma <= -2 -> State.CHARGING
        a.charge == 2 || a.pct >= 98 -> State.FULL
        a.charge == 1 -> State.NO_CURRENT
        else -> State.NOT_CHARGING
    }

    fun step(now: Long, l: Arm, r: Arm): List<Event> {
        val anyIn = (l.connected && l.inCase == 1) || (r.connected && r.inCase == 1)
        if (!anyIn) {
            session = false
            announced = false
            badSince.fill(-1)
            goodSince.fill(-1)
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
        fun ok(s: State) = s == State.CHARGING || s == State.FULL
        for (i in 0..1) {
            val s = states[i]
            if (s == State.NOT_CHARGING || s == State.NO_CURRENT || s == State.NO_CONTACT) {
                goodSince[i] = -1
                if (badSince[i] < 0) badSince[i] = now
                if (!alerted[i] && now - badSince[i] >= if (s == State.NO_CURRENT) STALL_MS else BAD_MS) {
                    alerted[i] = true
                    out.add(Event.Problem(i, s, arms[i].pct))
                }
            } else if (ok(s)) {
                if (goodSince[i] < 0) goodSince[i] = now
                if (now - goodSince[i] >= GOOD_MS) {
                    badSince[i] = -1
                    if (alerted[i]) {
                        alerted[i] = false
                        out.add(Event.Fixed(i, arms[i].pct))
                    }
                }
            }
        }
        // both (the ones connected) charging or full for SEAT_MS: said once a time in the case
        val good = (0..1).all { states[it] == State.UNKNOWN || (ok(states[it]) && now - goodSince[it] >= SEAT_MS) } &&
            states.any { ok(it) }
        if (!announced && good && alerted.none { it }) {
            announced = true
            // just reseated after a problem: its "charging ✓" says it already
            if (out.none { it is Event.Fixed }) out.add(Event.Seated(states[0], states[1], l.pct, r.pct))
        }
        return out
    }

    companion object {
        const val BAD_MS = 30_000L
        const val STALL_MS = 90_000L
        const val GOOD_MS = 60_000L
        const val SEAT_MS = 10_000L
        fun name(arm: Int) = if (arm == 0) "Left" else "Right"
    }
}
