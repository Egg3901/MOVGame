package com.lakesidegames.electioneer.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

// Seedable, deterministic RNG. Pure: no global state, no platform random.
// Byte-identical port of src/engine/rng.ts: the same seed yields the same
// stream as TS, which is what makes turns reproducible (undo, replays,
// calibration tests all rely on this).
//
// Porting notes:
// - TS number bit ops (imul, >>>, |) map onto Kotlin Int wraparound ops.
//   Math.imul(a, b) is exactly Int a * b; x >>> n is x ushr n.
// - All uint32 values (seeds, states) are carried as Long in 0..2^32-1 so
//   string interpolation in fork() matches `${numericSeed}:${salt}:${lastState}`.
// - Double arithmetic (including floor/ln/sqrt/cos in normal()) is IEEE 754
//   on both platforms; cross-checked against TS output in RngTest.
class Rng private constructor(
    private var a: Int,
    val numericSeed: Long,
    private var lastState: Long,
) {
    // Returns a float in [0, 1).
    fun next(): Double {
        a = a + MULBERRY_INC
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        val v = ((t xor (t ushr 14)).toLong() and UINT_MASK).toDouble() / TWO_POW_32
        lastState = floor(v * TWO_POW_32).toLong()
        return v
    }

    // Returns an integer in [min, max] inclusive.
    fun int(min: Int, max: Int): Int = min + floor(next() * (max - min + 1)).toInt()

    // Standard-normal sample (Box-Muller).
    fun normal(mean: Double = 0.0, stdev: Double = 1.0): Double {
        // Box-Muller; never exactly 0 for u1 to avoid log(0).
        var u1 = next()
        val u2 = next()
        if (u1 < 1e-12) u1 = 1e-12
        val mag = sqrt(-2.0 * ln(u1))
        return mean + stdev * mag * cos(2.0 * PI * u2)
    }

    // Picks one element.
    fun <T> pick(items: List<T>): T = items[int(0, items.size - 1)]

    fun <T> weightedPick(items: List<T>, weights: List<Double>): T {
        val total = weights.fold(0.0) { s, w -> s + maxOf(0.0, w) }
        var r = next() * total
        for (i in items.indices) {
            r -= maxOf(0.0, weights[i])
            if (r <= 0.0) return items[i]
        }
        return items[items.size - 1]
    }

    // Returns true with probability p.
    fun chance(p: Double): Boolean = next() < p

    // Forks a new independent stream (deterministic from current state).
    fun fork(salt: Long): Rng = createRng(hashSeed("$numericSeed:$salt:$lastState"))

    // Current internal state: used to persist RNG across turns.
    fun state(): Long = lastState

    companion object {
        private const val MULBERRY_INC = 0x6D2B79F5
        private const val UINT_MASK = 0xFFFFFFFFL
        private const val TWO_POW_32 = 4294967296.0

        // Hash a string to a 32-bit seed so seeds can be human-readable.
        // FNV-1a; charCodeAt units match Kotlin Char codes (UTF-16).
        fun hashSeed(input: String): Long {
            var h = 2166136261.toInt()
            for (c in input) {
                h = h xor c.code
                h = h * 16777619
            }
            return h.toLong() and UINT_MASK
        }

        fun createRng(seed: Long): Rng {
            val numericSeed = seed and UINT_MASK
            return Rng(numericSeed.toInt(), numericSeed, numericSeed)
        }

        fun createRng(seed: String): Rng = createRng(hashSeed(seed))

        fun createRng(seed: Int): Rng = createRng(seed.toLong())
    }
}
