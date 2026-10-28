package org.ampy.core

/**
 * JSON minimale senza dipendenze (i moduli core devono restare compilabili ovunque).
 * Supporta oggetti, array, stringhe, numeri, booleani e null.
 */
sealed class Json {
    data class Obj(val map: LinkedHashMap<String, Json> = LinkedHashMap()) : Json() {
        operator fun get(k: String): Json? = map[k]
        operator fun set(k: String, v: Json) { map[k] = v }
        fun str(k: String): String? = (map[k] as? Str)?.value
        fun num(k: String): Double? = (map[k] as? Num)?.value
        fun int(k: String): Int? = num(k)?.toInt()
        fun long(k: String): Long? = num(k)?.toLong()
        fun bool(k: String): Boolean? = (map[k] as? Bool)?.value
        fun obj(k: String): Obj? = map[k] as? Obj
        fun arr(k: String): Arr? = map[k] as? Arr
    }
    data class Arr(val items: MutableList<Json> = ArrayList()) : Json()
    data class Str(val value: String) : Json()
    data class Num(val value: Double) : Json()
    data class Bool(val value: Boolean) : Json()
    object Null : Json()

    fun encode(): String = StringBuilder().also { write(it) }.toString()

    private fun write(sb: StringBuilder) {
        when (this) {
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in map) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k); sb.append(':'); v.write(sb)
                }
                sb.append('}')
            }
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { i, v -> if (i > 0) sb.append(','); v.write(sb) }
                sb.append(']')
            }
            is Str -> writeString(sb, value)
            is Num -> {
                val v = value
                if (v.isNaN() || v.isInfinite()) sb.append("null")
                else if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) sb.append(v.toLong())
                else sb.append(v)
            }
            is Bool -> sb.append(value)
            Null -> sb.append("null")
        }
    }

    companion object {
        fun parse(text: String): Json = Parser(text).parseRoot()

        private fun writeString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun parseRoot(): Json {
            val v = value(); ws()
            if (i != s.length) err("caratteri extra")
            return v
        }
        fun err(msg: String): Nothing = throw IllegalArgumentException("JSON non valido @$i: $msg")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Json {
            ws()
            if (i >= s.length) err("fine inattesa")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(str())
                't' -> lit("true", Bool(true))
                'f' -> lit("false", Bool(false))
                'n' -> lit("null", Null)
                else -> if (c == '-' || c.isDigit()) num() else err("carattere '$c'")
            }
        }
        fun lit(w: String, v: Json): Json {
            if (!s.startsWith(w, i)) err("atteso $w")
            i += w.length; return v
        }
        fun obj(): Obj {
            val o = Obj(); i++; ws()
            if (s[i] == '}') { i++; return o }
            while (true) {
                ws(); if (s[i] != '"') err("attesa chiave")
                val k = str(); ws()
                if (s[i] != ':') err("atteso ':'"); i++
                o.map[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return o }; else -> err("atteso ',' o '}'") }
            }
        }
        fun arr(): Arr {
            val a = Arr(); i++; ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a.items.add(value()); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return a }; else -> err("atteso ',' o ']'") }
            }
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("stringa non chiusa")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> err("escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Num {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return Num(s.substring(st, i).toDouble())
        }
    }
}

// Helper di costruzione
fun jsonObj(vararg pairs: Pair<String, Any?>): Json.Obj {
    val o = Json.Obj()
    for ((k, v) in pairs) if (v != null) o[k] = toJson(v)
    return o
}

fun toJson(v: Any?): Json = when (v) {
    null -> Json.Null
    is Json -> v
    is String -> Json.Str(v)
    is Boolean -> Json.Bool(v)
    is Number -> Json.Num(v.toDouble())
    is Enum<*> -> Json.Str(v.name)
    is Iterable<*> -> Json.Arr(v.map { toJson(it) }.toMutableList())
    is Map<*, *> -> Json.Obj(LinkedHashMap(v.entries.associate { it.key.toString() to toJson(it.value) }))
    else -> Json.Str(v.toString())
}
