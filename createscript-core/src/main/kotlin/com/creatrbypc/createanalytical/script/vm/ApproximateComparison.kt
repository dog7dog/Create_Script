package com.creatrbypc.createanalytical.script.vm

import kotlin.math.abs
import kotlin.math.max

// isClose（第3.11e段階）の判定。|a - b| <= tolerance × max(1, |a|, |b|) を、丸めとオーバーフローの誤差なく判定する。
// 0 の近くでは絶対誤差、大きな値では相対誤差になる。等しい値（同じ符号の Infinity・0.0 と -0.0 を含む）は true、NaN を含む・Infinity と有限値は false
internal object ApproximateComparison {
    // 2^-900・2^-800・2^900
    private val TINY_PRODUCT = Math.scalb(1.0, -900)
    private val TINY_DIFFERENCE = Math.scalb(1.0, -800)
    private val SCALE_UP = Math.scalb(1.0, 900)

    // tolerance は有限の 0 以上（呼び出し側が確かめる）
    fun isClose(a: Double, b: Double, tolerance: Double): Boolean {
        if (a == b) return true
        if (!a.isFinite() || !b.isFinite()) return false
        // |a - b| <= |a| + |b| <= 2 × max(1, |a|, |b|) のため、常に成り立つ
        if (tolerance >= 2.0) return true
        val m = max(1.0, max(abs(a), abs(b)))
        // 差がオーバーフローするのは、反対の符号の 2 つがどちらも 2^970 以上のときだけで、半分にしても正確
        if ((a - b).isInfinite()) return lessOrEqual(a * 0.5, b * 0.5, tolerance, m * 0.5)
        // 右辺だけがオーバーフローするなら、真の右辺は最大値より大きく、有限の左辺より大きい
        if ((tolerance * m).isInfinite()) return true
        if (tolerance * m < TINY_PRODUCT) {
            // 右辺が小さいとき、FMA の誤差の項が非正規化数になって丸められないよう拡大する。左辺が 2^-800 以下なら |a|・|b| < 2^-747 で m = 1、拡大は正確
            if (abs(a - b) > TINY_DIFFERENCE) return false
            return lessOrEqual(a * SCALE_UP, b * SCALE_UP, tolerance * SCALE_UP, m)
        }
        return lessOrEqual(a, b, tolerance, m)
    }

    // 有限の x・y・t（0 <= t < 2）・m（1 以上）で、|x - y| <= t × m を判定する（途中の値はオーバーフローしない）。
    // 差は TwoSum、積は FMA で丸めの誤差を正確に求め、丸めた値が等しいときだけ誤差の項で決める
    private fun lessOrEqual(x: Double, y: Double, t: Double, m: Double): Boolean {
        val s = x - y
        val virtual = s - x
        val error = (x - (s - virtual)) + (-y - virtual)
        val product = t * m
        val productError = Math.fma(t, m, -product)
        val difference = abs(s)
        if (difference != product) return difference < product
        return (if (s >= 0.0) error else -error) <= productError
    }
}
