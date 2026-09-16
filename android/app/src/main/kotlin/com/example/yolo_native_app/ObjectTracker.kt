package com.example.yolo_native_app

import kotlin.math.max
import kotlin.math.min

/**
 * Tracker tipo ByteTrack (liviano, sin ReID): Kalman por track con predicción antes del
 * matching, y matching greedy por IoU en dos etapas — alta confianza primero, luego baja
 * confianza solo para rescatar tracks vivos que no matchearon en la primera etapa.
 * Gateo por className canonicalizado en ambas etapas.
 */
class ObjectTracker(
    private val highConfidenceThreshold: Float = 0.50f,
    private val lowConfidenceThreshold: Float = 0.10f,
    private val maxAge: Int = 5,
    private val iouThresholdHigh: Float = 0.3f,
    private val iouThresholdLow: Float = 0.4f
) {
    private class Track(
        var id: Int,
        var className: String,
        val kalman: BoxKalmanFilter,
        var misses: Int = 0
    )

    private data class Candidate(val trackIndex: Int, val detectionIndex: Int, val iou: Float)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<YoloDetector.Detection>): List<YoloDetector.Detection> {
        for (track in tracks) track.kalman.predict()

        val highDetections = detections.filter { it.confidence >= highConfidenceThreshold }
        val lowDetections = detections.filter { it.confidence < highConfidenceThreshold }

        val result = mutableListOf<YoloDetector.Detection>()
        val trackMatched = BooleanArray(tracks.size)

        // Etapa 1: tracks (posición predicha por Kalman) vs detecciones de alta confianza
        val highDetTaken = BooleanArray(highDetections.size)
        matchStage(highDetections, iouThresholdHigh, trackMatched, highDetTaken) { track, detection ->
            track.kalman.update(detection)
            track.className = detection.className
            track.misses = 0
            result.add(buildOutput(track, detection))
        }

        // Etapa 2: solo tracks que NO matchearon en etapa 1, contra detecciones de baja confianza
        val lowDetTaken = BooleanArray(lowDetections.size)
        matchStage(lowDetections, iouThresholdLow, trackMatched, lowDetTaken) { track, detection ->
            track.kalman.update(detection)
            track.className = detection.className
            track.misses = 0
            result.add(buildOutput(track, detection))
        }

        for (t in tracks.indices) {
            if (!trackMatched[t]) tracks[t].misses++
        }
        tracks.removeAll { it.misses > maxAge }

        // Tracks nuevos SOLO desde alta confianza no usada (regla ByteTrack: nunca crear un
        // track a partir de una detección de baja confianza, para no trackear ruido/fondo)
        for (d in highDetections.indices) {
            if (!highDetTaken[d]) {
                val detection = highDetections[d]
                val newTrack = Track(id = nextId++, className = detection.className, kalman = BoxKalmanFilter(detection))
                tracks.add(newTrack)
                result.add(buildOutput(newTrack, detection))
            }
        }

        return result
    }

    private fun matchStage(
        detections: List<YoloDetector.Detection>,
        iouThreshold: Float,
        trackMatched: BooleanArray,
        detectionTaken: BooleanArray,
        onMatch: (Track, YoloDetector.Detection) -> Unit
    ) {
        val candidates = mutableListOf<Candidate>()
        for (t in tracks.indices) {
            if (trackMatched[t]) continue
            val track = tracks[t]
            for (d in detections.indices) {
                if (track.className != detections[d].className) continue
                val iou = calculateIoU(predictedBox(track), detections[d])
                if (iou >= iouThreshold) candidates.add(Candidate(t, d, iou))
            }
        }
        candidates.sortByDescending { it.iou }

        for (c in candidates) {
            if (trackMatched[c.trackIndex] || detectionTaken[c.detectionIndex]) continue
            trackMatched[c.trackIndex] = true
            detectionTaken[c.detectionIndex] = true
            onMatch(tracks[c.trackIndex], detections[c.detectionIndex])
        }
    }

    private fun buildOutput(track: Track, detection: YoloDetector.Detection): YoloDetector.Detection {
        // Se reporta la estimación suavizada por Kalman, no la box cruda de la detección
        return detection.copy(
            trackId = track.id,
            x = track.kalman.x,
            y = track.kalman.y,
            width = track.kalman.width,
            height = track.kalman.height
        )
    }

    private fun predictedBox(track: Track): YoloDetector.Detection {
        return YoloDetector.Detection(
            classId = 0,
            className = track.className,
            confidence = 0f,
            x = track.kalman.x,
            y = track.kalman.y,
            width = track.kalman.width,
            height = track.kalman.height
        )
    }

    private fun calculateIoU(a: YoloDetector.Detection, b: YoloDetector.Detection): Float {
        val x1 = max(a.x - a.width / 2, b.x - b.width / 2)
        val y1 = max(a.y - a.height / 2, b.y - b.height / 2)
        val x2 = min(a.x + a.width / 2, b.x + b.width / 2)
        val y2 = min(a.y + a.height / 2, b.y + b.height / 2)

        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - intersection

        return if (union > 0) intersection / union else 0f
    }
}
