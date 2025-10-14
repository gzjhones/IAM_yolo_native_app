package com.example.yolo_native_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

class YoloDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val inputSize = 640
    
    private val numClasses = 2  // CAMBIAR SEGÚN TU MODELO (1, 2, 3, 80, etc.)
    private val modelFileName = "best_float32_v2.tflite"
    private val confidenceThreshold = 0.25f  // Umbral de confianza
    private val nmsIouThreshold = 0.45f  // Umbral IoU para NMS
    private val boundingBoxReduction = 0.85f  // Factor de reducción del bbox (0.5-1.0)
    
    // Calculado automáticamente
    private val outputChannels = 4 + numClasses  // 4 coords + N clases
    
    data class Detection(
        val classId: Int,
        val className: String,
        val confidence: Float,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
    
    fun loadModel(): Boolean {
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            
            labels = loadLabels()
            
            println("Model loaded successfully")
            println("Configuration:")
            println("   - Classes: $numClasses")
            println("   - Output channels: $outputChannels")
            println("   - Input shape: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}")
            println("   - Output shape: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
            println("   - Labels: $labels")
            println("   - Confidence threshold: $confidenceThreshold")
            println("   - NMS IoU threshold: $nmsIouThreshold")
            
            // Validar que el output shape coincide
            val actualOutputShape = interpreter?.getOutputTensor(0)?.shape()
            if (actualOutputShape != null && actualOutputShape[1] != outputChannels) {
                println("WARNING: Expected $outputChannels channels but model has ${actualOutputShape[1]}")
                println("   Please update numClasses variable!")
            }
            
            true
        } catch (e: Exception) {
            println("Error loading model: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun loadLabels(): List<String> {
        val labelList = mutableListOf<String>()
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open("labels.txt")))
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        labelList.add(line.trim())
                    }
                }
            }
            
            // Validar número de labels
            if (labelList.size != numClasses) {
                println("WARNING: Found ${labelList.size} labels but numClasses=$numClasses")
            }
            
        } catch (e: Exception) {
            println("Error loading labels: ${e.message}")
            // Generar labels por defecto
            for (i in 0 until numClasses) {
                labelList.add("class_$i")
            }
        }
        return labelList
    }
    
    fun detectObjects(imageBytes: ByteArray): List<Detection> {
        if (interpreter == null) {
            println("Interpreter not initialized")
            return emptyList()
        }
        
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            println("Original image: ${bitmap.width}x${bitmap.height}")
            
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputArray = prepareInput(resizedBitmap)
            
            // Array dinámico basado en outputChannels
            val outputArray = Array(1) { Array(outputChannels) { FloatArray(8400) } }
            
            println("Running inference...")
            val startTime = System.currentTimeMillis()
            interpreter?.run(inputArray, outputArray)
            val inferenceTime = System.currentTimeMillis() - startTime
            println("Inference completed in ${inferenceTime}ms")
            
            val detections = processOutput(outputArray[0], bitmap.width, bitmap.height)
            
            bitmap.recycle()
            resizedBitmap.recycle()
            
            detections
        } catch (e: Exception) {
            println("Error in detection: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
    private fun prepareInput(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255.0f // R
                input[0][y][x][1] = ((pixel shr 8) and 0xFF) / 255.0f  // G
                input[0][y][x][2] = (pixel and 0xFF) / 255.0f          // B
            }
        }
        
        return input
    }

    private fun processOutput(output: Array<FloatArray>, imageWidth: Int, imageHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        println("Processing ${output[0].size} potential detections...")
        var validCount = 0
        
        for (i in 0 until 8400) {
            val xNorm = output[0][i]
            val yNorm = output[1][i]
            val wNorm = output[2][i]
            val hNorm = output[3][i]
            
            // Encontrar la mejor clase dinámicamente
            var maxConfidence = 0f
            var bestClassId = -1
            
            for (c in 0 until numClasses) {
                val classConf = output[4 + c][i]
                if (classConf > maxConfidence) {
                    maxConfidence = classConf
                    bestClassId = c
                }
            }
            
            // Debug primeras 10 detecciones
            if (i < 10) {
                println("Det[$i]: x=%.2f, y=%.2f, w=%.2f, h=%.2f, bestClass=$bestClassId, conf=%.4f"
                    .format(xNorm, yNorm, wNorm, hNorm, maxConfidence))
            }
            
            // Filtrar por confianza
            if (maxConfidence > confidenceThreshold && wNorm > 0 && hNorm > 0) {
                validCount++
                
                if (validCount <= 5) {
                    println("Valid detection #$validCount: class=$bestClassId, conf=$maxConfidence")
                }
                
                // Desnormalizar coordenadas
                val xModel = xNorm * inputSize
                val yModel = yNorm * inputSize
                val wModel = wNorm * inputSize
                val hModel = hNorm * inputSize
                
                val scaleX = imageWidth.toFloat() / inputSize
                val scaleY = imageHeight.toFloat() / inputSize
                
                detections.add(Detection(
                    classId = bestClassId,
                    className = if (bestClassId < labels.size) labels[bestClassId] else "class_$bestClassId",
                    confidence = maxConfidence,
                    x = xModel * scaleX,
                    y = yModel * scaleY,
                    width = wModel * scaleX * boundingBoxReduction,
                    height = hModel * scaleY * boundingBoxReduction
                ))
            }
        }
        
        println("Detections with conf > $confidenceThreshold: $validCount")
        
        if (detections.isEmpty()) {
            println("WARNING: No detections found. Check:")
            println("   - Model trained correctly?")
            println("   - Adequate lighting?")
            println("   - Object in frame?")
            println("   - Confidence threshold too high? (current: $confidenceThreshold)")
        }
        
        val finalDetections = applyNMS(detections)
        println("Final detections after NMS: ${finalDetections.size}")
        
        return finalDetections
    }
   
    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        val sortedDetections = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        
        while (sortedDetections.isNotEmpty()) {
            val best = sortedDetections.removeAt(0)
            result.add(best)
            
            sortedDetections.removeAll { detection ->
                if (best.classId == detection.classId) {
                    calculateIoU(best, detection) > nmsIouThreshold
                } else {
                    false
                }
            }
        }
        
        return result
    }
    
    private fun calculateIoU(det1: Detection, det2: Detection): Float {
        val x1 = max(det1.x - det1.width / 2, det2.x - det2.width / 2)
        val y1 = max(det1.y - det1.height / 2, det2.y - det2.height / 2)
        val x2 = min(det1.x + det1.width / 2, det2.x + det2.width / 2)
        val y2 = min(det1.y + det1.height / 2, det2.y + det2.height / 2)
        
        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val det1Area = det1.width * det1.height
        val det2Area = det2.width * det2.height
        val unionArea = det1Area + det2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}