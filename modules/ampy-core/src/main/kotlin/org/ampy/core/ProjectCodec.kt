package org.ampy.core

/**
 * Serializzazione dei progetti (.ampy.json) con numero di schema e migrazioni,
 * per mantenere compatibili i progetti salvati con versioni precedenti.
 */
object ProjectCodec {

    fun encode(p: Project): String = toJsonObj(p).encode()

    fun decode(text: String): Project {
        val root = Json.parse(text) as? Json.Obj ?: throw IllegalArgumentException("Progetto non valido: radice non oggetto")
        val migrated = migrate(root)
        return fromJsonObj(migrated)
    }

    /** Applica le migrazioni in sequenza. Aggiungere qui un blocco per ogni nuova versione di schema. */
    fun migrate(root: Json.Obj): Json.Obj {
        var v = root.int("schemaVersion") ?: 0
        if (v > Project.CURRENT_SCHEMA) {
            throw IllegalArgumentException("Progetto creato con una versione più recente (schema $v > ${Project.CURRENT_SCHEMA})")
        }
        if (v == 0) {
            // Schema 0 (prototipi): "bpm" singolo invece di mappa di tempo.
            val bpm = root.num("bpm") ?: 120.0
            if (root["tempo"] == null) root["tempo"] = Json.Arr(mutableListOf(jsonObj("t" to 0.0, "bpm" to bpm)))
            root.map.remove("bpm")
            v = 1
        }
        root["schemaVersion"] = Json.Num(v.toDouble())
        return root
    }

    private fun noteToJson(n: NoteEvent) = jsonObj(
        "id" to n.id, "p" to n.pitch, "s" to n.startSec, "e" to n.endSec, "v" to n.velocity,
        "c" to n.confidence.toDouble(), "u" to (if (n.userEdited) true else null),
        "pb" to n.pitchBends,
    )

    private fun noteFromJson(o: Json.Obj): NoteEvent = NoteEvent(
        pitch = o.int("p")!!, startSec = o.num("s")!!, endSec = o.num("e")!!,
        velocity = o.int("v") ?: 80, confidence = (o.num("c") ?: 1.0).toFloat(),
        pitchBends = o.arr("pb")?.items?.map { (it as Json.Num).value.toInt() },
        id = o.long("id") ?: nextNoteId(), userEdited = o.bool("u") ?: false,
    )

    fun toJsonObj(p: Project): Json.Obj = jsonObj(
        "schemaVersion" to p.schemaVersion,
        "id" to p.id, "title" to p.title, "sourceAudio" to p.sourceAudio,
        "sampleRate" to p.sampleRate, "durationSec" to p.durationSec,
        "tempo" to p.tempo.map { jsonObj("t" to it.timeSec, "bpm" to it.bpm) },
        "key" to jsonObj("tonic" to p.key.tonicPitchClass, "minor" to p.key.isMinor),
        "time" to jsonObj("num" to p.timeSignature.numerator, "den" to p.timeSignature.denominator),
        "sections" to p.sections.map { jsonObj("label" to it.label, "s" to it.startSec, "e" to it.endSec) },
        "chords" to p.chords.map { jsonObj("s" to it.startSec, "e" to it.endSec, "r" to it.root, "q" to it.quality, "b" to it.bass, "c" to it.confidence) },
        "notes" to p.notes,
        "createdAtMs" to p.createdAtMs, "modifiedAtMs" to p.modifiedAtMs,
        "tracks" to p.tracks.map { t ->
            jsonObj(
                "id" to t.id, "name" to t.name, "instrument" to t.instrument.name,
                "audioFile" to t.audioFile, "volume" to t.volume.toDouble(), "pan" to t.pan.toDouble(),
                "muted" to t.muted, "solo" to t.solo,
                "tuning" to t.tuning?.let { jsonObj("name" to it.name, "strings" to it.openStrings) },
                "warnings" to t.warnings,
                "notes" to t.notes.map { noteToJson(it) },
            )
        },
    )

    fun fromJsonObj(o: Json.Obj): Project {
        val tracks = o.arr("tracks")?.items?.map { tj ->
            val t = tj as Json.Obj
            Track(
                id = t.str("id")!!, name = t.str("name") ?: "Traccia",
                instrument = runCatching { InstrumentKind.valueOf(t.str("instrument") ?: "OTHER") }.getOrDefault(InstrumentKind.OTHER),
                notes = t.arr("notes")?.items?.map { noteFromJson(it as Json.Obj) } ?: emptyList(),
                audioFile = t.str("audioFile"),
                volume = (t.num("volume") ?: 1.0).toFloat(), pan = (t.num("pan") ?: 0.0).toFloat(),
                muted = t.bool("muted") ?: false, solo = t.bool("solo") ?: false,
                tuning = t.obj("tuning")?.let { tu -> Tuning(tu.str("name") ?: "", tu.arr("strings")!!.items.map { (it as Json.Num).value.toInt() }) },
                warnings = t.arr("warnings")?.items?.map { (it as Json.Str).value } ?: emptyList(),
            )
        } ?: emptyList()
        tracks.flatMap { it.notes }.maxOfOrNull { it.id }?.let { reserveNoteIdsAbove(it) }
        return Project(
            schemaVersion = o.int("schemaVersion") ?: Project.CURRENT_SCHEMA,
            id = o.str("id") ?: "progetto", title = o.str("title") ?: "Senza titolo",
            sourceAudio = o.str("sourceAudio"), sampleRate = o.int("sampleRate") ?: 44100,
            durationSec = o.num("durationSec") ?: 0.0,
            tempo = o.arr("tempo")?.items?.map { val t = it as Json.Obj; TempoChange(t.num("t") ?: 0.0, t.num("bpm") ?: 120.0) }
                ?: listOf(TempoChange(0.0, 120.0)),
            key = o.obj("key")?.let { KeySignature(it.int("tonic") ?: 0, it.bool("minor") ?: false) } ?: KeySignature.C_MAJOR,
            timeSignature = o.obj("time")?.let { TimeSignature(it.int("num") ?: 4, it.int("den") ?: 4) } ?: TimeSignature(),
            tracks = tracks,
            sections = o.arr("sections")?.items?.map { val s = it as Json.Obj; Section(s.str("label") ?: "", s.num("s") ?: 0.0, s.num("e") ?: 0.0) } ?: emptyList(),
            chords = o.arr("chords")?.items?.map { val c = it as Json.Obj; ChordMark(c.num("s") ?: 0.0, c.num("e") ?: 0.0, c.int("r") ?: 0, c.str("q") ?: "MAJOR", c.int("b") ?: (c.int("r") ?: 0), c.num("c") ?: 1.0) } ?: emptyList(),
            notes = o.arr("notes")?.items?.map { (it as Json.Str).value } ?: emptyList(),
            createdAtMs = o.long("createdAtMs") ?: 0, modifiedAtMs = o.long("modifiedAtMs") ?: 0,
        )
    }
}
