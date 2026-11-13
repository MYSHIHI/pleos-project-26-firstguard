package io.github.myshihi.pleosproject26.n01crashresponse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON for the open incident and recent history. Unknown or damaged data is dropped, never "repaired" into safe states. */
object IncidentCodec {
    const val VERSION = 2

    fun encode(active: Incident?, history: List<Incident>): String = JSONObject()
        .put("version", VERSION)
        .put("active", active?.let(::toJson) ?: JSONObject.NULL)
        .put("history", JSONArray().also { a -> history.forEach { a.put(toJson(it)) } })
        .toString()

    fun decode(text: String?): Pair<Incident?, List<Incident>> {
        if (text.isNullOrBlank()) return null to emptyList()
        return try {
            val root = JSONObject(text)
            if (root.optInt("version") != VERSION) return null to emptyList()
            val active = root.optJSONObject("active")?.let { runCatching { fromJson(it) }.getOrNull() }
            val list = root.optJSONArray("history") ?: JSONArray()
            val history = (0 until list.length()).mapNotNull { runCatching { fromJson(list.getJSONObject(it)) }.getOrNull() }
            active to history
        } catch (_: Exception) { null to emptyList() }
    }

    private fun toJson(i: Incident): JSONObject = JSONObject().apply {
        put("id", i.id); put("startedAt", i.startedAt); put("trigger", i.trigger.name); put("triggerSource", i.triggerSource.name)
        put("seatsBefore", JSONObject().also { o ->
            i.seatsBefore.forEach { (s, r) ->
                o.put(s.name, JSONObject().put("on", r.on ?: JSONObject.NULL).put("source", r.source.name).put("at", r.at).put("note", r.note))
            }
        })
        put("people", JSONObject().also { o -> i.people.forEach { (s, p) -> o.put(s.name, p.name) } })
        put("peopleSource", JSONObject().also { o -> i.peopleSource.forEach { (s, src) -> o.put(s.name, src.name) } })
        put("factors", JSONObject().also { o -> i.factors.forEach { (f, s) -> o.put(f.name, s.name) } })
        put("factorNotes", JSONObject().also { o -> i.factorNotes.forEach { (f, n) -> o.put(f.name, n) } })
        put("userFactors", JSONArray(i.userFactors.map { it.name }))
        put("doorsOpened", JSONArray(i.doorsOpened.map { it.name }))
        put("response", i.response.name); put("responseDeadline", i.responseDeadline)
        put("contacts", JSONObject().also { o ->
            i.contacts.forEach { (a, c) -> o.put(a.name, JSONObject().put("state", c.state.name).put("at", c.at).put("note", c.note)) }
        })
        put("actions", JSONObject().also { o ->
            i.actions.forEach { (a, r) -> o.put(a.name, JSONObject().put("state", r.state.name).put("at", r.at).put("detail", r.detail)) }
        })
        put("place", i.place?.let { JSONObject().put("text", it.text).put("source", it.source.name) } ?: JSONObject.NULL)
        put("log", JSONArray().also { a -> i.log.forEach { a.put(JSONObject().put("at", it.at).put("source", it.source.name).put("text", it.text)) } })
        put("closedAt", i.closedAt ?: JSONObject.NULL)
    }

    private inline fun <reified E : Enum<E>> JSONObject.enumMap(key: String, block: (E, Any) -> Unit) {
        val o = optJSONObject(key) ?: return
        o.keys().forEach { k -> enumValues<E>().firstOrNull { it.name == k }?.let { block(it, o.get(k)) } }
    }

    private inline fun <reified E : Enum<E>> parse(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

    private fun fromJson(o: JSONObject): Incident {
        val seats = mutableMapOf<Seat, SeatRef>()
        o.enumMap<Seat>("seatsBefore") { s, v ->
            val r = v as JSONObject
            seats[s] = SeatRef(if (r.isNull("on")) null else r.getBoolean("on"), parse<Source>(r.optString("source")) ?: Source.APP,
                r.optLong("at"), r.optString("note"))
        }
        val i = Incident(o.getString("id"), o.getLong("startedAt"), parse<Trigger>(o.getString("trigger"))!!,
            parse<Source>(o.optString("triggerSource")) ?: Source.APP, seats)
        o.enumMap<Seat>("people") { s, v -> parse<PersonState>(v as String)?.let { i.people[s] = it } }
        o.enumMap<Seat>("peopleSource") { s, v -> parse<Source>(v as String)?.let { i.peopleSource[s] = it } }
        o.enumMap<Factor>("factors") { f, v -> i.factors[f] = parse<Source>(v as String) ?: Source.APP }
        o.enumMap<Factor>("factorNotes") { f, v -> i.factorNotes[f] = v as String }
        o.optJSONArray("userFactors")?.let { a -> (0 until a.length()).forEach { n -> parse<Factor>(a.getString(n))?.let { i.userFactors.add(it) } } }
        o.optJSONArray("doorsOpened")?.let { a -> (0 until a.length()).forEach { n -> parse<Seat>(a.getString(n))?.let { i.doorsOpened.add(it) } } }
        i.response = parse<Response>(o.optString("response")) ?: Response.NOT_ASKED
        i.responseDeadline = o.optLong("responseDeadline")
        o.enumMap<Agency>("contacts") { a, v ->
            val c = v as JSONObject
            parse<ContactState>(c.optString("state"))?.let { i.contacts[a] = ContactStatus(it, c.optLong("at"), c.optString("note")) }
        }
        o.enumMap<CarAction>("actions") { a, v ->
            val r = v as JSONObject
            parse<ActionState>(r.optString("state"))?.let { i.actions[a] = ActionResult(it, r.optLong("at"), r.optString("detail")) }
        }
        o.optJSONObject("place")?.let { p -> i.place = Place(p.getString("text"), parse<Source>(p.optString("source")) ?: Source.SAMPLE) }
        o.optJSONArray("log")?.let { l ->
            (0 until l.length()).forEach {
                val e = l.getJSONObject(it)
                i.log.add(LogEntry(e.getLong("at"), parse<Source>(e.optString("source")) ?: Source.APP, e.getString("text")))
            }
        }
        if (!o.isNull("closedAt")) i.closedAt = o.optLong("closedAt")
        return i
    }
}

class IncidentStore(context: Context) {
    private val prefs = context.getSharedPreferences("first_guard_v1", Context.MODE_PRIVATE)

    fun load(): Pair<Incident?, List<Incident>> = IncidentCodec.decode(prefs.getString("state", null))

    /** commit() on purpose: an incident record should survive the app being killed right after a change. */
    fun save(engine: IncidentEngine) {
        prefs.edit().putString("state", IncidentCodec.encode(engine.active, engine.history)).commit()
    }

    fun clear() { prefs.edit().clear().commit() }

    /** Seats whose "deployed" reading already opened an incident. Cleared when the vehicle reports not-deployed again. */
    var ackedAirbags: Set<Seat>
        get() = prefs.getStringSet("acked_airbags", emptySet()).orEmpty().mapNotNull { n -> Seat.entries.firstOrNull { it.name == n } }.toSet()
        set(v) { prefs.edit().putStringSet("acked_airbags", v.map { it.name }.toSet()).commit() }

    /** Defaults to on only for the emulator (ranchu/goldfish), where occupancy arrives inverted. */
    var invertSeats: Boolean
        get() = prefs.getBoolean("invert_seats", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        set(v) { prefs.edit().putBoolean("invert_seats", v).apply() }

    var sampleLocation: Boolean
        get() = prefs.getBoolean("sample_location", true)
        set(v) { prefs.edit().putBoolean("sample_location", v).apply() }
}
