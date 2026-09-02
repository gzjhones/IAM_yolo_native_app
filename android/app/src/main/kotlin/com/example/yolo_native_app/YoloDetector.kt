package com.example.yolo_native_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class YoloDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    // Derivadas del tensor de entrada real del modelo (soporta cualquier export YOLOv8/v11)
    private var inputWidth = 640
    private var inputHeight = 640
    private var inputIsNCHW = false // true: [1,3,H,W]   false: [1,H,W,3]

    // Derivadas del tensor de salida real del modelo
    private var numClasses = 0
    private var numAnchors = 0
    private var outputChannelsFirst = true // true: [1, 4+nc, anchors]   false: [1, anchors, 4+nc]

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
            val modelFileName = findModelFile()
            if (modelFileName == null) {
                println("❌ No se encontró ningún archivo .tflite en assets")
                return false
            }

            val modelBuffer = FileUtil.loadMappedFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val newInterpreter = Interpreter(modelBuffer, options)
            interpreter = newInterpreter

            inspectInputTensor(newInterpreter)
            inspectOutputTensor(newInterpreter)

            // Carga manual de labels.txt (formato fijo: un nombre de clase por línea)
            labels = loadLabels()

            println("Modelo cargado exitosamente: $modelFileName")
            println("Input: ${inputWidth}x${inputHeight}, formato=${if (inputIsNCHW) "NCHW" else "NHWC"}")
            println("Output: numClasses=$numClasses, numAnchors=$numAnchors, layout=${if (outputChannelsFirst) "[1,C,N]" else "[1,N,C]"}")
            println("Labels cargadas: ${labels.size}")
            if (labels.size != numClasses) {
                println("⚠️ labels.txt tiene ${labels.size} etiquetas pero el modelo tiene $numClasses clases. Revisa el archivo.")
            }

            true
        } catch (e: Exception) {
            println("Error cargando modelo: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun findModelFile(): String? {
        return context.assets.list("")
            ?.firstOrNull { it.endsWith(".tflite", ignoreCase = true) }
    }

    private fun inspectInputTensor(interpreter: Interpreter) {
        val shape = interpreter.getInputTensor(0).shape() // [1, ?, ?, ?]
        require(shape.size == 4) { "Tensor de entrada inesperado: ${shape.contentToString()}" }

        // El eje de canales es el que vale 3 (RGB); los otros dos son alto/ancho.
        if (shape[1] == 3) {
            inputIsNCHW = true
            inputHeight = shape[2]
            inputWidth = shape[3]
        } else {
            inputIsNCHW = false
            inputHeight = shape[1]
            inputWidth = shape[2]
        }
    }

    private fun inspectOutputTensor(interpreter: Interpreter) {
        val shape = interpreter.getOutputTensor(0).shape() // [1, ?, ?]
        require(shape.size == 3) { "Tensor de salida inesperado: ${shape.contentToString()}" }

        val d1 = shape[1]
        val d2 = shape[2]
        // El eje de canales (4 coords + clases) siempre es mucho menor que el de anchors.
        if (d1 <= d2) {
            outputChannelsFirst = true
            numClasses = d1 - 4
            numAnchors = d2
        } else {
            outputChannelsFirst = false
            numClasses = d2 - 4
            numAnchors = d1
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
        val interpreter = this.interpreter
        if (interpreter == null) {
            println("❌ Interpreter no inicializado")
            return emptyList()
        }

        return try {
            // Convertir bytes a Bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            println("Imagen original: ${bitmap.width}x${bitmap.height}")

            // Redimensionar al tamaño real de entrada del modelo
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

            // Preparar input respetando el layout real del modelo (NCHW o NHWC)
            val inputBuffer = prepareInput(resizedBitmap)

            // Preparar output con la forma real del modelo
            val rawOutput = allocateOutputBuffer()

            // Ejecutar inferencia
            println("Ejecutando inferencia...")
            val startTime = System.currentTimeMillis()
            interpreter.run(inputBuffer, rawOutput)
            val inferenceTime = System.currentTimeMillis() - startTime
            println("Inferencia completada en ${inferenceTime}ms")

            // Normalizar a [channels][anchors] sea cual sea el layout real y procesar
            val normalizedOutput = normalizeOutput(rawOutput)
            val detections = processOutput(normalizedOutput, bitmap.width, bitmap.height)

            bitmap.recycle()
            resizedBitmap.recycle()

            detections
        } catch (e: Exception) {
            println("❌ Error en detección: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private fun prepareInput(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * 3 * inputWidth * inputHeight)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        if (inputIsNCHW) {
            // [1, 3, H, W]: primero todo el canal R, luego todo G, luego todo B
            for (c in 0 until 3) {
                val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
                for (pixel in pixels) {
                    buffer.putFloat(((pixel shr shift) and 0xFF) / 255.0f)
                }
            }
        } else {
            // [1, H, W, 3]: por cada pixel, R, G, B intercalados
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
                buffer.putFloat((pixel and 0xFF) / 255.0f)          // B
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun allocateOutputBuffer(): Array<Array<FloatArray>> {
        val channels = numClasses + 4
        return if (outputChannelsFirst) {
            Array(1) { Array(channels) { FloatArray(numAnchors) } }
        } else {
            Array(1) { Array(numAnchors) { FloatArray(channels) } }
        }
    }

    // Normaliza el output real a [channels][anchors], sea cual sea el layout físico del modelo
    private fun normalizeOutput(rawOutput: Array<Array<FloatArray>>): Array<FloatArray> {
        if (outputChannelsFirst) {
            return rawOutput[0]
        }
        val channels = numClasses + 4
        val transposed = Array(channels) { FloatArray(numAnchors) }
        for (a in 0 until numAnchors) {
            val row = rawOutput[0][a]
            for (c in 0 until channels) {
                transposed[c][a] = row[c]
            }
        }
        return transposed
    }

    private fun processOutput(output: Array<FloatArray>, imageWidth: Int, imageHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        val confidenceThreshold = 0.50f

        println("Procesando $numAnchors detecciones potenciales...")

        // output[0-3] = coords, output[4 .. 4+numClasses-1] = confianza por clase
        for (i in 0 until numAnchors) {
            val xNorm = output[0][i]
            val yNorm = output[1][i]
            val wNorm = output[2][i]
            val hNorm = output[3][i]

            // Encontrar la clase con mayor confianza
            var maxConfidence = 0f
            var bestClassId = -1

            for (c in 0 until numClasses) {
                val classConf = output[4 + c][i]
                if (classConf > maxConfidence) {
                    maxConfidence = classConf
                    bestClassId = c
                }
            }

            // Filtrar por confianza
            if (maxConfidence > confidenceThreshold && wNorm > 0 && hNorm > 0) {
                // Desnormalizar coordenadas al tamaño real de entrada del modelo
                val xModel = xNorm * inputWidth
                val yModel = yNorm * inputHeight
                val wModel = wNorm * inputWidth
                val hModel = hNorm * inputHeight

                val scaleX = imageWidth.toFloat() / inputWidth
                val scaleY = imageHeight.toFloat() / inputHeight

                detections.add(Detection(
                    classId = bestClassId,
                    className = if (bestClassId in labels.indices) labels[bestClassId] else "class_$bestClassId",
                    confidence = maxConfidence,
                    x = xModel * scaleX,
                    y = yModel * scaleY,
                    width = wModel * scaleX * 0.85f,
                    height = hModel * scaleY * 0.85f
                ))
            }
        }

        println("Detecciones encontradas: ${detections.size}")
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
