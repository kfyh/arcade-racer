package com.offroad.engine

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * 2D vector in normalized track coordinates (0.0..1.0) or velocity units per second.
 */
@Serializable
data class Vec2(
    val x: Float = 0f,
    val y: Float = 0f
) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)

    operator fun div(scalar: Float): Vec2 = Vec2(x / scalar, y / scalar)

    fun lengthSquared(): Float = x * x + y * y

    fun length(): Float = sqrt(lengthSquared())

    fun normalized(): Vec2 {
        val len = length()
        return if (len > 1e-6f) Vec2(x / len, y / len) else Vec2(0f, 0f)
    }

    fun distanceTo(other: Vec2): Float = (this - other).length()

    fun dot(other: Vec2): Float = x * other.x + y * other.y
}

/**
 * Wraps an angle in radians into [-PI, PI].
 */
fun normalizeAngle(angle: Float): Float {
    var a = angle
    val twoPi = (2.0 * PI).toFloat()
    val pi = PI.toFloat()
    while (a > pi) a -= twoPi
    while (a < -pi) a += twoPi
    return a
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Minimum Euclidean distance from point [p] to line segment [a]-[b].
 */
fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
    val ab = b - a
    val lenSq = ab.lengthSquared()
    if (lenSq < 1e-8f) return p.distanceTo(a)
    val t = ((p - a).dot(ab) / lenSq).coerceIn(0f, 1f)
    val proj = a + ab * t
    return p.distanceTo(proj)
}
