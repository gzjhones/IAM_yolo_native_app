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
            // Load model
            val modelBuffer = FileUtil.loadMappedFile(context, "yolo11s_float32.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            
            // Load labels
            labels = loadLabels()
            
            println("Model loaded successfully")
            println("Input shape: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}")
            println("Output shape: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
            println("Labels: $labels")
            
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
        } catch (e: Exception) {
            println("Error loading labels: ${e.message}")
            // Fallback if no labels.txt
            labelList.add("mando_xbox")
        }
        return labelList
    }
    
    fun detectObjects(imageBytes: ByteArray): List<Detection> {
        if (interpreter == null) {
            println("Interpreter not initialized")
            return emptyList()
        }
        
        return try {
            // Convert bytes to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            println("Original image: ${bitmap.width}x${bitmap.height}")
            
            // Resize to 640x640
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Prepare input tensor
            val inputArray = prepareInput(resizedBitmap)
            
            // Prepare output tensor [1, 5, 8400]
            val outputArray = Array(1) { Array(5) { FloatArray(8400) } }
            
            // Run inference
            println("Running inference...")
            val startTime = System.currentTimeMillis()
            interpreter?.run(inputArray, outputArray)
            val inferenceTime = System.currentTimeMillis() - startTime
            println("Inference completed in ${inferenceTime}ms")
            
            // Process results
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
        val confidenceThreshold = 0.25f  // Low threshold for debugging
        
        println("Processing ${output[0].size} potential detections...")
        
        var validCount = 0
        
        // YOLOv11 with 1 class: output[0]=x, output[1]=y, output[2]=w, output[3]=h, output[4]=confidence
        for (i in 0 until 8400) {
            val xNorm = output[0][i]
            val yNorm = output[1][i]
            val wNorm = output[2][i]
            val hNorm = output[3][i]
            val confidence = output[4][i]
            
            // Debug: print first 10 detections
            if (i < 10) {
                println("Det[$i]: x=%.2f, y=%.2f, w=%.2f, h=%.2f, conf=%.4f".format(xNorm, yNorm, wNorm, hNorm, confidence))
            }
            
            // Filter by confidence and valid size
            if (confidence > confidenceThreshold && wNorm > 0 && hNorm > 0) {
                validCount++
                
                if (validCount <= 5) {
                    println("Valid detection #$validCount: conf=${confidence}")
                }
                
                // CORRECTION: Denormalize by multiplying by inputSize (640)
                // Coordinates come normalized [0-1] relative to 640x640 size
                val xModel = xNorm * inputSize  // Convert to model coordinates
                val yModel = yNorm * inputSize
                val wModel = wNorm * inputSize
                val hModel = hNorm * inputSize
                
                // Then scale to original image size
                val scaleX = imageWidth.toFloat() / inputSize
                val scaleY = imageHeight.toFloat() / inputSize

                val paddingReduction = 0.50f
                
                detections.add(Detection(
                    classId = 0,
                    className = if (labels.isNotEmpty()) labels[0] else "mando_xbox",
                    confidence = confidence,
                    x = xModel * scaleX,
                    y = yModel * scaleY,
                    width = wModel * scaleX * paddingReduction,
                    height = hModel * scaleY * paddingReduction
                ))
            }
        }
        
        println("Detections with conf > $confidenceThreshold: $validCount")
        
        if (detections.isEmpty()) {
            println("Warning: No detections found. Check:")
            println("   - Is the model trained correctly?")
            println("   - Is the lighting adequate?")
            println("   - Is the object in the frame?")
        }
        
        // Apply NMS
        val finalDetections = applyNMS(detections)
        println("Final detections after NMS: ${finalDetections.size}")
        
        return finalDetections
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