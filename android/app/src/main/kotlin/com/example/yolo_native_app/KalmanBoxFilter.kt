package com.example.yolo_native_app

/**
 * Kalman filter escalar (posición + velocidad, modelo de velocidad constante).
 * F = [[1,1],[0,1]], H = [1,0]. Ruido de proceso/medición diagonal.
 */
private class Kalman1D(
    initialPosition: Float,
    private val processNoisePos: Float = 1f,
    private val processNoiseVel: Float = 10f,
    private val measurementNoise: Float = 10f
) {
    private var pos = initialPosition
    private var vel = 0f

    // Covarianza 2x2 [[p00,p01],[p10,p11]]
    private var p00 = 1000f
    private var p01 = 0f
    private var p10 = 0f
    private var p11 = 1000f

    val position: Float get() = pos

    fun predict() {
        pos += vel

        val newP00 = p00 + p01 + p10 + p11 + processNoisePos
        val newP01 = p01 + p11
        val newP10 = p10 + p11
        val newP11 = p11 + processNoiseVel

        p00 = newP00
        p01 = newP01
        p10 = newP10
        p11 = newP11
    }

    fun update(measurement: Float) {
        val innovation = measurement - pos
        val s = p00 + measurementNoise
        val k0 = p00 / s
        val k1 = p10 / s

        pos += k0 * innovation
        vel += k1 * innovation

        val newP00 = (1 - k0) * p00
        val newP01 = (1 - k0) * p01
        val newP10 = p10 - k1 * p00
        val newP11 = p11 - k1 * p01

        p00 = newP00
        p01 = newP01
        p10 = newP10
        p11 = newP11
    }
}

/**
 * Kalman de un bounding box [x,y,w,h] como 4 filtros 1D independientes en vez de una
 * matriz 8x8 con correlación cruzada entre dimensiones: bajo ruido diagonal es
 * equivalente y evita álgebra matricial general (más liviano para tiempo real on-device).
 */
class BoxKalmanFilter(detection: YoloDetector.Detection) {
    private val kx = Kalman1D(detection.x)
    private val ky = Kalman1D(detection.y)
    private val kw = Kalman1D(detection.width)
    private val kh = Kalman1D(detection.height)

    val x: Float get() = kx.position
    val y: Float get() = ky.position
    val width: Float get() = kw.position
    val height: Float get() = kh.position

    fun predict() {
        kx.predict()
        ky.predict()
        kw.predict()
        kh.predict()
    }

    fun update(detection: YoloDetector.Detection) {
        kx.update(detection.x)
        ky.update(detection.y)
        kw.update(detection.width)
        kh.update(detection.height)
    }
}
