package org.ampy.separator

import org.ampy.audioengine.PcmSource
import org.ampy.audioengine.RawFloatFile
import org.ampy.audioengine.Segmenter
import org.ampy.audioengine.Stft
import org.ampy.core.AmpyLog
import org.ampy.core.FloatTensor
import org.ampy.core.JobContext
import org.ampy.core.LogCategory
import org.ampy.core.ModuleInfo
import org.ampy.core.TensorRunner
import java.io.File
import kotlin.math.sqrt

object SeparatorModule {
    val info = ModuleInfo("AmpySeparator", "0.1.0", "Separazione sorgenti: HPSS DSP, modelli a maschera spettrale e a forma d'onda (ONNX)")
}

enum class StemKind(val label: String) {
    VOCALS("Voce"), DRUMS("Batteria"), BASS("Basso"), OTHER("Altro"), GUITAR("Chitarra"), PIANO("Pianoforte"),
    WINDS("Fiati"), HARMONIC("Componente armonica"), PERCUSSIVE("Componente percussiva"), LOW_BAND("Banda bassa (filtro)"), RESIDUAL("Residuo"),
}

data class Stem(val kind: StemKind, val file: File, val sampleRate: Int, val channels: Int, val warnings: List<String> = emptyList())

data class SeparationResult(val stems: List<Stem>, val report: SeparabilityReport)

interface SourceSeparator {
    val id: String
    val displayName: String
    val stems: List<StemKind>
    /** Avvertenza mostrata all'utente sui limiti del metodo. */
    val limitation: String
    fun separate(src: PcmSource, outDir: File, ctx: JobContext): SeparationResult
}

/**
 * Elaborazione a segmenti sovrapposti con dissolvenza incrociata lineare: permette di separare brani
 * di qualsiasi durata con memoria costante. [process] riceve (canali x campioni) e restituisce, per ogni stem,
 * (canali x campioni) della stessa lunghezza.
 */
internal class SegmentedRenderer(val src: PcmSource, val outDir: File, val kinds: List<StemKind>, val outChannels: Int, val segFrames: Int, val overlap: Int) {
    fun run(ctx: JobContext, process: (Array<FloatArray>) -> List<Array<FloatArray>>): List<Stem> {
        outDir.mkdirs()
        val writers = kinds.map { RawFloatFile.Writer(File(outDir, "${it.name.lowercase()}.f32"), outChannels) }
        val segs = Segmenter.plan(src.frameCount, segFrames, overlap)
        var tail: List<Array<FloatArray>>? = null // coda della dissolvenza del segmento precedente
        try {
            for ((si, s) in segs.withIndex()) {
                ctx.cancel.throwIfCancelled()
                val input = src.readChannels(s.start, s.length).let { ch -> Array(outChannels) { c -> ch[minOf(c, ch.size - 1)] } }
                val res = process(input)
                val isLast = si == segs.size - 1
                val fadeLen = if (si == 0) 0 else minOf(overlap, s.length)
                val emitEnd = if (isLast) s.length else s.length - overlap
                res.forEachIndexed { k, stem ->
                    val out = Array(outChannels) { c -> FloatArray(emitEnd) }
                    for (c in 0 until outChannels) {
                        for (i in 0 until emitEnd) {
                            var v = stem[c][i]
                            if (i < fadeLen) { val w = (i + 0.5f) / fadeLen; v = v * w + tail!![k][c][i] * (1 - w) }
                            out[c][i] = v
                        }
                    }
                    val inter = FloatArray(emitEnd * outChannels)
                    for (i in 0 until emitEnd) for (c in 0 until outChannels) inter[i * outChannels + c] = out[c][i]
                    writers[k].writeInterleaved(inter, emitEnd)
                }
                tail = if (isLast) null else res.map { stem -> Array(outChannels) { c -> stem[c].copyOfRange(s.length - overlap, s.length) } }
                ctx.progress.report((si + 1).toDouble() / segs.size, "Separazione: segmento ${si + 1}/${segs.size}")
            }
        } finally { writers.forEach { it.close() } }
        return kinds.map { Stem(it, File(outDir, "${it.name.lowercase()}.f32"), src.sampleRate, outChannels) }
    }
}

