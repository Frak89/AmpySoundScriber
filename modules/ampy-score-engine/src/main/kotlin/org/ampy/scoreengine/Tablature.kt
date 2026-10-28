package org.ampy.scoreengine

import org.ampy.core.NoteEvent
import org.ampy.core.Tuning
import kotlin.math.abs

/** Posizione su tastiera: string 0 = corda più grave della [Tuning]. */
data class TabNote(val note: NoteEvent, val string: Int, val fret: Int)

/**
 * Assegnazione corda/tasto con Viterbi su gruppi di note simultanee.
 * Costo: altezza del tasto, estensione della mano nell'accordo, spostamento rispetto al gruppo precedente.
 * Note fuori estensione vengono trasposte d'ottava e marcate (non scartate in silenzio).
 */
class TabGenerator(val tuning: Tuning, val maxFret: Int = 22, val maxSpan: Int = 4) {

    data class Result(val notes: List<TabNote>, val transposed: Int)

    private fun positions(pitch: Int): List<Pair<Int, Int>> =
        tuning.openStrings.mapIndexedNotNull { s, open -> val f = pitch - open; if (f in 0..maxFret) s to f else null }

    private fun fitPitch(p: Int): Int {
        var q = p
        val lo = tuning.openStrings.min(); val hi = tuning.openStrings.max() + maxFret
        while (q < lo) q += 12
        while (q > hi) q -= 12
        return q
    }

    private fun chordAssignments(pitches: List<Int>): List<List<Pair<Int, Int>>> {
        val res = ArrayList<List<Pair<Int, Int>>>()
        val cands = pitches.map { positions(it) }
        fun rec(i: Int, used: Set<Int>, acc: List<Pair<Int, Int>>) {
            if (res.size > 400) return
            if (i == pitches.size) { res.add(acc); return }
            for (c in cands[i]) if (c.first !in used) rec(i + 1, used + c.first, acc + c)
        }
        rec(0, emptySet(), emptyList())
        return res
    }

    private fun span(a: List<Pair<Int, Int>>): Int {
        val fretted = a.map { it.second }.filter { it > 0 }
        return if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
    }

    private fun handPos(a: List<Pair<Int, Int>>): Double = a.map { it.second }.filter { it > 0 }.ifEmpty { listOf(0) }.average()

    fun assign(notes: List<NoteEvent>, chordToleranceSec: Double = 0.03): Result {
        if (notes.isEmpty()) return Result(emptyList(), 0)
        var transposed = 0
        val fitted = notes.sortedBy { it.startSec }.map { n -> val q = fitPitch(n.pitch); if (q != n.pitch) transposed++; n to q }
        // gruppi di note simultanee (max = numero di corde; le eccedenti vanno nel gruppo successivo)
        val groups = ArrayList<MutableList<Pair<NoteEvent, Int>>>()
        for (p in fitted) {
            val g = groups.lastOrNull()
            if (g != null && abs(g[0].first.startSec - p.first.startSec) <= chordToleranceSec && g.size < tuning.openStrings.size && g.none { it.second == p.second }) g.add(p)
            else groups.add(mutableListOf(p))
        }
        val states = groups.map { g ->
            val pitches = g.map { it.second }
            chordAssignments(pitches).filter { span(it) <= maxSpan }.ifEmpty { chordAssignments(pitches).ifEmpty { listOf(pitches.map { pp -> positions(pp).first() }) } }
        }
        // Viterbi
        val cost = ArrayList<DoubleArray>(); val back = ArrayList<IntArray>()
        fun local(a: List<Pair<Int, Int>>) = a.sumOf { it.second * 0.12 } + span(a) * 0.6
        cost.add(DoubleArray(states[0].size) { local(states[0][it]) }); back.add(IntArray(states[0].size) { -1 })
        for (i in 1 until states.size) {
            val c = DoubleArray(states[i].size); val b = IntArray(states[i].size)
            for (j in states[i].indices) {
                var best = Double.MAX_VALUE; var bi = 0
                val hj = handPos(states[i][j])
                for (k in states[i - 1].indices) {
                    val v = cost[i - 1][k] + abs(hj - handPos(states[i - 1][k])) * 0.5
                    if (v < best) { best = v; bi = k }
                }
                c[j] = best + local(states[i][j]); b[j] = bi
            }
            cost.add(c); back.add(b)
        }
        var idx = cost.last().indices.minBy { cost.last()[it] }
        val chosen = arrayOfNulls<List<Pair<Int, Int>>>(states.size)
        for (i in states.indices.reversed()) { chosen[i] = states[i][idx]; idx = back[i][idx].coerceAtLeast(0) }
        val out = ArrayList<TabNote>()
        groups.forEachIndexed { gi, g -> g.forEachIndexed { ni, (n, _) -> val (s, f) = chosen[gi]!![ni]; out.add(TabNote(n, s, f)) } }
        return Result(out, transposed)
    }

    /** Tablatura testuale (ASCII) con [colsPerLine] colonne temporali per riga; [stepSec] = risoluzione. */
    fun renderAscii(result: Result, stepSec: Double = 0.125, colsPerLine: Int = 64, barEverySteps: Int = 16): String {
        if (result.notes.isEmpty()) return ""
        val total = (result.notes.maxOf { it.note.startSec } / stepSec).toInt() + 2
        val nStr = tuning.openStrings.size
        val grid = Array(nStr) { Array(total) { "-" } }
        for (t in result.notes) {
            val c = (t.note.startSec / stepSec).toInt().coerceIn(0, total - 1)
            grid[t.string][c] = t.fret.toString()
        }
        val names = tuning.openStrings.map { NOTE_NAMES[it % 12] }
        val sb = StringBuilder()
        sb.append("Accordatura: ").append(tuning.name).append('\n')
        if (result.transposed > 0) sb.append("Nota: ${result.transposed} note trasposte d'ottava per rientrare nell'estensione\n")
        var start = 0
        while (start < total) {
            val end = minOf(total, start + colsPerLine)
            for (s in nStr - 1 downTo 0) {
                sb.append(names[s].padEnd(2)).append('|')
                for (c in start until end) {
                    if (c > start && c % barEverySteps == 0) sb.append('|')
                    val cell = grid[s][c]
                    sb.append(cell.padEnd(if (grid.any { it[c].length > 1 }) 2 else 1, '-')).append('-')
                }
                sb.append("|\n")
            }
            sb.append('\n'); start = end
        }
        return sb.toString()
    }

    companion object { val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B") }
}
