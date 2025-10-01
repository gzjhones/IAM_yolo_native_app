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
            // Cargar modelo
            val modelBuffer = FileUtil.loadMappedFile(context, "yolo11s_float32.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            
            // Cargar labels
            labels = loadLabels()
            
            println("✅ Modelo cargado exitosamente")
            println("📊 Input shape: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}")
            println("📊 Output shape: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
            println("🏷️ Labels: $labels")
            
            true
        } catch (e: Exception) {
            println("❌ Error cargando modelo: ${e.message}")
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
        } catch (e: Exception) {
            println("⚠️ Error cargando labels: ${e.message}")
            // Fallback si no hay labels.txt
            labelList.add("mando_xbox")
        }
        return labelList
    }
    
    fun detectObjects(imageBytes: ByteArray): List<Detection> {
        if (interpreter == null) {
            println("❌ Interpreter no inicializado")
            return emptyList()
        }
        
        return try {
            // Convertir bytes a Bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            println("🖼️ Imagen original: ${bitmap.width}x${bitmap.height}")
            
            // Redimensionar a 640x640
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Preparar input tensor
            val inputArray = prepareInput(resizedBitmap)
            
            // Preparar output tensor [1, 5, 8400]
            val outputArray = Array(1) { Array(84) { FloatArray(8400) } }
            
            // Ejecutar inferencia
            println("🔄 Ejecutando inferencia...")
            val startTime = System.currentTimeMillis()
            interpreter?.run(inputArray, outputArray)
            val inferenceTime = System.currentTimeMillis() - startTime
            println("✅ Inferencia completada en ${inferenceTime}ms")
            
            // Procesar resultados
            val detections = processOutput(outputArray[0], bitmap.width, bitmap.height)
            
            bitmap.recycle()
            resizedBitmap.recycle()
            
            detections
        } catch (e: Exception) {
            println("❌ Error en detección: ${e.message}")
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
        val confidenceThreshold = 0.50f  // Subir el umbral
        
        println("🔍 Procesando ${output[0].size} detecciones potenciales...")
        
        // YOLOv11 base: output[0-3]=coords, output[4-83]=80 clases
        for (i in 0 until 8400) {
            val xNorm = output[0][i]
            val yNorm = output[1][i]
            val wNorm = output[2][i]
            val hNorm = output[3][i]
            
            // Encontrar la clase con mayor confianza
            var maxConfidence = 0f
            var bestClassId = -1
            
            for (c in 0 until 80) {
                val classConf = output[4 + c][i]
                if (classConf > maxConfidence) {
                    maxConfidence = classConf
                    bestClassId = c
                }
            }
            
            // Filtrar por confianza
            if (maxConfidence > confidenceThreshold && wNorm > 0 && hNorm > 0) {
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
                    width = wModel * scaleX * 0.85f,
                    height = hModel * scaleY * 0.85f
                ))
            }
        }
        
        println("🎯 Detecciones encontradas: ${detections.size}")
        return applyNMS(detections)
    }
    
    private fun applyNMS(detections: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        val sortedDetections = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        
        while (sortedDetections.isNotEmpty()) {
            val best = sortedDetections.removeAt(0)
            result.add(best)
            
            sortedDetections.removeAll { detection ->
                if (best.classId == detection.classId) {
                    calculateIoU(best, detection) > iouThreshold
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