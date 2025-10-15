package com.example.yolo_native_app

import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity: FlutterActivity() {
    private val CHANNEL = "yolo_detector"
    private val SHEETS_CHANNEL = "google_sheets"
    private val TELEGRAM_CHANNEL = "telegram_channel"
    private lateinit var yoloDetector: YoloDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        yoloDetector = YoloDetector(this)
        
        // Canal principal de detección YOLO
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
                            
                            // Verificar y enviar a Google Sheets
                            checkAndSendToSheets(detections, inferenceTime)
                            
                            // Verificar y enviar a Telegram
                            checkAndSendToTelegram(detections, imageBytes, inferenceTime)
                            
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
        
        // Canal para Google Sheets
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SHEETS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "sendToSheets" -> {
                    val data = call.argument<Map<String, Any>>("data")
                    if (data != null) {
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
        
        // Canal para Telegram
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, TELEGRAM_CHANNEL).setMethodCallHandler { call, result ->
            result.success(true)
        }
    }
    
    private fun checkAndSendToSheets(detections: List<YoloDetector.Detection>, inferenceTime: Long) {
        println("=== VERIFICANDO DETECCIONES PARA SHEETS ===")
        println("Total detections: ${detections.size}")
        
        detections.forEach { detection ->
            val confidence = detection.confidence * 100
            println("Detection: ${detection.className}, confidence: $confidence%")
            
            if (confidence > 70 || confidence < 40) {
                println("ENVIANDO A SHEETS: ${detection.className} with ${confidence}% confidence")
                val data = prepareSheetData(detection, inferenceTime)
                sendToFlutterChannel(data, SHEETS_CHANNEL)
            } else {
                println("NO ENVIAR A SHEETS: Confidence $confidence% está entre 40-70%")
            }
        }
    }
    
    private fun checkAndSendToTelegram(
        detections: List<YoloDetector.Detection>, 
        originalImageBytes: ByteArray,
        inferenceTime: Long
    ) {
        println("=== VERIFICANDO DETECCIONES PARA TELEGRAM ===")
        
        val threshold = 75
        
        println("Telegram threshold: $threshold%")
        
        detections.forEach { detection ->
            val confidence = detection.confidence * 100
            
            if (confidence >= threshold) {
                println("ENVIANDO A TELEGRAM: ${detection.className} with ${confidence}% confidence")
                
                try {
                    val imageWithBox = drawBoundingBox(originalImageBytes, detection)
                    val message = formatTelegramMessage(detection, inferenceTime)
                    sendImageToTelegram(imageWithBox, message)
                    
                } catch (e: Exception) {
                    println("Error procesando imagen para Telegram: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("NO ENVIAR A TELEGRAM: Confidence $confidence% < $threshold%")
            }
        }
    }
    
    private fun drawBoundingBox(imageBytes: ByteArray, detection: YoloDetector.Detection): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        val canvas = Canvas(mutableBitmap)
        
        val boxPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            style = Paint.Style.FILL
        }
        
        val textBgPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        
        val left = detection.x - detection.width / 2
        val top = detection.y - detection.height / 2
        val right = detection.x + detection.width / 2
        val bottom = detection.y + detection.height / 2
        
        canvas.drawRect(left, top, right, bottom, boxPaint)
        
        val label = "${detection.className} ${String.format("%.1f", detection.confidence * 100)}%"
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        
        canvas.drawRect(
            left,
            top - textBounds.height() - 20f,
            left + textBounds.width() + 20f,
            top,
            textBgPaint
        )
        
        canvas.drawText(label, left + 10f, top - 10f, textPaint)
        
        val outputStream = ByteArrayOutputStream()
        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        
        bitmap.recycle()
        mutableBitmap.recycle()
        
        return outputStream.toByteArray()
    }
    
    private fun formatTelegramMessage(detection: YoloDetector.Detection, inferenceTime: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        
        return """
            <b>📸 Detección YOLO</b>
            ────────────────
            🏷️ <b>Clase:</b> ${detection.className}
            📊 <b>Confianza:</b> ${String.format("%.1f", detection.confidence * 100)}%
            ⏱️ <b>Tiempo:</b> ${inferenceTime}ms
            📅 <b>Fecha:</b> $currentDate
            🔗 <b>Device:</b> ${getAndroidDeviceId()}
        """.trimIndent()
    }
    
    private fun sendImageToTelegram(imageBytes: ByteArray, message: String) {
        mainHandler.post {
            try {
                val channel = MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, TELEGRAM_CHANNEL)
                channel.invokeMethod("sendToTelegram", mapOf(
                    "image" to imageBytes,
                    "message" to message
                ))
                println("Imagen enviada a canal Telegram")
            } catch (e: Exception) {
                println("Error enviando a canal Telegram: ${e.message}")
                e.printStackTrace()
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
    
    private fun sendToFlutterChannel(data: Map<String, Any>, channelName: String) {
        mainHandler.post {
            try {
                val channel = MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, channelName)
                channel.invokeMethod("sendToSheets", mapOf("data" to data))
            } catch (e: Exception) {
                println("Error sending to Flutter channel $channelName: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private fun getAndroidDeviceId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            "unknown_device"
        }
    }
    
    override fun onDestroy() {
        yoloDetector.close()
        super.onDestroy()
    }
}