package com.example.yolo_native_app

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val CHANNEL = "yolo_detector"
    private lateinit var yoloDetector: YoloDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        yoloDetector = YoloDetector(this)
        
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
                            val detections = yoloDetector.detectObjects(imageBytes)
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
    }
    
    override fun onDestroy() {
        yoloDetector.close()
        super.onDestroy()
    }
}