/**
 * Separazione armonico/percussiva (Fitzgerald 2010) con maschere di Wiener. Funziona offline su qualsiasi
 * dispositivo senza modelli, ma NON isola singoli strumenti: separa componenti sostenute da transitori.
 */
class HpssSeparator(val nFft: Int = 2048, val hop: Int = 512, val kernelTime: Int = 17, val kernelFreq: Int = 17, val segmentSec: Double = 30.0, val includeLowBand: Boolean = true) : SourceSeparator {
    override val id = "dsp-hpss"
    override val displayName = "DSP: armonico / percussivo (veloce, senza modelli)"
    override val stems = listOfNotNull(StemKind.HARMONIC, StemKind.PERCUSSIVE, if (includeLowBand) StemKind.LOW_BAND else null)
    override val limitation = "La separazione DSP divide componenti armoniche e percussive: voce, chitarra e pianoforte restano insieme nella componente armonica. Per isolare singoli strumenti installare un modello neurale dal Gestore modelli."

    private fun medianFilter(values: FloatArray, k: Int, tmp: FloatArray, out: FloatArray) {
        val h = k / 2; val n = values.size
        for (i in 0 until n) {
            var c = 0
            for (j in maxOf(0, i - h)..minOf(n - 1, i + h)) tmp[c++] = values[j]
            java.util.Arrays.sort(tmp, 0, c)
            out[i] = tmp[c / 2]
        }
    }

    fun masks(mag: Array<FloatArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
        val nT = mag.size; val nB = mag.firstOrNull()?.size ?: 0
        val harm = Array(nT) { FloatArray(nB) }; val perc = Array(nT) { FloatArray(nB) }
        val tmp = FloatArray(maxOf(kernelTime, kernelFreq) + 1)
        val col = FloatArray(nT); val colOut = FloatArray(nT)
        for (b in 0 until nB) {
            for (t in 0 until nT) col[t] = mag[t][b]
            medianFilter(col, kernelTime, tmp, colOut)
            for (t in 0 until nT) harm[t][b] = colOut[t]
        }
        for (t in 0 until nT) medianFilter(mag[t], kernelFreq, tmp, perc[t])
        val mh = Array(nT) { FloatArray(nB) }; val mp = Array(nT) { FloatArray(nB) }
        for (t in 0 until nT) for (b in 0 until nB) {
            val h2 = harm[t][b] * harm[t][b]; val p2 = perc[t][b] * perc[t][b]
            val den = h2 + p2 + 1e-12f
            mh[t][b] = h2 / den; mp[t][b] = p2 / den
        }
        return mh to mp
    }

    override fun separate(src: PcmSource, outDir: File, ctx: JobContext): SeparationResult {
        val stft = Stft(nFft, hop)
        val seg = (segmentSec * src.sampleRate).toInt()
        val overlap = minOf(seg / 4, src.sampleRate * 2)
        val lowCutBin = (180.0 / src.sampleRate * nFft).toInt()
        val ch = src.channelCount.coerceIn(1, 2)
        val stemsOut = SegmentedRenderer(src, outDir, stems, ch, seg, overlap).run(ctx) { input ->
            val outs = stems.map { Array(ch) { FloatArray(input[0].size) } }
            for (c in 0 until ch) {
                val spec = stft.forward(input[c])
                val (mh, mp) = masks(spec.mag)
                val h = Array(spec.nFrames) { t -> FloatArray(spec.nBins) { b -> spec.mag[t][b] * mh[t][b] } }
                val p = Array(spec.nFrames) { t -> FloatArray(spec.nBins) { b -> spec.mag[t][b] * mp[t][b] } }
                outs[0][c] = stft.inverse(h, spec.phase, input[c].size)
                outs[1][c] = stft.inverse(p, spec.phase, input[c].size)
                if (includeLowBand) {
                    val lb = Array(spec.nFrames) { t -> FloatArray(spec.nBins) { b -> if (b <= lowCutBin) h[t][b] else 0f } }
                    outs[2][c] = stft.inverse(lb, spec.phase, input[c].size)
                }
            }
            outs
        }
        val stemsWithWarn = stemsOut.map { s ->
            when (s.kind) {
                StemKind.LOW_BAND -> s.copy(warnings = listOf("Filtro passa-basso sulla componente armonica: contiene basso ma anche note gravi di altri strumenti."))
                StemKind.HARMONIC -> s.copy(warnings = listOf("Contiene tutti gli strumenti intonati insieme (non separabili con il metodo DSP)."))
                else -> s
            }
        }
        val report = SeparabilityAnalyzer.analyze(stemsWithWarn, extraWarnings = listOf(limitation))
        return SeparationResult(stemsWithWarn, report)
    }
}

