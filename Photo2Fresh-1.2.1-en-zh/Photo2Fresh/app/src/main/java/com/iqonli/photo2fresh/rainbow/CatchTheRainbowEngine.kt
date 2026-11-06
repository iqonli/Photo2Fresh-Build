package com.iqonli.photo2fresh.rainbow

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.graphics.Bitmap
import com.iqonli.photo2fresh.regression.RawRoiPatch
import com.iqonli.photo2fresh.regression.RgbImage
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.iqonli.photo2fresh.config.RainbowConfig
import com.iqonli.photo2fresh.inference.HydraliskCandidate
import com.iqonli.photo2fresh.opencv.ImagePoint
import com.iqonli.photo2fresh.opencv.OpenCvRuntime
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android port of the geometry and color path in CatchTheRainbow alphaV6.1.
 * C11-C33 YOLO class identities are not trusted by the grid fit; geometry is primary.
 */
class CatchTheRainbowEngine(
    private val config: RainbowConfig,
) {
    private val slotNames = listOf(
        "C11", "C12", "C13",
        "C21", "C22", "C23",
        "C31", "C32", "C33",
    )

    fun process(
        rectifiedBitmap: Bitmap,
        hydraliskCandidates: List<HydraliskCandidate>,
    ): RainbowResult {
        check(OpenCvRuntime.initialized) { OpenCvRuntime.error ?: "OpenCV 尚未初始化" }
        require(hydraliskCandidates.isNotEmpty()) { "传感区域分割模型没有返回候选" }

        val imageWidth = rectifiedBitmap.width
        val imageHeight = rectifiedBitmap.height
        val candidates = hydraliskCandidates.mapIndexedNotNull { index, item ->
            item.toGeometry(index)
        }
        require(candidates.isNotEmpty()) { "传感区域分割模型候选无法转换为有效几何" }

        val gxCandidate = selectBar(candidates, BarType.GX, imageWidth, imageHeight, emptySet())
        val gx = gxCandidate?.toBar(BarType.GX)
        val excluded = mutableSetOf<Int>()
        gxCandidate?.let { excluded += it.index }

        val gyCandidate = selectBar(candidates, BarType.GY, imageWidth, imageHeight, excluded)
        val gy = gyCandidate?.toBar(BarType.GY)
        gyCandidate?.let { excluded += it.index }

        val w0 = identifyW0(candidates, gx, gy, excluded)
        w0?.let { excluded += it.index }
        val cPool = buildCCandidatePool(candidates, gx, gy, excluded, imageWidth, imageHeight)
        val templateWithSource = buildTemplateFromBars(gx, gy)
            ?: buildTemplateFromCandidates(cPool)
        val grid = ransacGrid(
            candidates = cPool,
            template = templateWithSource.first,
            templateSource = templateWithSource.second,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        val regions = buildRegions(grid, cPool)
        val graySampleSelection = buildGraySamples(
            regions = regions,
            grid = grid,
            gx = gx,
            gy = gy,
            w0 = w0,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        val graySamples = graySampleSelection.samples

        val rgba = Mat()
        val rgb = Mat()
        Utils.bitmapToMat(rectifiedBitmap, rgba)
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        val sampleStats = linkedMapOf<String, ColorStats>()
        val regionStats = linkedMapOf<String, ColorStats>()
        val rawRois = linkedMapOf<String, RgbImage>()
        try {
            graySamples.forEach { sample ->
                sampleStats[sample.name] = colorStatistics(
                    rgb,
                    sample.center,
                    sample.side,
                    sample.side,
                    sample.angleDegrees,
                )
            }
            regions.forEach { region ->
                rawRois[region.name] = cropRawRoi(rgb, region)
                regionStats[region.name] = colorStatistics(
                    rgb,
                    region.center,
                    region.roiSide,
                    region.roiSide,
                    region.angleDegrees,
                    residual = region.residual,
                    residualThreshold = grid.thresholdPx,
                )
            }
        } finally {
            rgb.release()
        }

        val correctionByName = linkedMapOf<String, CorrectionResult>()
        val outputColors = regions.map { region ->
            val rawStats = requireNotNull(regionStats[region.name])
            val columnGray = requireNotNull(sampleStats["Gxx${region.col + 1}"])
            val rowGray = requireNotNull(sampleStats["Gyy${region.row + 1}"])
            val correction = calibrate(rawStats.meanRgb, columnGray, rowGray)
            correctionByName[region.name] = correction
            val corrected = correction.corrected
            val flags = linkedSetOf<String>()
            flags += rawStats.flags
            flags += columnGray.flags.map { "GXX_$it" }
            flags += rowGray.flags.map { "GYY_$it" }
            flags += correction.flags
            if (region.recovered) flags += "RANSAC_RECOVERED"
            if (region.reassigned) flags += "YOLO_CLASS_REASSIGNED"
            RainbowRegionColor(
                name = region.name,
                rawRgb = rawStats.meanRgb,
                correctedRgb = corrected,
                lab = rgbToLabD65(corrected),
                hsl = rgbToHsl(corrected),
                qualityFlags = flags.toList(),
            )
        }

        val globalFlags = linkedSetOf<String>()
        if (graySampleSelection.usedFallback) globalFlags += "GRAY_SAMPLE_GEOMETRY_FALLBACK"
        if (grid.assignment.size < 7) globalFlags += "LOW_GRID_INLIERS"
        if (regions.count { it.recovered } > 2) globalFlags += "MANY_RECOVERED_REGIONS"
        outputColors.flatMapTo(globalFlags) { color ->
            color.qualityFlags.filter {
                it.contains("CLIPPED") || it.contains("OUTSIDE") || it.contains("TOO_FEW")
            }
        }

        val debug = renderPreview(
            source = rectifiedBitmap,
            regions = regions,
            samples = graySamples,
            colors = outputColors,
            corrections = correctionByName,
            grid = grid,
            gx = gx,
            gy = gy,
            w0 = w0,
        )
        val geometry = org.json.JSONObject()
            .put("roiScale", config.roiScale).put("minInliers", config.minInliers).put("maxRecover", config.maxRecover)
            .put("ransacIterations", config.ransacIterations).put("residualStepRatio", config.residualStepRatio)
            .put("calibrationStrength", config.calibrationStrength).put("gainMin", config.gainMin).put("gainMax", config.gainMax)
            .put("standardGrayRgb", org.json.JSONArray(config.standardGrayRgb.toList()))
            .put("regions", org.json.JSONArray(regions.map { region -> org.json.JSONObject()
                .put("name", region.name).put("center", org.json.JSONArray(listOf(region.center.x, region.center.y)))
                .put("side", region.roiSide).put("angle", region.angleDegrees)
                .put("rawMeanRgb", org.json.JSONArray(regionStats.getValue(region.name).meanRgb.asArray().toList()))
            }))
            .put("graySamples", org.json.JSONArray(graySamples.map { sample -> org.json.JSONObject()
                .put("name", sample.name).put("center", org.json.JSONArray(listOf(sample.center.x, sample.center.y)))
                .put("side", sample.side).put("angle", sample.angleDegrees)
                .put("rawMeanRgb", org.json.JSONArray(sampleStats.getValue(sample.name).meanRgb.asArray().toList()))
            }))
            .put("candidates", org.json.JSONArray(hydraliskCandidates.map { candidate -> org.json.JSONObject()
                .put("classId", candidate.classId).put("confidence", candidate.confidence.toDouble())
                .put("polygon", org.json.JSONArray(candidate.polygon.map { org.json.JSONArray(listOf(it.x, it.y)) }))
            }))
        return RainbowResult(
            colors = outputColors,
            grid = RainbowGridSummary(
                inliers = grid.assignment.size,
                recovered = regions.count { it.recovered },
                reassigned = regions.count { it.reassigned },
                medianResidualPx = grid.medianResidual,
                thresholdPx = grid.thresholdPx,
                templateSource = grid.templateSource,
                graySampleSource = graySampleSelection.source,
                graySampleFallbackReason = graySampleSelection.fallbackReason,
            ),
            qualityFlags = globalFlags.toList(),
            debugBitmap = debug,
            assignedCenters = regions.associate { it.name to ImagePoint(it.center.x, it.center.y) },
            geometryJson = geometry.toString(2),
            rawRois = regions.map { region ->
                RawRoiPatch(region.name, rawRois.getValue(region.name), correctionByName.getValue(region.name).gains.copyOf())
            },
        )
    }

    private fun HydraliskCandidate.toGeometry(index: Int): CandidateGeometry? {
        val points = polygon.map { Point(it.x, it.y) }
        if (points.size < 3) return null
        val contour2f = MatOfPoint2f(*points.toTypedArray())
        return try {
            val area = abs(Imgproc.contourArea(contour2f))
            if (area < 4.0) return null
            val rect = Imgproc.minAreaRect(contour2f)
            if (rect.size.width <= 0.0 || rect.size.height <= 0.0) return null
            val center = polygonCentroid(points)
            val aspect = max(rect.size.width, rect.size.height) / max(1e-9, min(rect.size.width, rect.size.height))
            CandidateGeometry(
                index = index,
                predictedClass = classId,
                confidence = confidence.toDouble(),
                polygon = points,
                center = center,
                area = area,
                rect = rect,
                aspect = aspect,
            )
        } finally {
            contour2f.release()
        }
    }

    private fun polygonCentroid(points: List<Point>): Point {
        var twiceArea = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val cross = a.x * b.y - b.x * a.y
            twiceArea += cross
            cx += (a.x + b.x) * cross
            cy += (a.y + b.y) * cross
        }
        if (abs(twiceArea) < 1e-9) {
            return Point(points.map { it.x }.average(), points.map { it.y }.average())
        }
        return Point(cx / (3.0 * twiceArea), cy / (3.0 * twiceArea))
    }

    private fun selectBar(
        candidates: List<CandidateGeometry>,
        type: BarType,
        imageWidth: Int,
        imageHeight: Int,
        excluded: Set<Int>,
    ): CandidateGeometry? {
        return candidates.asSequence()
            .filterNot { it.index in excluded }
            .mapNotNull { candidate ->
                val axes = candidate.rectAxes()
                val aspect = axes.majorLength / max(1e-6, axes.minorLength)
                val horizontalness = abs(axes.major.x)
                val verticalness = abs(axes.major.y)
                val cx = candidate.center.x / max(1.0, imageWidth.toDouble())
                val cy = candidate.center.y / max(1.0, imageHeight.toDouble())
                val lengthFraction = axes.majorLength / max(imageWidth, imageHeight).toDouble()
                val expectedClass = if (type == BarType.GX) 1 else 2
                val otherClass = if (type == BarType.GX) 2 else 1
                val predictedBonus = when (candidate.predictedClass) {
                    expectedClass -> 1.15
                    otherClass -> -0.35
                    else -> 0.0
                }
                val score = if (type == BarType.GX) {
                    2.4 * ln(max(1.0, aspect)) + 2.0 * horizontalness +
                        0.9 * (1.0 - cy) + 0.35 * cx + 3.0 * lengthFraction + predictedBonus
                } else {
                    2.4 * ln(max(1.0, aspect)) + 2.0 * verticalness +
                        0.9 * (1.0 - cx) + 0.35 * cy + 3.0 * lengthFraction + predictedBonus
                }
                if (aspect >= 1.65 || candidate.predictedClass == expectedClass) score to candidate else null
            }
            .filter { it.first >= 2.0 }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun CandidateGeometry.toBar(type: BarType): BarGeometry {
        val axes = rectAxes()
        var major = axes.major
        if (type == BarType.GX && major.x < 0.0) major = major * -1.0
        if (type == BarType.GY && major.y < 0.0) major = major * -1.0
        return BarGeometry(
            type = type,
            center = Point(rect.center.x, rect.center.y),
            major = major,
            majorLength = axes.majorLength,
            minorLength = axes.minorLength,
            source = "detected_geometry",
            polygon = polygon,
        )
    }

    private fun identifyW0(
        candidates: List<CandidateGeometry>,
        gx: BarGeometry?,
        gy: BarGeometry?,
        excluded: Set<Int>,
    ): CandidateGeometry? {
        if (gx == null || gy == null) {
            return candidates.firstOrNull {
                it.index !in excluded && it.predictedClass == 0 && it.aspect < 2.5
            }
        }
        val intersection = lineIntersection(gx.center, gx.major, gy.center, gy.major)
            ?: Point((gx.center.x + gy.center.x) / 2.0, (gx.center.y + gy.center.y) / 2.0)
        val squares = candidates.filter { it.index !in excluded && it.aspect <= 2.4 }
        if (squares.isEmpty()) return null
        val medianSide = median(squares.map { sqrt(max(1.0, it.area)) })
        val best = squares.minByOrNull { candidate ->
            candidate.center.distanceTo(intersection) / max(1.0, medianSide) -
                if (candidate.predictedClass == 0) 0.75 else 0.0
        } ?: return null
        val rawDistance = best.center.distanceTo(intersection) / max(1.0, medianSide)
        return if (rawDistance <= 1.75 || best.predictedClass == 0) best else null
    }

    private fun buildCCandidatePool(
        candidates: List<CandidateGeometry>,
        gx: BarGeometry?,
        gy: BarGeometry?,
        excluded: Set<Int>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<CandidateGeometry> {
        val origin = if (gx != null && gy != null) {
            lineIntersection(gx.center, gx.major, gy.center, gy.major)
                ?: Point((gx.center.x + gy.center.x) / 2.0, (gx.center.y + gy.center.y) / 2.0)
        } else null
        val minimumArea = max(16.0, imageWidth * imageHeight * 1e-5)
        var pool = candidates.filter { candidate ->
            if (candidate.index in excluded || candidate.area < minimumArea || candidate.aspect > 3.8) {
                false
            } else if (origin != null && gx != null && gy != null) {
                val delta = candidate.center - origin
                val u = delta.dot(gx.major)
                val v = delta.dot(gy.major)
                u >= -0.08 * gx.majorLength && v >= -0.08 * gy.majorLength &&
                    u <= 1.40 * gx.majorLength && v <= 1.40 * gy.majorLength
            } else true
        }
        if (pool.size < 5) {
            pool = candidates.filter {
                it.index !in excluded && it.area >= minimumArea && it.aspect <= 4.5
            }
        }
        require(pool.size >= 3) { "可用于 C 网格拟合的候选不足：${pool.size}" }
        return deduplicate(pool)
    }

    private fun deduplicate(candidates: List<CandidateGeometry>): List<CandidateGeometry> {
        if (candidates.size <= 1) return candidates
        val medianArea = median(candidates.map { max(it.area, 1.0) })
        val medianSide = sqrt(medianArea)
        val centerGate = max(4.0, 0.30 * medianSide)
        val ordered = candidates.sortedByDescending {
            -abs(ln(max(it.aspect, 1e-6))) -
                0.25 * abs(ln(max(it.area, 1e-6) / max(medianArea, 1e-6))) +
                0.05 * it.confidence
        }
        val kept = mutableListOf<CandidateGeometry>()
        ordered.forEach { candidate ->
            val duplicate = kept.any { existing ->
                val centerDistance = candidate.center.distanceTo(existing.center)
                val iou = polygonIou(candidate.polygon, existing.polygon)
                val side = min(sqrt(max(candidate.area, 1.0)), sqrt(max(existing.area, 1.0)))
                centerDistance <= max(3.0, 0.18 * side) ||
                    (centerDistance <= centerGate && iou >= 0.30) || iou >= 0.72
            }
            if (!duplicate) kept += candidate
        }
        return kept.sortedBy { it.index }
    }

    private fun polygonIou(a: List<Point>, b: List<Point>): Double {
        val hullA = MatOfPoint()
        val hullB = MatOfPoint()
        val aInput = MatOfPoint(*a.toTypedArray())
        val bInput = MatOfPoint(*b.toTypedArray())
        val intersection = MatOfPoint2f()
        return try {
            val hullIndicesA = org.opencv.core.MatOfInt()
            val hullIndicesB = org.opencv.core.MatOfInt()
            try {
                Imgproc.convexHull(aInput, hullIndicesA)
                Imgproc.convexHull(bInput, hullIndicesB)
                hullA.fromList(hullIndicesA.toList().map { a[it] })
                hullB.fromList(hullIndicesB.toList().map { b[it] })
                val areaA = abs(Imgproc.contourArea(hullA))
                val areaB = abs(Imgproc.contourArea(hullB))
                if (areaA < 1e-6 || areaB < 1e-6) return 0.0
                val interArea = Imgproc.intersectConvexConvex(
                    MatOfPoint2f(*hullA.toArray()),
                    MatOfPoint2f(*hullB.toArray()),
                    intersection,
                ).toDouble().coerceAtLeast(0.0)
                val union = areaA + areaB - interArea
                if (union > 1e-6) interArea / union else 0.0
            } finally {
                hullIndicesA.release()
                hullIndicesB.release()
            }
        } catch (_: Exception) {
            0.0
        } finally {
            aInput.release()
            bInput.release()
            hullA.release()
            hullB.release()
            intersection.release()
        }
    }

    private fun buildTemplateFromBars(gx: BarGeometry?, gy: BarGeometry?): Pair<List<Point>, String>? {
        if (gx == null || gy == null) return null
        var ex = gx.major
        var ey = gy.major
        if (ex.x < 0.0) ex = ex * -1.0
        if (ey.y < 0.0) ey = ey * -1.0
        if (abs(ex.cross(ey)) < 0.25) return null
        val points = mutableListOf<Point>()
        for (row in 0..2) {
            val rowAnchor = gy.center + ey * ((row - 1) * gy.majorLength / 3.0)
            for (col in 0..2) {
                val colAnchor = gx.center + ex * ((col - 1) * gx.majorLength / 3.0)
                val point = lineIntersection(colAnchor, ey, rowAnchor, ex) ?: return null
                points += point
            }
        }
        return points to "Gx_Gy_geometry"
    }

    private fun buildTemplateFromCandidates(candidates: List<CandidateGeometry>): Pair<List<Point>, String> {
        val xs = kMeans1d(candidates.map { it.center.x })
        val ys = kMeans1d(candidates.map { it.center.y })
        return List(9) { slot -> Point(xs[slot % 3], ys[slot / 3]) } to
            "coordinate_clustering_geometry_only"
    }

    private fun kMeans1d(values: List<Double>): List<Double> {
        if (values.size < 3) {
            val low = values.minOrNull() ?: 0.0
            val high = values.maxOrNull() ?: low
            return listOf(low, (low + high) / 2.0, high)
        }
        val sorted = values.sorted()
        var centers = mutableListOf(
            percentile(sorted, 0.15),
            percentile(sorted, 0.50),
            percentile(sorted, 0.85),
        )
        repeat(40) {
            val groups = List(3) { mutableListOf<Double>() }
            values.forEach { value ->
                val index = centers.indices.minByOrNull { abs(value - centers[it]) } ?: 0
                groups[index] += value
            }
            val next = centers.toMutableList()
            groups.forEachIndexed { index, group -> if (group.isNotEmpty()) next[index] = median(group) }
            next.sort()
            if (next.indices.maxOf { abs(next[it] - centers[it]) } < 1e-5) {
                centers = next
                return@repeat
            }
            centers = next
        }
        return centers
    }

    private fun ransacGrid(
        candidates: List<CandidateGeometry>,
        template: List<Point>,
        templateSource: String,
        imageWidth: Int,
        imageHeight: Int,
    ): GridFit {
        require(candidates.size >= 3) { "C 候选少于 3 个，无法 RANSAC" }
        val templateVx = template[1] - template[0]
        val templateVy = template[3] - template[0]
        val baseStep = max(1.0, (templateVx.length() + templateVy.length()) / 2.0)
        var threshold = clamp(config.residualStepRatio * baseStep, 10.0, 140.0)
        val nearest = List(9) { slot ->
            candidates.indices.sortedBy { template[slot].distanceTo(candidates[it].center) }
                .take(min(7, candidates.size))
        }
        val random = Random(20260722L)
        var best: GridFit? = fitGrid((0..8).toList(), template)?.let { model ->
            evaluateGrid(model, candidates, template, templateSource, imageWidth, imageHeight)
        }

        val triples = nonCollinearTriples()
        repeat(config.ransacIterations) {
            val slots = triples[random.nextInt(triples.size)]
            val picked = slots.map { slot -> nearest[slot][random.nextInt(nearest[slot].size)] }
            if (picked.toSet().size != 3) return@repeat
            val centers = picked.map { candidates[it].center }
            val model = fitGrid(slots, centers) ?: return@repeat
            val evaluated = evaluateGrid(model, candidates, template, templateSource, imageWidth, imageHeight)
                ?: return@repeat
            if (best == null || evaluated.score > requireNotNull(best).score) best = evaluated
        }
        var result = requireNotNull(best) { "RANSAC 未找到有效 3×3 仿射网格" }
        require(result.assignment.size >= 3) { "RANSAC 可靠内点少于 3" }

        repeat(3) {
            val slots = result.assignment.keys.sorted()
            val centers = slots.map { candidates[requireNotNull(result.assignment[it])].center }
            val refit = fitGrid(slots, centers) ?: return@repeat
            val evaluated = evaluateGrid(refit, candidates, template, templateSource, imageWidth, imageHeight)
                ?: return@repeat
            if (evaluated.assignment.size >= 3) result = evaluated
        }
        threshold = result.thresholdPx
        require(result.assignment.size >= config.minInliers) {
            "RANSAC 可靠 C 区域只有 ${result.assignment.size}/9，低于设置的 ${config.minInliers}/9"
        }
        require(9 - result.assignment.size <= config.maxRecover) {
            "缺失 C 区域 ${9 - result.assignment.size} 个，超过允许补全的 ${config.maxRecover} 个"
        }
        return result.copy(thresholdPx = threshold)
    }

    private fun evaluateGrid(
        model: GridModel,
        candidates: List<CandidateGeometry>,
        template: List<Point>,
        templateSource: String,
        imageWidth: Int,
        imageHeight: Int,
    ): GridFit? {
        if (!validateGrid(model, candidates, template, imageWidth, imageHeight)) return null
        val predicted = predictGrid(model)
        val localThreshold = clamp(
            config.residualStepRatio * ((model.vx.length() + model.vy.length()) / 2.0),
            10.0,
            140.0,
        )
        val assignment = greedyAssignment(predicted, candidates, localThreshold)
        if (assignment.first.size < 3) return null
        val residualValues = assignment.second.values
        val score = GridScore(
            count = assignment.first.size,
            medianResidualScore = -median(residualValues.toList()) / max(1.0, localThreshold),
            meanResidualScore = -residualValues.average() / max(1.0, localThreshold),
        )
        return GridFit(
            model = model,
            assignment = assignment.first,
            residuals = assignment.second,
            medianResidual = median(residualValues.toList()),
            thresholdPx = localThreshold,
            score = score,
            templateSource = templateSource,
        )
    }

    private fun validateGrid(
        model: GridModel,
        candidates: List<CandidateGeometry>,
        template: List<Point>,
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean {
        val lx = model.vx.length()
        val ly = model.vy.length()
        if (lx < 5.0 || ly < 5.0) return false
        val angle = angleBetween(model.vx, model.vy)
        if (angle !in 42.0..138.0) return false
        if (model.vx.cross(model.vy) <= 0.0) return false
        if (model.vx.x < 0.10 * lx || model.vy.y < 0.10 * ly) return false
        if (lx / max(1e-6, ly) !in 0.42..2.4) return false
        val medianSide = median(candidates.map { sqrt(max(1.0, it.area)) })
        if (lx < 1.05 * medianSide || ly < 1.05 * medianSide) return false
        if (lx > 7.0 * medianSide || ly > 7.0 * medianSide) return false
        val predicted = predictGrid(model)
        if (predicted.any {
                it.x < -0.10 * imageWidth || it.x > 1.10 * imageWidth ||
                    it.y < -0.10 * imageHeight || it.y > 1.10 * imageHeight
            }) return false
        val tx = (template[1] - template[0]).length()
        val ty = (template[3] - template[0]).length()
        if (tx > 1.0 && lx / tx !in 0.48..1.95) return false
        if (ty > 1.0 && ly / ty !in 0.48..1.95) return false
        if (median(predicted.zip(template).map { (a, b) -> a.distanceTo(b) }) > 1.35 * max(tx, ty)) return false
        return true
    }

    private fun fitGrid(slots: List<Int>, centers: List<Point>): GridModel? {
        if (slots.size < 3 || slots.size != centers.size) return null
        val ata = Array(3) { DoubleArray(3) }
        val atx = DoubleArray(3)
        val aty = DoubleArray(3)
        slots.indices.forEach { index ->
            val slot = slots[index]
            val row = slot / 3
            val col = slot % 3
            val v = doubleArrayOf(1.0, col.toDouble(), row.toDouble())
            for (i in 0..2) {
                atx[i] += v[i] * centers[index].x
                aty[i] += v[i] * centers[index].y
                for (j in 0..2) ata[i][j] += v[i] * v[j]
            }
        }
        val px = solve3x3(ata, atx) ?: return null
        val py = solve3x3(ata, aty) ?: return null
        return GridModel(
            origin = Point(px[0], py[0]),
            vx = Point(px[1], py[1]),
            vy = Point(px[2], py[2]),
        )
    }

    private fun solve3x3(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val a = Array(3) { row -> DoubleArray(4) { col -> if (col < 3) matrix[row][col] else rhs[row] } }
        for (pivot in 0..2) {
            val best = (pivot..2).maxByOrNull { abs(a[it][pivot]) } ?: pivot
            if (abs(a[best][pivot]) < 1e-10) return null
            if (best != pivot) {
                val temp = a[pivot]
                a[pivot] = a[best]
                a[best] = temp
            }
            val divisor = a[pivot][pivot]
            for (col in pivot..3) a[pivot][col] /= divisor
            for (row in 0..2) {
                if (row == pivot) continue
                val factor = a[row][pivot]
                for (col in pivot..3) a[row][col] -= factor * a[pivot][col]
            }
        }
        return doubleArrayOf(a[0][3], a[1][3], a[2][3])
    }

    private fun greedyAssignment(
        predicted: List<Point>,
        candidates: List<CandidateGeometry>,
        threshold: Double,
    ): Pair<Map<Int, Int>, Map<Int, Double>> {
        data class Edge(val slot: Int, val candidate: Int, val distance: Double)
        val edges = mutableListOf<Edge>()
        predicted.forEachIndexed { slot, point ->
            candidates.forEachIndexed { index, candidate ->
                val distance = point.distanceTo(candidate.center)
                if (distance <= threshold) edges += Edge(slot, index, distance)
            }
        }
        val assignment = linkedMapOf<Int, Int>()
        val residuals = linkedMapOf<Int, Double>()
        val used = mutableSetOf<Int>()
        edges.sortedBy { it.distance }.forEach { edge ->
            if (edge.slot !in assignment && edge.candidate !in used) {
                assignment[edge.slot] = edge.candidate
                residuals[edge.slot] = edge.distance
                used += edge.candidate
            }
        }
        return assignment to residuals
    }

    private fun buildRegions(grid: GridFit, candidates: List<CandidateGeometry>): List<RegionGeometry> {
        val predicted = predictGrid(grid.model)
        val ex = grid.model.vx.unit()
        val assignedDims = grid.assignment.mapValues { (_, candidateIndex) ->
            candidates[candidateIndex].alignedDimensions(ex)
        }
        val globalWidth = median(assignedDims.values.map { it.width })
        val globalHeight = median(assignedDims.values.map { it.height })
        val globalAngle = circularMedian(assignedDims.values.map { it.angleDegrees }, atan2(ex.y, ex.x) * 180.0 / PI)

        fun fallbackDimensions(row: Int, col: Int): Dimensions {
            val nearby = assignedDims.filterKeys { slot ->
                abs(slot / 3 - row) + abs(slot % 3 - col) <= 1
            }.values
            val pool = if (nearby.isNotEmpty()) nearby else assignedDims.values
            return Dimensions(
                width = if (pool.isEmpty()) globalWidth else median(pool.map { it.width }),
                height = if (pool.isEmpty()) globalHeight else median(pool.map { it.height }),
                angleDegrees = if (pool.isEmpty()) globalAngle else circularMedian(pool.map { it.angleDegrees }, globalAngle),
            )
        }

        return slotNames.mapIndexed { slot, name ->
            val row = slot / 3
            val col = slot % 3
            val candidateIndex = grid.assignment[slot]
            if (candidateIndex != null) {
                val candidate = candidates[candidateIndex]
                val dims = assignedDims.getValue(slot)
                val residual = grid.residuals[slot] ?: candidate.center.distanceTo(predicted[slot])
                val fallback = residual > max(6.0, grid.thresholdPx * 0.78) || candidate.aspect > 3.8
                val usedDims = if (fallback) fallbackDimensions(row, col) else dims
                val center = if (fallback) predicted[slot] else candidate.center
                RegionGeometry(
                    name = name,
                    row = row,
                    col = col,
                    center = center,
                    fullWidth = usedDims.width,
                    fullHeight = usedDims.height,
                    angleDegrees = usedDims.angleDegrees,
                    roiSide = max(2.0, min(usedDims.width, usedDims.height) * config.roiScale),
                    residual = residual,
                    recovered = false,
                    reassigned = candidate.predictedClass != slot + 3,
                    boundary = if (fallback) rotatedBoxPoints(center, usedDims) else candidate.polygon,
                    boundarySource = if (fallback) "ransac_quad" else "yolo_polygon",
                )
            } else {
                val dims = fallbackDimensions(row, col)
                RegionGeometry(
                    name = name,
                    row = row,
                    col = col,
                    center = predicted[slot],
                    fullWidth = dims.width,
                    fullHeight = dims.height,
                    angleDegrees = dims.angleDegrees,
                    roiSide = max(2.0, min(dims.width, dims.height) * config.roiScale),
                    residual = Double.NaN,
                    recovered = true,
                    reassigned = false,
                    boundary = rotatedBoxPoints(predicted[slot], dims),
                    boundarySource = "ransac_quad",
                )
            }
        }
    }

    private fun buildGraySamples(
        regions: List<RegionGeometry>,
        grid: GridFit,
        gx: BarGeometry?,
        gy: BarGeometry?,
        w0: CandidateGeometry?,
        imageWidth: Int,
        imageHeight: Int,
    ): GraySampleSelection {
        val primary = if (gx != null && gy != null) {
            buildProjectedGraySamples(regions, grid, gx, gy)
        } else {
            null
        }
        val primaryProblem = when {
            gx == null && gy == null -> "Gx 与 Gy 均未可靠识别"
            gx == null -> "Gx 未可靠识别"
            gy == null -> "Gy 未可靠识别"
            primary == null -> "灰度采样区域无法生成"
            else -> validateGraySampleGeometry(primary, grid, imageWidth, imageHeight)
        }
        if (primary != null && primaryProblem == null) {
            return GraySampleSelection(
                samples = primary,
                source = "detected_Gx_Gy",
                usedFallback = false,
                fallbackReason = null,
            )
        }

        val usableW0 = validW0GridCoordinates(w0, grid.model)
        val fallback = buildFallbackGraySamples(regions, grid, usableW0)
        val fallbackProblem = validateGraySampleGeometry(fallback, grid, imageWidth, imageHeight)
        require(fallbackProblem == null) {
            "灰度采样主路径异常（${primaryProblem ?: "未知原因"}），备用路径也无效（$fallbackProblem）"
        }
        return GraySampleSelection(
            samples = fallback,
            source = if (usableW0 != null) "W0_C_grid_fallback" else "C_grid_fallback",
            usedFallback = true,
            fallbackReason = primaryProblem,
        )
    }

    private fun buildProjectedGraySamples(
        regions: List<RegionGeometry>,
        grid: GridFit,
        gx: BarGeometry,
        gy: BarGeometry,
    ): List<GraySample> {
        val angle = atan2(grid.model.vx.y, grid.model.vx.x) * 180.0 / PI
        val samples = mutableListOf<GraySample>()
        for (row in 0..2) {
            val rowRegions = regions.filter { it.row == row }
            val mean = Point(rowRegions.map { it.center.x }.average(), rowRegions.map { it.center.y }.average())
            val center = projectToBar(mean, gy)
            val side = min(median(rowRegions.map { it.roiSide }), gy.minorLength * 0.94).coerceAtLeast(2.0)
            samples += GraySample("Gyy${row + 1}", center, side, angle, "detected_Gy")
        }
        for (col in 0..2) {
            val colRegions = regions.filter { it.col == col }
            val mean = Point(colRegions.map { it.center.x }.average(), colRegions.map { it.center.y }.average())
            val center = projectToBar(mean, gx)
            val side = min(median(colRegions.map { it.roiSide }), gx.minorLength * 0.94).coerceAtLeast(2.0)
            samples += GraySample("Gxx${col + 1}", center, side, angle, "detected_Gx")
        }
        return samples
    }

    /**
     * Backup geometry calibrated from the Windows CatchTheRainbow records supplied with the project.
     * In grid coordinates W0 is near (-0.87, -0.82), the Gx sample line near v=-0.75,
     * and the Gy sample line near u=-0.83. W0 refines the offsets while the C grid provides
     * orientation, spacing and per-row/per-column ROI sizes.
     */
    private fun buildFallbackGraySamples(
        regions: List<RegionGeometry>,
        grid: GridFit,
        w0Coordinates: Pair<Double, Double>?,
    ): List<GraySample> {
        val gxV = if (w0Coordinates != null) {
            (w0Coordinates.second + W0_TO_GX_V_OFFSET).coerceIn(-1.15, -0.35)
        } else {
            DEFAULT_GX_V
        }
        val gyU = if (w0Coordinates != null) {
            (w0Coordinates.first + W0_TO_GY_U_OFFSET).coerceIn(-1.15, -0.35)
        } else {
            DEFAULT_GY_U
        }
        val angle = atan2(grid.model.vx.y, grid.model.vx.x) * 180.0 / PI
        val source = if (w0Coordinates != null) "W0_C_grid" else "C_grid"
        val samples = mutableListOf<GraySample>()

        for (row in 0..2) {
            val rowRegions = regions.filter { it.row == row }
            val side = median(rowRegions.map { it.roiSide }).coerceAtLeast(2.0)
            val center = grid.model.origin + grid.model.vx * gyU + grid.model.vy * row.toDouble()
            samples += GraySample("Gyy${row + 1}", center, side, angle, source)
        }
        for (col in 0..2) {
            val colRegions = regions.filter { it.col == col }
            val side = median(colRegions.map { it.roiSide }).coerceAtLeast(2.0)
            val center = grid.model.origin + grid.model.vx * col.toDouble() + grid.model.vy * gxV
            samples += GraySample("Gxx${col + 1}", center, side, angle, source)
        }
        return samples
    }


    private fun validW0GridCoordinates(
        w0: CandidateGeometry?,
        model: GridModel,
    ): Pair<Double, Double>? = w0
        ?.let { affineCoordinates(it.center, model) }
        ?.takeIf { it.first in -1.50..-0.20 && it.second in -1.50..-0.20 }

    /** Returns null for acceptable geometry, otherwise a concise reason for fallback. */
    private fun validateGraySampleGeometry(
        samples: List<GraySample>,
        grid: GridFit,
        imageWidth: Int,
        imageHeight: Int,
    ): String? {
        val byName = samples.associateBy { it.name }
        val gxx = (1..3).map { byName["Gxx$it"] ?: return "Gxx 数量或命名异常" }
        val gyy = (1..3).map { byName["Gyy$it"] ?: return "Gyy 数量或命名异常" }
        val minStep = min(grid.model.vx.length(), grid.model.vy.length())
        if (minStep < 5.0) return "网格步长无效"

        for (sample in samples) {
            if (!sample.center.x.isFinite() || !sample.center.y.isFinite() || !sample.side.isFinite()) {
                return "灰度采样坐标包含非有限值"
            }
            if (sample.side !in (0.04 * minStep)..(0.48 * minStep)) {
                return "灰度采样尺寸明显异常"
            }
            val margin = sample.side * 0.75
            if (sample.center.x < -margin || sample.center.x > imageWidth + margin ||
                sample.center.y < -margin || sample.center.y > imageHeight + margin
            ) {
                return "灰度采样中心超出图像"
            }
        }

        val gxxCoordinates = gxx.map { affineCoordinates(it.center, grid.model) }
        val gyyCoordinates = gyy.map { affineCoordinates(it.center, grid.model) }
        gxxCoordinates.forEachIndexed { index, coordinate ->
            if (abs(coordinate.first - index.toDouble()) > 0.58) return "Gxx 与 C 列对应关系异常"
            if (coordinate.second !in -1.35..-0.18) return "Gxx 未位于 C 网格上方"
        }
        gyyCoordinates.forEachIndexed { index, coordinate ->
            if (abs(coordinate.second - index.toDouble()) > 0.58) return "Gyy 与 C 行对应关系异常"
            if (coordinate.first !in -1.35..-0.18) return "Gyy 未位于 C 网格左侧"
        }

        val gxxGaps = gxxCoordinates.zipWithNext { a, b -> b.first - a.first }
        val gyyGaps = gyyCoordinates.zipWithNext { a, b -> b.second - a.second }
        if (gxxGaps.any { it !in 0.35..1.65 }) return "Gxx 顺序或间距异常"
        if (gyyGaps.any { it !in 0.35..1.65 }) return "Gyy 顺序或间距异常"
        if ((gxxCoordinates.maxOf { it.second } - gxxCoordinates.minOf { it.second }) > 0.42) {
            return "Gxx 未形成稳定横向序列"
        }
        if ((gyyCoordinates.maxOf { it.first } - gyyCoordinates.minOf { it.first }) > 0.42) {
            return "Gyy 未形成稳定纵向序列"
        }

        fun overlaps(a: GraySample, b: GraySample): Boolean {
            val minimumDistance = 0.62 * (a.side + b.side) / 2.0
            return a.center.distanceTo(b.center) < minimumDistance
        }
        if (gxx.indices.any { i -> (i + 1 until gxx.size).any { j -> overlaps(gxx[i], gxx[j]) } }) {
            return "Gxx 发生重叠"
        }
        if (gyy.indices.any { i -> (i + 1 until gyy.size).any { j -> overlaps(gyy[i], gyy[j]) } }) {
            return "Gyy 发生重叠"
        }
        if (gxx.any { x -> gyy.any { y -> overlaps(x, y) } }) return "Gx 与 Gy 采样区域混乱"
        return null
    }

    /** Affine coefficients p = origin + u*vx + v*vy. */
    private fun affineCoordinates(point: Point, model: GridModel): Pair<Double, Double> {
        val delta = point - model.origin
        val determinant = model.vx.cross(model.vy)
        if (abs(determinant) < 1e-9) return Double.NaN to Double.NaN
        val u = delta.cross(model.vy) / determinant
        val v = model.vx.cross(delta) / determinant
        return u to v
    }

    private fun projectToBar(point: Point, bar: BarGeometry): Point {
        val t = ((point - bar.center).dot(bar.major)).coerceIn(
            -bar.majorLength * 0.47,
            bar.majorLength * 0.47,
        )
        return bar.center + bar.major * t
    }

    private fun colorStatistics(
        rgb: Mat,
        center: Point,
        width: Double,
        height: Double,
        angleDegrees: Double,
        residual: Double = Double.NaN,
        residualThreshold: Double = Double.NaN,
    ): ColorStats {
        val corners = rotatedBoxPoints(center, Dimensions(width, height, angleDegrees))
        val outside = corners.any { it.x < 0 || it.x >= rgb.cols() || it.y < 0 || it.y >= rgb.rows() }
        val mask = Mat.zeros(rgb.rows(), rgb.cols(), CvType.CV_8U)
        val polygon = MatOfPoint(*corners.map { Point(Math.rint(it.x), Math.rint(it.y)) }.toTypedArray())
        return try {
            Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0), Imgproc.LINE_AA, 0)
            val count = Core.countNonZero(mask)
            require(count >= 16) { "ROI 有效像素不足：$count" }
            val mean = Core.mean(rgb, mask).`val`
            val flags = mutableListOf<String>()
            if (outside) flags += "ROI_OUTSIDE"
            if (residual.isFinite() && residualThreshold.isFinite() && residual > 0.75 * residualThreshold) {
                flags += "HIGH_RANSAC_RESIDUAL"
            }
            ColorStats(
                meanRgb = RgbColor(mean[0], mean[1], mean[2]),
                gray = mean[0] * 0.2126 + mean[1] * 0.7152 + mean[2] * 0.0722,
                validPixels = count,
                flags = flags,
            )
        } finally {
            polygon.release()
            mask.release()
        }
    }

    private fun calibrate(raw: RgbColor, gxx: ColorStats, gyy: ColorStats): CorrectionResult {
        val reference = RgbColor(
            (gxx.meanRgb.r + gyy.meanRgb.r) / 2.0,
            (gxx.meanRgb.g + gyy.meanRgb.g) / 2.0,
            (gxx.meanRgb.b + gyy.meanRgb.b) / 2.0,
        )
        val target = config.standardGrayRgb.map { it.toDouble() }.toDoubleArray()
        val targetLinear = srgbToLinear(target)
        val referenceLinear = srgbToLinear(reference.asArray())
        val rawGain = DoubleArray(3) { targetLinear[it] / max(referenceLinear[it], 1e-6) }
        val weights = doubleArrayOf(0.2126, 0.7152, 0.0722)
        val scalarGain = dot(targetLinear, weights) / max(dot(referenceLinear, weights), 1e-6)
        val unclamped = DoubleArray(3) { index ->
            val relative = rawGain[index] / max(scalarGain, 1e-9)
            scalarGain * max(relative, 1e-9).pow(config.calibrationStrength)
        }
        val gain = unclamped.map { it.coerceIn(config.gainMin, config.gainMax) }.toDoubleArray()
        val correctedLinear = srgbToLinear(raw.asArray()).mapIndexed { index, value -> value * gain[index] }.toDoubleArray()
        val correctedArray = linearToSrgb(correctedLinear)
        val corrected = RgbColor(correctedArray[0], correctedArray[1], correctedArray[2])
        val flags = mutableListOf<String>()
        if (abs(gxx.gray - gyy.gray) > 15.0) flags += "GRAY_PAIR_MISMATCH"
        if (unclamped.indices.any { abs(unclamped[it] - gain[it]) > 1e-12 }) flags += "GAIN_CLAMPED"
        if (correctedArray.any { it <= 0.5 || it >= 254.5 }) flags += "CORRECTED_COLOR_CLIPPED"
        return CorrectionResult(corrected = corrected, gains = gain, flags = flags)
    }

    private fun srgbToLinear(rgb: DoubleArray): DoubleArray = rgb.map { component ->
        val value = (component / 255.0).coerceIn(0.0, 1.0)
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }.toDoubleArray()

    private fun linearToSrgb(linear: DoubleArray): DoubleArray = linear.map { component ->
        val value = component.coerceIn(0.0, 1.0)
        val srgb = if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - 0.055
        (srgb * 255.0).coerceIn(0.0, 255.0)
    }.toDoubleArray()

    private fun rgbToLabD65(rgb: RgbColor): LabColor {
        val linear = srgbToLinear(rgb.asArray())
        val x = 0.4124564 * linear[0] + 0.3575761 * linear[1] + 0.1804375 * linear[2]
        val y = 0.2126729 * linear[0] + 0.7151522 * linear[1] + 0.0721750 * linear[2]
        val z = 0.0193339 * linear[0] + 0.1191920 * linear[1] + 0.9503041 * linear[2]
        fun f(value: Double): Double {
            val delta = 6.0 / 29.0
            return if (value > delta.pow(3.0)) Math.cbrt(value) else value / (3.0 * delta.pow(2.0)) + 4.0 / 29.0
        }
        val fx = f(x / 0.95047)
        val fy = f(y)
        val fz = f(z / 1.08883)
        return LabColor(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun rgbToHsl(rgb: RgbColor): HslColor {
        val r = (rgb.r / 255.0).coerceIn(0.0, 1.0)
        val g = (rgb.g / 255.0).coerceIn(0.0, 1.0)
        val b = (rgb.b / 255.0).coerceIn(0.0, 1.0)
        val maxValue = max(r, max(g, b))
        val minValue = min(r, min(g, b))
        val delta = maxValue - minValue
        val lightness = (maxValue + minValue) / 2.0
        if (delta < 1e-12) return HslColor(0.0, 0.0, lightness)
        val saturation = delta / (1.0 - abs(2.0 * lightness - 1.0))
        var hue = when (maxValue) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * ((b - r) / delta + 2.0)
            else -> 60.0 * ((r - g) / delta + 4.0)
        }
        if (hue < 0.0) hue += 360.0
        return HslColor(hue, saturation.coerceIn(0.0, 1.0), lightness.coerceIn(0.0, 1.0))
    }

    /**
     * Reproduce the desktop CatchTheRainbow preview layout as closely as practical on Android:
     * annotated image first, then RAW ROI / CORRECTED ROI / CORRECTED MEAN COLOR + HEX rows.
     */
    private fun renderPreview(
        source: Bitmap,
        regions: List<RegionGeometry>,
        samples: List<GraySample>,
        colors: List<RainbowRegionColor>,
        corrections: Map<String, CorrectionResult>,
        grid: GridFit,
        gx: BarGeometry?,
        gy: BarGeometry?,
        w0: CandidateGeometry?,
    ): Bitmap {
        val annotated = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val predicted = predictGrid(grid.model)
        linePaint.color = Color.rgb(180, 180, 180)
        linePaint.strokeWidth = 1f
        for (row in 0..2) {
            drawOpenPolyline(canvas, predicted.subList(row * 3, row * 3 + 3), linePaint)
        }
        for (col in 0..2) {
            drawOpenPolyline(canvas, listOf(predicted[col], predicted[col + 3], predicted[col + 6]), linePaint)
        }

        linePaint.strokeWidth = 2f
        gx?.let {
            linePaint.color = Color.rgb(0, 229, 255)
            drawPolygon(canvas, it.polygon, linePaint)
            drawBadge(canvas, "Gx", it.polygon, linePaint.color)
        }
        gy?.let {
            linePaint.color = Color.rgb(0, 229, 255)
            drawPolygon(canvas, it.polygon, linePaint)
            drawBadge(canvas, "Gy", it.polygon, linePaint.color)
        }
        w0?.let {
            linePaint.color = Color.RED
            drawPolygon(canvas, it.polygon, linePaint)
            drawBadge(canvas, "W0", it.polygon, linePaint.color)
        }

        regions.forEach { region ->
            linePaint.color = when {
                region.boundarySource == "yolo_polygon" -> Color.rgb(160, 0, 255)
                region.recovered -> Color.rgb(255, 208, 0)
                else -> Color.rgb(255, 128, 0)
            }
            drawPolygon(canvas, region.boundary, linePaint)
            drawBadge(canvas, region.name, region.boundary, linePaint.color)

            linePaint.color = Color.WHITE
            drawPolygon(
                canvas,
                rotatedBoxPoints(region.center, Dimensions(region.roiSide, region.roiSide, region.angleDegrees)),
                linePaint,
            )
        }

        samples.forEach { sample ->
            linePaint.color = if (sample.name.startsWith("Gxx")) {
                Color.rgb(220, 0, 255)
            } else {
                Color.rgb(0, 220, 255)
            }
            val box = rotatedBoxPoints(sample.center, Dimensions(sample.side, sample.side, sample.angleDegrees))
            drawPolygon(canvas, box, linePaint)
            drawBadge(canvas, sample.name, box, linePaint.color)
        }

        val pad = 8
        val tileGap = 7
        val patchSize = max(48, min(96, (annotated.width - 2 * pad - 8 * tileGap) / 9))
        val titleHeight = 24
        val hexHeight = 25
        val rowGap = 8
        val firstTitleTop = pad
        val firstY = firstTitleTop + titleHeight
        val secondTitleTop = firstY + patchSize + rowGap
        val secondY = secondTitleTop + titleHeight
        val thirdTitleTop = secondY + patchSize + rowGap
        val thirdY = thirdTitleTop + titleHeight
        val hexTop = thirdY + patchSize
        val panelHeight = hexTop + hexHeight + pad
        val totalWidth = max(annotated.width, 2 * pad + 9 * patchSize + 8 * tileGap)

        val combined = Bitmap.createBitmap(totalWidth, annotated.height + panelHeight, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(combined)
        outputCanvas.drawColor(Color.WHITE)
        outputCanvas.drawBitmap(annotated, 0f, 0f, null)
        val panelTop = annotated.height.toFloat()
        val panelPaint = Paint().apply { color = Color.rgb(248, 248, 248) }
        outputCanvas.drawRect(0f, panelTop, totalWidth.toFloat(), panelTop + panelHeight, panelPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            textSize = 14f
        }
        outputCanvas.drawText(AppLanguage.text("RAW ROI"), pad.toFloat(), panelTop + firstTitleTop + 17f, titlePaint)
        outputCanvas.drawText(AppLanguage.text("CORRECTED ROI"), pad.toFloat(), panelTop + secondTitleTop + 17f, titlePaint)
        outputCanvas.drawText(AppLanguage.text("CORRECTED MEAN COLOR"), pad.toFloat(), panelTop + thirdTitleTop + 17f, titlePaint)

        val ordered = regions.sortedWith(compareBy<RegionGeometry> { it.row }.thenBy { it.col })
        val colorByName = colors.associateBy { it.name }
        val sourceRaw = Mat()
        val sourceMat = Mat()
        Utils.bitmapToMat(source, sourceRaw)
        when (sourceRaw.channels()) {
            4 -> Imgproc.cvtColor(sourceRaw, sourceMat, Imgproc.COLOR_RGBA2RGB)
            3 -> sourceRaw.copyTo(sourceMat)
            1 -> Imgproc.cvtColor(sourceRaw, sourceMat, Imgproc.COLOR_GRAY2RGB)
            else -> throw IllegalArgumentException("颜色提取与校正模块预览不支持 ${sourceRaw.channels()} 通道图片")
        }
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 75, 75)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val tileTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
        }
        val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 25, 25)
            textSize = if (patchSize >= 64) 11f else 9f
            textAlign = Paint.Align.CENTER
        }

        try {
            ordered.forEachIndexed { index, region ->
                val x = pad + index * (patchSize + tileGap)
                val rawCrop = cropRotatedRoi(sourceMat, region)
                val gains = corrections[region.name]?.gains ?: doubleArrayOf(1.0, 1.0, 1.0)
                val correctedCrop = applyLinearGainToBitmap(rawCrop, gains)
                val item = colorByName[region.name]
                val correctedRgb = item?.correctedRgb ?: RgbColor(230.0, 230.0, 230.0)
                val meanColor = Color.rgb(
                    correctedRgb.r.roundToInt().coerceIn(0, 255),
                    correctedRgb.g.roundToInt().coerceIn(0, 255),
                    correctedRgb.b.roundToInt().coerceIn(0, 255),
                )

                fun tileRect(y: Int): RectF = RectF(
                    x.toFloat(),
                    panelTop + y,
                    (x + patchSize).toFloat(),
                    panelTop + y + patchSize,
                )

                outputCanvas.drawBitmap(rawCrop, null, tileRect(firstY), bitmapPaint)
                outputCanvas.drawBitmap(correctedCrop, null, tileRect(secondY), bitmapPaint)
                panelPaint.color = meanColor
                outputCanvas.drawRect(tileRect(thirdY), panelPaint)
                outputCanvas.drawRect(tileRect(firstY), borderPaint)
                outputCanvas.drawRect(tileRect(secondY), borderPaint)
                outputCanvas.drawRect(tileRect(thirdY), borderPaint)

                drawOutlinedTileLabel(outputCanvas, region.name, x + 3f, panelTop + firstY + 14f, tileTextPaint)
                drawOutlinedTileLabel(outputCanvas, region.name, x + 3f, panelTop + secondY + 14f, tileTextPaint)

                val hex = "%02X%02X%02X".format(
                    correctedRgb.r.roundToInt().coerceIn(0, 255),
                    correctedRgb.g.roundToInt().coerceIn(0, 255),
                    correctedRgb.b.roundToInt().coerceIn(0, 255),
                )
                outputCanvas.drawText(
                    AppLanguage.text(hex),
                    x + patchSize / 2f,
                    panelTop + hexTop + 17f,
                    hexPaint,
                )
                rawCrop.recycle()
                correctedCrop.recycle()
            }
        } finally {
            sourceRaw.release()
            sourceMat.release()
            annotated.recycle()
        }
        return combined
    }

    /** Mirrors desktop crop_rotated: TL/TR/BR/BL -> [0, side-1], one cubic warp. */
    private fun cropRawRoi(source: Mat, region: RegionGeometry): RgbImage {
        val side = max(1, Math.rint(region.roiSide).toInt())
        val corners = rotatedBoxPoints(region.center, Dimensions(region.roiSide, region.roiSide, region.angleDegrees))
        val ordered = listOf(
            corners.minByOrNull { it.x + it.y }!!,
            corners.minByOrNull { it.y - it.x }!!,
            corners.maxByOrNull { it.x + it.y }!!,
            corners.maxByOrNull { it.y - it.x }!!,
        )
        require(ordered.distinctBy { it.x to it.y }.size == 4) { "ROI 四角排序退化" }
        val src = MatOfPoint2f(*ordered.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(side - 1.0, 0.0), Point(side - 1.0, side - 1.0), Point(0.0, side - 1.0))
        var matrix: Mat? = null
        val crop = Mat()
        return try {
            matrix = Imgproc.getPerspectiveTransform(src, dst)
            Imgproc.warpPerspective(source, crop, matrix, Size(side.toDouble(), side.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            require(crop.channels() == 3) { "原始 ROI 必须为 RGB 三通道" }
            val bytes = ByteArray(side * side * 3)
            crop.get(0, 0, bytes)
            RgbImage(side, side, IntArray(side * side) { i ->
                (255 shl 24) or ((bytes[3 * i].toInt() and 255) shl 16) or
                    ((bytes[3 * i + 1].toInt() and 255) shl 8) or (bytes[3 * i + 2].toInt() and 255)
            })
        } finally {
            src.release(); dst.release(); matrix?.release(); crop.release()
        }
    }

    private fun cropRotatedRoi(source: Mat, region: RegionGeometry): Bitmap {
        val image = cropRawRoi(source, region)
        return Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun applyLinearGainToBitmap(source: Bitmap, gains: DoubleArray): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val channels = doubleArrayOf(
                Color.red(color).toDouble(),
                Color.green(color).toDouble(),
                Color.blue(color).toDouble(),
            )
            val linear = srgbToLinear(channels)
            val corrected = linearToSrgb(DoubleArray(3) { channel -> linear[channel] * gains[channel] })
            pixels[index] = Color.rgb(
                Math.rint(corrected[0]).toInt().coerceIn(0, 255),
                Math.rint(corrected[1]).toInt().coerceIn(0, 255),
                Math.rint(corrected[2]).toInt().coerceIn(0, 255),
            )
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun drawBadge(canvas: Canvas, text: String, points: List<Point>, color: Int) {
        if (points.isEmpty()) return
        val anchor = points.minByOrNull { it.y * 10000.0 + it.x } ?: return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT
        }
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val x = max(0f, anchor.x.toFloat())
        val bottom = max(bounds.height().toFloat() + 4f, anchor.y.toFloat())
        val top = bottom - bounds.height() - 6f
        val background = Paint().apply {
            style = Paint.Style.FILL
            this.color = color
        }
        canvas.drawRect(x, top, x + bounds.width() + 8f, bottom + 2f, background)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        textPaint.color = if (0.299 * r + 0.587 * g + 0.114 * b > 155.0) Color.BLACK else Color.WHITE
        canvas.drawText(AppLanguage.text(text), x + 4f, bottom - 2f, textPaint)
    }

    private fun drawOpenPolyline(canvas: Canvas, points: List<Point>, paint: Paint) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawOutlinedTileLabel(canvas: Canvas, text: String, x: Float, y: Float, base: Paint) {
        val outline = Paint(base).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawText(AppLanguage.text(text), x, y, outline)
        val fill = Paint(base).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawText(AppLanguage.text(text), x, y, fill)
    }

    private fun drawPolygon(canvas: Canvas, points: List<Point>, paint: Paint) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun CandidateGeometry.rectAxes(): RectAxes {
        val points = rotatedBoxPoints(rect.center, Dimensions(rect.size.width, rect.size.height, rect.angle)).toTypedArray()
        val edges = points.indices.map { points[(it + 1) % 4] - points[it] }
        val lengths = edges.map { it.length() }
        val index = lengths.indices.maxByOrNull { lengths[it] } ?: 0
        return RectAxes(edges[index].unit(), lengths[index], lengths[(index + 1) % 4])
    }

    private fun CandidateGeometry.alignedDimensions(referenceX: Point): Dimensions {
        val points = rotatedBoxPoints(rect.center, Dimensions(rect.size.width, rect.size.height, rect.angle)).toTypedArray()
        val edges = points.indices.map { points[(it + 1) % 4] - points[it] }
        val lengths = edges.map { it.length() }
        val index = listOf(0, 1).maxByOrNull { abs(edges[it].unit().dot(referenceX.unit())) } ?: 0
        var widthVector = edges[index]
        if (widthVector.dot(referenceX) < 0.0) widthVector = widthVector * -1.0
        return Dimensions(
            width = lengths[index],
            height = lengths[(index + 1) % 4],
            angleDegrees = atan2(widthVector.y, widthVector.x) * 180.0 / PI,
        )
    }

    private fun rotatedBoxPoints(center: Point, dimensions: Dimensions): List<Point> {
        val rect = RotatedRect(center, Size(max(1.0, dimensions.width), max(1.0, dimensions.height)), dimensions.angleDegrees)
        val points = Mat()
        return try {
            // Use the C++ float32 implementation also used by Python cv2.boxPoints.
            Imgproc.boxPoints(rect, points)
            val values = FloatArray(8)
            points.get(0, 0, values)
            List(4) { i -> Point(values[2 * i].toDouble(), values[2 * i + 1].toDouble()) }
        } finally { points.release() }
    }

    private fun predictGrid(model: GridModel): List<Point> = List(9) { slot ->
        model.origin + model.vx * (slot % 3).toDouble() + model.vy * (slot / 3).toDouble()
    }

    private fun nonCollinearTriples(): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        for (a in 0..6) for (b in a + 1..7) for (c in b + 1..8) {
            val p1 = Point((a % 3).toDouble(), (a / 3).toDouble())
            val p2 = Point((b % 3).toDouble(), (b / 3).toDouble())
            val p3 = Point((c % 3).toDouble(), (c / 3).toDouble())
            if (abs((p2 - p1).cross(p3 - p1)) > 0.5) result += listOf(a, b, c)
        }
        return result
    }

    private fun lineIntersection(p1: Point, d1: Point, p2: Point, d2: Point): Point? {
        val denominator = d1.cross(d2)
        if (abs(denominator) < 1e-8) return null
        val t = (p2 - p1).cross(d2) / denominator
        return p1 + d1 * t
    }

    private fun angleBetween(a: Point, b: Point): Double {
        val value = a.unit().dot(b.unit()).coerceIn(-1.0, 1.0)
        return acos(value) * 180.0 / PI
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val position = (sorted.size - 1) * q
        val low = position.toInt()
        val high = min(sorted.lastIndex, low + 1)
        val fraction = position - low
        return sorted[low] * (1.0 - fraction) + sorted[high] * fraction
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "无法计算空集合中位数" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun circularMedian(values: List<Double>, fallback: Double): Double {
        if (values.isEmpty()) return fallback
        return values.minByOrNull { candidate -> values.sumOf { angularDifference(candidate, it) } } ?: fallback
    }

    private fun angularDifference(a: Double, b: Double): Double {
        val delta = abs((a - b) % 180.0)
        return min(delta, 180.0 - delta)
    }

    private fun clamp(value: Double, low: Double, high: Double): Double = max(low, min(high, value))
    private fun dot(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { a[it] * b[it] }

    private enum class BarType { GX, GY }

    private data class CandidateGeometry(
        val index: Int,
        val predictedClass: Int,
        val confidence: Double,
        val polygon: List<Point>,
        val center: Point,
        val area: Double,
        val rect: RotatedRect,
        val aspect: Double,
    )

    private data class RectAxes(val major: Point, val majorLength: Double, val minorLength: Double)
    private data class BarGeometry(
        val type: BarType,
        val center: Point,
        val major: Point,
        val majorLength: Double,
        val minorLength: Double,
        val source: String,
        val polygon: List<Point>,
    )
    private data class GridModel(val origin: Point, val vx: Point, val vy: Point)
    private data class GridScore(
        val count: Int,
        val medianResidualScore: Double,
        val meanResidualScore: Double,
    ) : Comparable<GridScore> {
        override fun compareTo(other: GridScore): Int = compareValuesBy(
            this,
            other,
            GridScore::count,
            GridScore::medianResidualScore,
            GridScore::meanResidualScore,
        )
    }
    private data class GridFit(
        val model: GridModel,
        val assignment: Map<Int, Int>,
        val residuals: Map<Int, Double>,
        val medianResidual: Double,
        val thresholdPx: Double,
        val score: GridScore,
        val templateSource: String,
    )
    private data class Dimensions(val width: Double, val height: Double, val angleDegrees: Double)
    private data class RegionGeometry(
        val name: String,
        val row: Int,
        val col: Int,
        val center: Point,
        val fullWidth: Double,
        val fullHeight: Double,
        val angleDegrees: Double,
        val roiSide: Double,
        val residual: Double,
        val recovered: Boolean,
        val reassigned: Boolean,
        val boundary: List<Point>,
        val boundarySource: String,
    )
    private data class GraySample(
        val name: String,
        val center: Point,
        val side: Double,
        val angleDegrees: Double,
        val source: String,
    )
    private data class GraySampleSelection(
        val samples: List<GraySample>,
        val source: String,
        val usedFallback: Boolean,
        val fallbackReason: String?,
    )
    private data class ColorStats(
        val meanRgb: RgbColor,
        val gray: Double,
        val validPixels: Int,
        val flags: List<String>,
    )
    private data class CorrectionResult(
        val corrected: RgbColor,
        val gains: DoubleArray,
        val flags: List<String>,
    )

    private companion object {
        const val W0_TO_GX_V_OFFSET = 0.070
        const val W0_TO_GY_U_OFFSET = 0.040
        const val DEFAULT_GX_V = -0.750
        const val DEFAULT_GY_U = -0.833
    }
}

private operator fun Point.plus(other: Point): Point = Point(x + other.x, y + other.y)
private operator fun Point.minus(other: Point): Point = Point(x - other.x, y - other.y)
private operator fun Point.times(value: Double): Point = Point(x * value, y * value)
private fun Point.dot(other: Point): Double = x * other.x + y * other.y
private fun Point.cross(other: Point): Double = x * other.y - y * other.x
private fun Point.length(): Double = hypot(x, y)
private fun Point.unit(): Point {
    val length = length()
    return if (length < 1e-9) Point(1.0, 0.0) else Point(x / length, y / length)
}
private fun Point.distanceTo(other: Point): Double = hypot(x - other.x, y - other.y)
