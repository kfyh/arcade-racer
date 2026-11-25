package com.offroad.engine

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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

/**
 * Tests whether 2D segment [a]-[b] intersects an axis-aligned box centered at origin
 * with half-extents ([hx], [hy]).
 */
fun segmentIntersectsAabb(a: Vec2, b: Vec2, hx: Float, hy: Float): Boolean {
    var tMin = 0f
    var tMax = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y

    if (abs(dx) < 1e-8f) {
        if (a.x < -hx || a.x > hx) return false
    } else {
        val invD = 1f / dx
        var t1 = (-hx - a.x) * invD
        var t2 = (hx - a.x) * invD
        if (t1 > t2) {
            val tmp = t1
            t1 = t2
            t2 = tmp
        }
        tMin = maxOf(tMin, t1)
        tMax = minOf(tMax, t2)
        if (tMin > tMax) return false
    }

    if (abs(dy) < 1e-8f) {
        if (a.y < -hy || a.y > hy) return false
    } else {
        val invD = 1f / dy
        var t1 = (-hy - a.y) * invD
        var t2 = (hy - a.y) * invD
        if (t1 > t2) {
            val tmp = t1
            t1 = t2
            t2 = tmp
        }
        tMin = maxOf(tMin, t1)
        tMax = minOf(tMax, t2)
        if (tMin > tMax) return false
    }

    return true
}

/**
 * Tests whether an oriented bounding box at [center] rotated by [heading] with half-extents
 * ([halfLength], [halfWidth]) intersects line segment [segA]-[segB].
 */
fun obbIntersectsSegment(
    center: Vec2,
    heading: Float,
    halfLength: Float,
    halfWidth: Float,
    segA: Vec2,
    segB: Vec2
): Boolean {
    val fwd = Vec2(cos(heading), sin(heading))
    val right = Vec2(-fwd.y, fwd.x)
    val da = segA - center
    val db = segB - center
    val localA = Vec2(da.dot(fwd), da.dot(right))
    val localB = Vec2(db.dot(fwd), db.dot(right))
    return segmentIntersectsAabb(localA, localB, halfLength, halfWidth)
}