/**
 * Modello a maschera spettrale stile Open-Unmix (un modello ONNX per sorgente).
 * Ingresso: magnitudine STFT (1, canali, bins, frame); uscita: magnitudine stimata con la stessa forma.
 * Le stime sono combinate con una maschera di Wiener morbida e applicate allo spettro complesso della miscela.
 */
class SpectralMaskModelSeparator(
    override val id: String,
    override val displayName: String,
    private val runners: Map<StemKind, TensorRunner>,
    val nFft: Int = 4096, val hop: Int = 1024, val segmentSec: Double = 20.0,
    val inputName: String = "mix", val outputName: String = "estimate",
    override val limitation: String = "Modello a maschera spettrale: qualità dipendente dal modello installato.",
) : SourceSeparator {
    override val stems = runners.keys.toList() + StemKind.RESIDUAL

    override fun separate(src: PcmSource, outDir: File, ctx: JobContext): SeparationResult {
        val stft = Stft(nFft, hop)
        val seg = (segmentSec * src.sampleRate).toInt(); val overlap = minOf(seg / 4, src.sampleRate * 2)
        val ch = src.channelCount.coerceIn(1, 2)
        val kinds = runners.keys.toList()
        val out = SegmentedRenderer(src, outDir, stems, ch, seg, overlap).run(ctx) { input ->
            val specs = (0 until ch).map { stft.forward(input[it]) }
            val nT = specs[0].nFrames; val nB = specs[0].nBins
            // tensore (1, ch, bins, frames)
            val mix = FloatArray(ch * nB * nT)
            for (c in 0 until ch) for (b in 0 until nB) for (t in 0 until nT) mix[(c * nB + b) * nT + t] = specs[c].mag[t][b]
            val estimates = kinds.map { k ->
                val r = runners[k]!!.run(mapOf(inputName to FloatTensor(intArrayOf(1, ch, nB, nT), mix)))
                (r[outputName] ?: r.values.first()).data
            }
            val res = kinds.map { Array(ch) { FloatArray(input[0].size) } } + listOf(Array(ch) { FloatArray(input[0].size) })
            for (c in 0 until ch) {
                val masked = kinds.indices.map { Array(nT) { FloatArray(nB) } }
                val resid = Array(nT) { FloatArray(nB) }
                for (b in 0 until nB) for (t in 0 until nT) {
                    val i = (c * nB + b) * nT + t
                    var den = 1e-10f
                    for (e in estimates) den += e[i] * e[i]
                    var used = 0f
                    for (k in kinds.indices) { val m = estimates[k][i] * estimates[k][i] / den; masked[k][t][b] = specs[c].mag[t][b] * m; used += m }
                    resid[t][b] = specs[c].mag[t][b] * (1 - used).coerceAtLeast(0f)
                }
                for (k in kinds.indices) res[k][c] = stft.inverse(masked[k], specs[c].phase, input[c].size)
                res.last()[c] = stft.inverse(resid, specs[c].phase, input[c].size)
            }
            res
        }
        return SeparationResult(out, SeparabilityAnalyzer.analyze(out))
    }
}

/**
 * Modello a forma d'onda (es. Demucs/HTDemucs esportato in ONNX).
 * Ingresso (1, canali, campioni) a [modelRate]; uscita (1, sorgenti, canali, campioni) nell'ordine [order].
 */
