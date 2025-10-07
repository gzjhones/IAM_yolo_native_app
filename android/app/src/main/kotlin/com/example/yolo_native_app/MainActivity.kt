package com.example.yolo_native_app

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity: FlutterActivity() {
    private val CHANNEL = "yolo_detector"
    private val SHEETS_CHANNEL = "google_sheets"
    private lateinit var yoloDetector: YoloDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        yoloDetector = YoloDetector(this)
        
        // Canal principal de detección
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "loadModel" -> {
                    Thread {
                        val success = yoloDetector.loadModel()
                        mainHandler.post {
                            result.success(success)
                        }
                    }.start()
                }
                
                "detectObjects" -> {
                    val imageBytes = call.argument<ByteArray>("image")
                    if (imageBytes != null) {
                        Thread {
                            val startTime = System.currentTimeMillis()
                            val detections = yoloDetector.detectObjects(imageBytes)
                            val inferenceTime = System.currentTimeMillis() - startTime
                            
                            val detectionsMap = detections.map { detection ->
                                mapOf(
                                    "classId" to detection.classId,
                                    "className" to detection.className,
                                    "confidence" to detection.confidence,
                                    "x" to detection.x,
                                    "y" to detection.y,
                                    "width" to detection.width,
                                    "height" to detection.height
                                )
                            }
                            
                            // Enviar a Google Sheets si cumple las condiciones
                            checkAndSendToSheets(detections, inferenceTime)
                            
                            mainHandler.post {
                                result.success(detectionsMap)
                            }
                        }.start()
                    } else {
                        result.error("INVALID_ARGUMENT", "Image bytes are null", null)
                    }
                }
                
                else -> {
                    result.notImplemented()
                }
            }
        }
        
        // Canal para Google Sheets (manejado desde Flutter)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SHEETS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "sendToSheets" -> {
                    val data = call.argument<Map<String, Any>>("data")
                    if (data != null) {
                        // Este método será implementado en Flutter/Dart
                        result.success(true)
                    } else {
                        result.error("INVALID_DATA", "Data is null", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }
    
    private fun checkAndSendToSheets(detections: List<YoloDetector.Detection>, inferenceTime: Long) {
        detections.forEach { detection ->
            val confidence = detection.confidence * 100
            
            // Reglas: >70% o <40%
            if (confidence > 70 || confidence < 40) {
                val data = prepareSheetData(detection, inferenceTime)
                sendToFlutterChannel(data)
            }
        }
    }
    
    private fun prepareSheetData(detection: YoloDetector.Detection, inferenceTime: Long): Map<String, Any> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentDate = Date()
        
        return mapOf(
            "detectionId" to "${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}",
            "className" to detection.className,
            "classId" to detection.classId,
            "confidence" to String.format("%.2f", detection.confidence * 100),
            "date" to dateFormat.format(currentDate),
            "time" to timeFormat.format(currentDate),
            "timestamp" to System.currentTimeMillis(),
            "x" to String.format("%.2f", detection.x),
            "y" to String.format("%.2f", detection.y),
            "width" to String.format("%.2f", detection.width),
            "height" to String.format("%.2f", detection.height),
            "inferenceTimeMs" to inferenceTime,
            "deviceId" to getAndroidDeviceId(),
            "alertType" to if (detection.confidence * 100 > 70) "HIGH_CONFIDENCE" else "LOW_CONFIDENCE"
        )
    }
    
    private fun sendToFlutterChannel(data: Map<String, Any>) {
        mainHandler.post {
            try {
                val channel = MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, SHEETS_CHANNEL)
                channel.invokeMethod("sendToSheets", mapOf("data" to data))
            } catch (e: Exception) {
                println("Error enviando a canal Flutter: ${e.message}")
            }
        }
    }
    
    private fun getAndroidDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }
    
    override fun onDestroy() {
        yoloDetector.close()
        super.onDestroy()
    }
}