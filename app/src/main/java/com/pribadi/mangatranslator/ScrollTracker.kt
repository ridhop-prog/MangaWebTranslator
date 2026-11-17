package com.pribadi.mangatranslator

import android.graphics.Rect
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real-time visual scroll tracker operating directly on ImageReader RGBA_8888 buffers.
 * Computes sub-pixel vertical scroll displacement with zero per-frame heap allocations,
 * masked against overlay regions to avoid feedback loops.
 */
class ScrollTracker(
    private val onSceneChanged: (() -> Unit)? = null
) {
    companion object {
        private const val NUM_COLS = 16
        private const val NUM_BANDS = 16
        private const val MAX_FEATURES = 256
        private const val SEARCH_RADIUS_ROWS = 180
        private const val COARSE_STRIDE = 3
        private const val EDGE_THRESHOLD_PRIMARY = 10
        private const val EDGE_THRESHOLD_FALLBACK = 2
        private const val MIN_PRIMARY_FEATURES = 40
        private const val STATIONARY_ERROR_THRESHOLD = 1.8f
        private const val SCENE_CHANGE_ERROR_THRESHOLD = 36f
        private const val SCENE_CHANGE_CONSECUTIVE_FRAMES = 2
        private const val TRUNCATED_LOSS_CAP = 80
    }

    private class FrameSignature(
        val numRows: Int,
        val numCols: Int
    ) {
        val totalPoints = numRows * numCols
        val luma = ByteArray(totalPoints)
        val hGrad = ByteArray(totalPoints)
        val vGrad = ByteArray(totalPoints)
        val valid = BooleanArray(totalPoints)
        val featureRows = IntArray(MAX_FEATURES)
        val featureCols = IntArray(MAX_FEATURES)
        var featureCount = 0
        var timestampMs = 0L
        var isPopulated = false

        fun clearFeatures() {
            featureCount = 0
            isPopulated = false
        }
    }

    private val lock = Any()

    @Volatile
    var currentShiftY: Float = 0f
        private set

    @Volatile
    var velocityPxPerMs: Float = 0f
        private set

    @Volatile
    private var lastScrollMoveTimeMs: Long = 0L

    private var frameWidth = 0
    private var frameHeight = 0
    private var startY = 0
    private var endY = 0
    private var sampleStepY = 2
    private var numRows = 0
    private val colX = IntArray(NUM_COLS)

    // Three pooled frames: no allocations after initial setup for a given resolution
    private var prevFrame: FrameSignature? = null
    private var curFrame: FrameSignature? = null
    private var anchorFrame: FrameSignature? = null

    private var anchorShiftY = 0f
    private var lastStepDyRows = 0f
    private var badMatchCount = 0
    private var pendingAnchorReset = false

    // Scratch buffers reused per frame for salient feature extraction & coarse search
    private val bestBandColRow = IntArray(NUM_BANDS * NUM_COLS)
    private val bestBandColEnergy = IntArray(NUM_BANDS * NUM_COLS)
    private val colRects = Array(NUM_COLS) { ArrayList<Rect>(8) }
    private val topCoarseRows = IntArray(3)
    private val topCoarseErrors = FloatArray(3)

    fun isScrollingSettled(settleDurationMs: Long): Boolean {
        val lastMove = lastScrollMoveTimeMs
        if (lastMove == 0L) return true
        return (SystemClock.uptimeMillis() - lastMove) >= settleDurationMs
    }

    /**
     * Resets scroll state and anchor (e.g. for a fresh full-screen scan).
     */
    fun reset(initialShiftY: Float = 0f) {
        synchronized(lock) {
            currentShiftY = initialShiftY
            anchorShiftY = initialShiftY
            velocityPxPerMs = 0f
            lastStepDyRows = 0f
            badMatchCount = 0
            lastScrollMoveTimeMs = 0L
            prevFrame?.clearFeatures()
            curFrame?.clearFeatures()
            anchorFrame?.clearFeatures()
            pendingAnchorReset = false
        }
    }

    /**
     * Marks the next captured frame as the new reference anchor without losing currentShiftY.
     */
    fun requestAnchorReset() {
        synchronized(lock) {
            pendingAnchorReset = true
            velocityPxPerMs = 0f
            lastStepDyRows = 0f
            badMatchCount = 0
        }
    }

    /**
     * Processes an RGBA_8888 ImageReader buffer and updates [currentShiftY] and [velocityPxPerMs].
     * Returns true if scroll offset changed noticeably.
     */
    fun processFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        maskedRects: List<Rect>
    ): Boolean {
        if (width < 64 || height < 64 || pixelStride < 3) return false

        var sceneChangedCallback = false
        var shiftUpdated = false

        synchronized(lock) {
            ensureGrid(width, height)

            val prev = prevFrame ?: return false
            val cur = curFrame ?: return false
            val anchor = anchorFrame ?: return false

            val nowMs = SystemClock.uptimeMillis()
            extractFrameSignature(buffer, width, height, pixelStride, rowStride, maskedRects, cur, nowMs)

            if (pendingAnchorReset || !prev.isPopulated) {
                pendingAnchorReset = false
                copySignature(cur, anchor)
                anchorShiftY = currentShiftY
                swapPrevAndCur()
                velocityPxPerMs = 0f
                lastStepDyRows = 0f
                badMatchCount = 0
                return false
            }

            if (prev.featureCount < 6) {
                copySignature(cur, anchor)
                anchorShiftY = currentShiftY
                swapPrevAndCur()
                velocityPxPerMs = 0f
                return false
            }

            // Stage 1: Stationary Check at dy = 0, -1, +1
            val err0 = evaluateShift(prev, cur, 0)
            if (err0 >= 0f && err0 < STATIONARY_ERROR_THRESHOLD) {
                val errMinus = evaluateShift(prev, cur, -1)
                val errPlus = evaluateShift(prev, cur, 1)
                val leMinus = errMinus < 0f || err0 <= errMinus
                val lePlus = errPlus < 0f || err0 <= errPlus
                if (leMinus && lePlus) {
                    velocityPxPerMs = 0f
                    lastStepDyRows = 0f
                    badMatchCount = 0
                    swapPrevAndCur()
                    return false
                }
            }

            // Stage 2: Coarse-to-Fine Frame-to-Frame Search
            val dtMs = (nowMs - prev.timestampMs).coerceIn(1L, 200L).toFloat()
            val predictedRows = if (abs(lastStepDyRows) > 0.5f && dtMs < 80f) {
                lastStepDyRows.roundToInt().coerceIn(-(numRows / 2), numRows / 2)
            } else {
                0
            }

            val maxSearchRow = maxOf(1, numRows - 24)
            val minDy = (predictedRows - SEARCH_RADIUS_ROWS).coerceAtLeast(-maxSearchRow)
            val maxDy = (predictedRows + SEARCH_RADIUS_ROWS).coerceAtMost(maxSearchRow)

            topCoarseErrors[0] = Float.MAX_VALUE
            topCoarseErrors[1] = Float.MAX_VALUE
            topCoarseErrors[2] = Float.MAX_VALUE
            topCoarseRows[0] = 0
            topCoarseRows[1] = 0
            topCoarseRows[2] = 0

            for (dy in minDy..maxDy step COARSE_STRIDE) {
                val err = evaluateShift(prev, cur, dy)
                if (err >= 0f) {
                    insertCoarseCandidate(dy, err)
                }
            }

            if (topCoarseErrors[0] == Float.MAX_VALUE) {
                badMatchCount++
                if (badMatchCount >= SCENE_CHANGE_CONSECUTIVE_FRAMES) {
                    badMatchCount = 0
                    copySignature(cur, anchor)
                    anchorShiftY = currentShiftY
                    velocityPxPerMs = 0f
                    lastStepDyRows = 0f
                    sceneChangedCallback = true
                }
                swapPrevAndCur()
                if (sceneChangedCallback) {
                    onSceneChanged?.invoke()
                }
                return false
            }

            // Fine refinement at stride 1 around top 3 coarse candidates and 0
            var bestRow = 0
            var bestErr = Float.MAX_VALUE

            val candStarts = intArrayOf(
                topCoarseRows[0],
                topCoarseRows[1],
                topCoarseRows[2],
                0
            )
            for (cIdx in candStarts.indices) {
                val center = candStarts[cIdx]
                if (cIdx in 1..2 && topCoarseErrors[cIdx] == Float.MAX_VALUE) continue
                val fMin = (center - COARSE_STRIDE).coerceAtLeast(-maxSearchRow)
                val fMax = (center + COARSE_STRIDE).coerceAtMost(maxSearchRow)
                for (dy in fMin..fMax) {
                    val err = evaluateShift(prev, cur, dy)
                    if (err >= 0f && err < bestErr) {
                        bestErr = err
                        bestRow = dy
                    }
                }
            }

            // Scene change / large jump check
            if (bestErr == Float.MAX_VALUE || bestErr > SCENE_CHANGE_ERROR_THRESHOLD) {
                badMatchCount++
                if (badMatchCount >= SCENE_CHANGE_CONSECUTIVE_FRAMES) {
                    badMatchCount = 0
                    copySignature(cur, anchor)
                    anchorShiftY = currentShiftY
                    velocityPxPerMs = 0f
                    lastStepDyRows = 0f
                    sceneChangedCallback = true
                }
                swapPrevAndCur()
                if (sceneChangedCallback) {
                    onSceneChanged?.invoke()
                }
                return false
            }

            badMatchCount = 0

            // Parabolic sub-row interpolation for frame-to-frame step
            val subOffset = computeParabolicOffset(prev, cur, bestRow, bestErr, maxSearchRow)
            val exactStepRows = bestRow + subOffset
            val stepDyPixels = exactStepRows * sampleStepY

            var newShiftY = currentShiftY + stepDyPixels

            // Stage 3: Drift-Free Anchor Refinement & Re-Anchoring
            if (anchor.isPopulated && anchor.featureCount >= 10 &&
                abs(newShiftY - anchorShiftY) < frameHeight * 0.28f
            ) {
                val expectedAnchorRows = ((newShiftY - anchorShiftY) / sampleStepY).roundToInt()
                val aMin = (expectedAnchorRows - 4).coerceAtLeast(-maxSearchRow)
                val aMax = (expectedAnchorRows + 4).coerceAtMost(maxSearchRow)

                var anchorBestRow = expectedAnchorRows
                var anchorBestErr = Float.MAX_VALUE
                for (dy in aMin..aMax) {
                    val err = evaluateShift(anchor, cur, dy)
                    if (err >= 0f && err < anchorBestErr) {
                        anchorBestErr = err
                        anchorBestRow = dy
                    }
                }

                if (anchorBestErr < 26f && anchorBestErr <= bestErr * 1.35f + 4f) {
                    val anchorSub = computeParabolicOffset(anchor, cur, anchorBestRow, anchorBestErr, maxSearchRow)
                    newShiftY = anchorShiftY + (anchorBestRow + anchorSub) * sampleStepY
                }
            }

            val deltaY = newShiftY - currentShiftY
            if (abs(deltaY) >= 0.35f) {
                currentShiftY = newShiftY
                val instVel = deltaY / dtMs
                velocityPxPerMs = if (abs(velocityPxPerMs) < 0.01f) {
                    instVel
                } else {
                    instVel * 0.65f + velocityPxPerMs * 0.35f
                }
                lastStepDyRows = exactStepRows
                lastScrollMoveTimeMs = nowMs
                shiftUpdated = true
            } else {
                if (bestRow == 0) {
                    velocityPxPerMs = 0f
                    lastStepDyRows = 0f
                }
            }

            // Re-anchor when cumulative distance from anchor exceeds 22% of screen height
            if (!anchor.isPopulated || abs(currentShiftY - anchorShiftY) > frameHeight * 0.22f) {
                copySignature(cur, anchor)
                anchorShiftY = currentShiftY
            }

            swapPrevAndCur()
        }

        if (sceneChangedCallback) {
            onSceneChanged?.invoke()
        }
        return shiftUpdated
    }

    private fun ensureGrid(width: Int, height: Int) {
        if (width == frameWidth && height == frameHeight && prevFrame != null) return

        frameWidth = width
        frameHeight = height
        startY = (height * 0.09f).toInt().coerceIn(0, height - 1)
        endY = (height * 0.91f).toInt().coerceIn(startY + 1, height)
        sampleStepY = maxOf(2, height / 900)
        numRows = ((endY - startY) / sampleStepY) + 1

        val xStart = width * 0.06f
        val xEnd = width * 0.94f
        val xSpan = (xEnd - xStart).coerceAtLeast(1f)
        for (c in 0 until NUM_COLS) {
            colX[c] = (xStart + (xSpan * c) / (NUM_COLS - 1))
                .roundToInt()
                .coerceIn(0, (width - 5).coerceAtLeast(0))
        }

        prevFrame = FrameSignature(numRows, NUM_COLS)
        curFrame = FrameSignature(numRows, NUM_COLS)
        anchorFrame = FrameSignature(numRows, NUM_COLS)
        pendingAnchorReset = true
    }

    private fun extractFrameSignature(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        maskedRects: List<Rect>,
        out: FrameSignature,
        timestampMs: Long
    ) {
        out.timestampMs = timestampMs
        out.clearFeatures()

        val luma = out.luma
        val hGrad = out.hGrad
        val vGrad = out.vGrad
        val valid = out.valid
        val cap = buffer.capacity()

        for (c in 0 until NUM_COLS) {
            colRects[c].clear()
            val x = colX[c]
            val xRight = (x + 4).coerceAtMost(width - 1)
            for (i in maskedRects.indices) {
                val rect = maskedRects[i]
                if (xRight >= rect.left && x <= rect.right) {
                    colRects[c].add(rect)
                }
            }
        }

        var idx = 0
        for (r in 0 until numRows) {
            val y = startY + r * sampleStepY
            if (y >= height) {
                for (c in 0 until NUM_COLS) {
                    valid[idx++] = false
                }
                continue
            }
            val rowBase = y * rowStride

            for (c in 0 until NUM_COLS) {
                val rects = colRects[c]
                var isMasked = false
                for (i in rects.indices) {
                    val rect = rects[i]
                    if (y >= rect.top && y <= rect.bottom) {
                        isMasked = true
                        break
                    }
                }

                if (isMasked) {
                    valid[idx] = false
                    luma[idx] = 0
                    hGrad[idx] = 0
                    vGrad[idx] = 0
                    idx++
                    continue
                }

                val x = colX[c]
                val off = rowBase + x * pixelStride
                val x2 = (x + 4).coerceAtMost(width - 1)
                val off2 = rowBase + x2 * pixelStride

                if (off + 2 >= cap || off2 + 2 >= cap) {
                    valid[idx++] = false
                    continue
                }

                val r0 = buffer.get(off).toInt() and 0xFF
                val g0 = buffer.get(off + 1).toInt() and 0xFF
                val b0 = buffer.get(off + 2).toInt() and 0xFF
                val lum = (77 * r0 + 150 * g0 + 29 * b0) shr 8

                val r1 = buffer.get(off2).toInt() and 0xFF
                val g1 = buffer.get(off2 + 1).toInt() and 0xFF
                val b1 = buffer.get(off2 + 2).toInt() and 0xFF
                val lumRight = (77 * r1 + 150 * g1 + 29 * b1) shr 8

                luma[idx] = lum.toByte()
                hGrad[idx] = (lumRight - lum).coerceIn(-127, 127).toByte()
                valid[idx] = true
                idx++
            }
        }

        bestBandColEnergy.fill(0)
        bestBandColRow.fill(-1)

        for (r in 1 until numRows - 1) {
            val band = ((r * NUM_BANDS) / numRows).coerceIn(0, NUM_BANDS - 1)
            val rowOff = r * NUM_COLS
            val prevRowOff = rowOff - NUM_COLS
            val nextRowOff = rowOff + NUM_COLS

            for (c in 0 until NUM_COLS) {
                val pIdx = rowOff + c
                if (!valid[pIdx] || !valid[prevRowOff + c] || !valid[nextRowOff + c]) {
                    vGrad[pIdx] = 0
                    continue
                }
                val lPrev = luma[prevRowOff + c].toInt() and 0xFF
                val lNext = luma[nextRowOff + c].toInt() and 0xFF
                val vg = (lNext - lPrev).coerceIn(-127, 127)
                vGrad[pIdx] = vg.toByte()

                val hg = hGrad[pIdx].toInt()
                val energy = abs(vg) + abs(hg)

                val cell = band * NUM_COLS + c
                if (energy > bestBandColEnergy[cell]) {
                    bestBandColEnergy[cell] = energy
                    bestBandColRow[cell] = r
                }
            }
        }

        var count = 0
        for (band in 0 until NUM_BANDS) {
            for (c in 0 until NUM_COLS) {
                val cell = band * NUM_COLS + c
                val row = bestBandColRow[cell]
                if (row >= 0 && bestBandColEnergy[cell] >= EDGE_THRESHOLD_PRIMARY) {
                    out.featureRows[count] = row
                    out.featureCols[count] = c
                    count++
                    if (count >= MAX_FEATURES) break
                }
            }
            if (count >= MAX_FEATURES) break
        }

        if (count < MIN_PRIMARY_FEATURES) {
            count = 0
            for (band in 0 until NUM_BANDS) {
                for (c in 0 until NUM_COLS) {
                    val cell = band * NUM_COLS + c
                    val row = bestBandColRow[cell]
                    if (row >= 0 && bestBandColEnergy[cell] >= EDGE_THRESHOLD_FALLBACK) {
                        out.featureRows[count] = row
                        out.featureCols[count] = c
                        count++
                        if (count >= MAX_FEATURES) break
                    }
                }
                if (count >= MAX_FEATURES) break
            }
        }

        out.featureCount = count
        out.isPopulated = true
    }

    private fun evaluateShift(ref: FrameSignature, cur: FrameSignature, dyRows: Int): Float {
        val count = ref.featureCount
        if (count <= 0) return -1f

        val minRequired = maxOf(6, count / 6)
        var matched = 0
        var totalLoss = 0

        val refLuma = ref.luma
        val refVGrad = ref.vGrad
        val refHGrad = ref.hGrad
        val curLuma = cur.luma
        val curVGrad = cur.vGrad
        val curHGrad = cur.hGrad
        val curValid = cur.valid

        for (i in 0 until count) {
            val r = ref.featureRows[i]
            val targetR = r + dyRows
            if (targetR <= 0 || targetR >= numRows - 1) continue

            val c = ref.featureCols[i]
            val curIdx = targetR * NUM_COLS + c
            if (!curValid[curIdx]) continue

            val refIdx = r * NUM_COLS + c
            val dLuma = abs((refLuma[refIdx].toInt() and 0xFF) - (curLuma[curIdx].toInt() and 0xFF))
            val dVGrad = abs(refVGrad[refIdx].toInt() - curVGrad[curIdx].toInt())
            val dHGrad = abs(refHGrad[refIdx].toInt() - curHGrad[curIdx].toInt())

            val loss = minOf(TRUNCATED_LOSS_CAP, dLuma + (dVGrad shl 1) + dHGrad)
            totalLoss += loss
            matched++
        }

        if (matched < minRequired) return -1f
        return totalLoss.toFloat() / matched.toFloat()
    }

    private fun computeParabolicOffset(
        ref: FrameSignature,
        cur: FrameSignature,
        bestRow: Int,
        bestErr: Float,
        maxSearchRow: Int
    ): Float {
        if (bestRow - 1 < -maxSearchRow || bestRow + 1 > maxSearchRow) return 0f
        val errMinus = evaluateShift(ref, cur, bestRow - 1)
        val errPlus = evaluateShift(ref, cur, bestRow + 1)
        if (errMinus < 0f || errPlus < 0f) return 0f

        val denom = 2f * (errMinus - 2f * bestErr + errPlus)
        if (denom <= 1e-4f) return 0f
        return ((errMinus - errPlus) / denom).coerceIn(-0.5f, 0.5f)
    }

    private fun insertCoarseCandidate(dy: Int, err: Float) {
        if (err < topCoarseErrors[0]) {
            topCoarseErrors[2] = topCoarseErrors[1]
            topCoarseRows[2] = topCoarseRows[1]
            topCoarseErrors[1] = topCoarseErrors[0]
            topCoarseRows[1] = topCoarseRows[0]
            topCoarseErrors[0] = err
            topCoarseRows[0] = dy
        } else if (err < topCoarseErrors[1]) {
            topCoarseErrors[2] = topCoarseErrors[1]
            topCoarseRows[2] = topCoarseRows[1]
            topCoarseErrors[1] = err
            topCoarseRows[1] = dy
        } else if (err < topCoarseErrors[2]) {
            topCoarseErrors[2] = err
            topCoarseRows[2] = dy
        }
    }

    private fun copySignature(src: FrameSignature, dst: FrameSignature) {
        System.arraycopy(src.luma, 0, dst.luma, 0, src.totalPoints)
        System.arraycopy(src.hGrad, 0, dst.hGrad, 0, src.totalPoints)
        System.arraycopy(src.vGrad, 0, dst.vGrad, 0, src.totalPoints)
        System.arraycopy(src.valid, 0, dst.valid, 0, src.totalPoints)
        System.arraycopy(src.featureRows, 0, dst.featureRows, 0, src.featureCount)
        System.arraycopy(src.featureCols, 0, dst.featureCols, 0, src.featureCount)
        dst.featureCount = src.featureCount
        dst.timestampMs = src.timestampMs
        dst.isPopulated = src.isPopulated
    }

    private fun swapPrevAndCur() {
        val tmp = prevFrame
        prevFrame = curFrame
        curFrame = tmp
    }
}