class WaveformModelSeparator(
    override val id: String,
    override val displayName: String,
    private val runner: TensorRunner,
    val order: List<StemKind>,
    val modelRate: Int = 44100,
    val segmentSamples: Int = 343980, // 7.8 s, segmento di training di HTDemucs
    val overlapSamples: Int = 44100,
    val inputName: String = "mix",
    override val limitation: String = "Modello neurale a forma d'onda: la qualità dipende dal modello; strumenti con timbro simile possono restare mescolati.",
) : SourceSeparator {
    override val stems = order

    override fun separate(src: PcmSource, outDir: File, ctx: JobContext): SeparationResult {
        require(src.sampleRate == modelRate) { "Il modello richiede $modelRate Hz: ricampionare prima (PcmConvert)" }
        val ch = 2
        val out = SegmentedRenderer(src, outDir, order, ch, segmentSamples, overlapSamples).run(ctx) { input ->
            val n = input[0].size
            val x = FloatArray(ch * segmentSamples)
            for (c in 0 until ch) input[c].copyInto(x, c * segmentSamples) // zero-padding a lunghezza fissa
            val r = runner.run(mapOf(inputName to FloatTensor(intArrayOf(1, ch, segmentSamples), x))).values.first()
            check(r.shape.size == 4 && r.shape[1] == order.size) { "uscita del modello con forma inattesa ${r.shape.contentToString()}" }
            order.indices.map { s -> Array(ch) { c -> FloatArray(n) { i -> r.data[(s * ch + c) * segmentSamples + i] } } }
        }
        return SeparationResult(out, SeparabilityAnalyzer.analyze(out))
    }
}

/** Valuta l'affidabilità della separazione e segnala coppie di sorgenti non separabili. */
data class SeparabilityReport(val stemEnergyDb: Map<StemKind, Double>, val leakage: List<Triple<StemKind, StemKind, Double>>, val warnings: List<String>)

object SeparabilityAnalyzer {
    fun analyze(stems: List<Stem>, extraWarnings: List<String> = emptyList(), maxFrames: Int = 44100 * 60, leakThreshold: Double = 0.75): SeparabilityReport {
        val stft = Stft(2048, 1024)
        val mags = stems.map { s ->
            RawFloatFile(s.file, s.sampleRate, s.channels).use { src ->
                val n = minOf(src.frameCount, maxFrames.toLong()).toInt()
                // campione centrale del brano (più rappresentativo dell'attacco)
                val start = ((src.frameCount - n) / 2).coerceAtLeast(0)
                stft.forward(src.readMono(start, n)).mag
            }
        }
        val energy = stems.indices.associate { i -> stems[i].kind to 10 * kotlin.math.log10(mags[i].sumOf { r -> r.sumOf { (it * it).toDouble() } } + 1e-12) }
        val maxE = energy.values.maxOrNull() ?: 0.0
        val warns = ArrayList(extraWarnings)
        for ((k, e) in energy) if (e < maxE - 35) warns.add("${k.label}: energia molto bassa (${"%.0f".format(e - maxE)} dB), probabilmente assente nel brano.")
        val leaks = ArrayList<Triple<StemKind, StemKind, Double>>()
        for (i in stems.indices) for (j in i + 1 until stems.size) {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            val a = mags[i]; val b = mags[j]
            for (t in 0 until minOf(a.size, b.size)) for (f in a[t].indices) { dot += a[t][f] * b[t][f]; na += a[t][f] * a[t][f]; nb += b[t][f] * b[t][f] }
            val corr = if (na > 0 && nb > 0) dot / sqrt(na * nb) else 0.0
            leaks.add(Triple(stems[i].kind, stems[j].kind, corr))
            if (corr > leakThreshold && stems[i].kind != StemKind.RESIDUAL && stems[j].kind != StemKind.RESIDUAL)
                warns.add("${stems[i].kind.label} e ${stems[j].kind.label} non sono separabili con affidabilità (sovrapposizione spettrale ${"%.0f".format(corr * 100)}%).")
        }
        AmpyLog.i(LogCategory.SEPARATION, "separability", warns.joinToString(" | ").ifEmpty { "nessun problema rilevato" })
        return SeparabilityReport(energy, leaks, warns)
    }
}
