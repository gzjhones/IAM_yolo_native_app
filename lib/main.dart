import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:camera/camera.dart';
import 'dart:typed_data';
import 'dart:async';
import 'package:image/image.dart' as img;
import 'google_sheets_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  GoogleSheetsService.initialize();
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YOLO Native Detection',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: YoloDetectionPage(),
    );
  }
}

class YoloDetectionPage extends StatefulWidget {
  @override
  _YoloDetectionPageState createState() => _YoloDetectionPageState();
}

class _YoloDetectionPageState extends State<YoloDetectionPage> {
  static const platform = MethodChannel('yolo_detector');
  
  CameraController? cameraController;
  List<Detection> detections = [];
  bool isModelLoaded = false;
  bool isDetecting = false;
  String status = 'Inicializando...';
  Size? imageSize; // Tamaño real de la imagen
  
  @override
  void initState() {
    super.initState();
    init();
  }

  
  Future<void> init() async {
    await loadModel();
    await initCamera();
  }
  
  Future<void> loadModel() async {
    try {
      setState(() => status = 'Cargando modelo...');
      final bool result = await platform.invokeMethod('loadModel');
      setState(() {
        isModelLoaded = result;
        status = result ? 'Modelo cargado' : 'Error cargando modelo';
      });
    } catch (e) {
      setState(() => status = 'Error: $e');
      print('Error cargando modelo: $e');
    }
  }
  
  Future<void> initCamera() async {
    try {
      setState(() => status = 'Inicializando cámara...');
      
      final cameras = await availableCameras();
      cameraController = CameraController(
        cameras[0],
        ResolutionPreset.medium,
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.yuv420, // Asegurar formato correcto
      );
      await cameraController!.initialize();
      setState(() => status = 'Listo');
      
      startDetection();
    } catch (e) {
      setState(() => status = 'Error cámara: $e');
      print('Error inicializando cámara: $e');
    }
  }
  
  void startDetection() {
    cameraController!.startImageStream((CameraImage image) async {
      if (!isDetecting && isModelLoaded) {
        isDetecting = true;
        // Guardar el tamaño real de la imagen
        imageSize = Size(image.width.toDouble(), image.height.toDouble());
        await detectObjects(image);
        isDetecting = false;
      }
    });
  }
  
  Future<void> detectObjects(CameraImage cameraImage) async {
    try {
      final bytes = await convertImageToBytes(cameraImage);
      
      if (bytes != null) {
        final List<dynamic> results = await platform.invokeMethod(
          'detectObjects',
          {'image': bytes},
        );
        
        setState(() {
          detections = results.map((d) => Detection.fromMap(d)).toList();
        });
      }
    } catch (e) {
      print('Error en detección: $e');
    }
  }
  
  Future<Uint8List?> convertImageToBytes(CameraImage image) async {
    try {
      img.Image? imgImage = convertYUV420ToImage(image);
      
      if (imgImage != null) {
        return Uint8List.fromList(img.encodeJpg(imgImage, quality: 85));
      }
    } catch (e) {
      print('Error convirtiendo imagen: $e');
    }
    return null;
  }
  
  img.Image? convertYUV420ToImage(CameraImage image) {
    final width = image.width;
    final height = image.height;
    
    final imgImage = img.Image(width: width, height: height);
    
    final yPlane = image.planes[0];
    final uPlane = image.planes[1];
    final vPlane = image.planes[2];
    
    // Validar que tenemos suficientes datos
    if (yPlane.bytes.isEmpty || uPlane.bytes.isEmpty || vPlane.bytes.isEmpty) {
      print('Error: Planos vacíos');
      return null;
    }
    
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final yIndex = y * yPlane.bytesPerRow + x;
        
        // Calcular índice UV con validación
        final uvRow = y ~/ 2;
        final uvCol = x ~/ 2;
        final uvIndex = uvRow * uPlane.bytesPerRow + uvCol;
        
        // Validar límites antes de acceder
        if (yIndex >= yPlane.bytes.length || 
            uvIndex >= uPlane.bytes.length || 
            uvIndex >= vPlane.bytes.length) {
          continue;
        }
        
        final yValue = yPlane.bytes[yIndex];
        final uValue = uPlane.bytes[uvIndex];
        final vValue = vPlane.bytes[uvIndex];
        
        final r = (yValue + 1.402 * (vValue - 128)).clamp(0, 255).toInt();
        final g = (yValue - 0.344136 * (uValue - 128) - 0.714136 * (vValue - 128)).clamp(0, 255).toInt();
        final b = (yValue + 1.772 * (uValue - 128)).clamp(0, 255).toInt();
        
        imgImage.setPixelRgba(x, y, r, g, b, 255);
      }
    }
    
    return imgImage;
  }
  @override
  void dispose() {
    cameraController?.dispose();
    super.dispose();
  }
  
  @override
  Widget build(BuildContext context) {
    if (cameraController == null || !cameraController!.value.isInitialized) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 20),
              Text(status, style: TextStyle(color: Colors.white)),
            ],
          ),
        ),
      );
    }
    
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Cámara
          CameraPreview(cameraController!),
          
          // Bounding boxes - CORREGIDO
          if (imageSize != null)
            CustomPaint(
              painter: DetectionPainter(detections, imageSize!),
            ),
          
          // Info panel - mover a la izquierda vertical
          Positioned(
            left: 20,
            top: 20,
            child: Container(
              padding: EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.7),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Detecciones: ${detections.length}',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  SizedBox(height: 6),
                  ...detections.take(3).map((d) => Text(
                    '${d.className} (${(d.confidence * 100).toStringAsFixed(1)}%)',
                    style: TextStyle(color: Colors.white70, fontSize: 12),
                  )),
                ],
              ),
            ),
          ),

          // Estado - mover a esquina superior derecha
          Positioned(
            top: 20,
            right: 20,
            child: Container(
              padding: EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: isModelLoaded ? Colors.green : Colors.red,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(
                isModelLoaded ? 'Activo' : 'Cargando',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                  fontSize: 12,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class Detection {
  final int classId;
  final String className;
  final double confidence;
  final double x;
  final double y;
  final double width;
  final double height;
  
  Detection({
    required this.classId,
    required this.className,
    required this.confidence,
    required this.x,
    required this.y,
    required this.width,
    required this.height,
  });
  
  factory Detection.fromMap(Map<dynamic, dynamic> map) {
    return Detection(
      classId: map['classId'] as int,
      className: map['className'] as String,
      confidence: (map['confidence'] as num).toDouble(),
      x: (map['x'] as num).toDouble(),
      y: (map['y'] as num).toDouble(),
      width: (map['width'] as num).toDouble(),
      height: (map['height'] as num).toDouble(),
    );
  }
}

class DetectionPainter extends CustomPainter {
  final List<Detection> detections;
  final Size imageSize; // Tamaño real de la imagen (640x480)
  
  DetectionPainter(this.detections, this.imageSize);
  
  @override
  void paint(Canvas canvas, Size size) {
    print('Canvas: ${size.width}x${size.height}');
    print('Image size: ${imageSize.width}x${imageSize.height}');
    print('Detections: ${detections.length}');
    
    // El escalado correcto: de coordenadas de imagen a coordenadas de canvas
    final scaleX = size.width / imageSize.width;
    final scaleY = size.height / imageSize.height;
    
    print('Scales: X=$scaleX, Y=$scaleY');
    
    for (var detection in detections) {
      print('Detection: x=${detection.x}, y=${detection.y}, w=${detection.width}, h=${detection.height}');
      
      // Color del bounding box
      final boxPaint = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3.0
        ..color = Colors.greenAccent.withOpacity(0.9);
      
      // Fondo del label
      final labelBgPaint = Paint()
        ..style = PaintingStyle.fill
        ..color = Colors.greenAccent.withOpacity(0.8);
      
      // Calcular coordenadas escaladas
      final left = (detection.x - detection.width / 2) * scaleX;
      final top = (detection.y - detection.height / 2) * scaleY;
      final right = (detection.x + detection.width / 2) * scaleX;
      final bottom = (detection.y + detection.height / 2) * scaleY;
      
      print('Box: left=$left, top=$top, right=$right, bottom=$bottom');
      
      final rect = Rect.fromLTRB(left, top, right, bottom);
      
      // Dibujar bounding box
      canvas.drawRect(rect, boxPaint);
      
      // Dibujar esquinas
      drawCorners(canvas, rect);
      
      // Label
      final labelText = '${detection.className} ${(detection.confidence * 100).toStringAsFixed(0)}%';
      
      final textSpan = TextSpan(
        text: labelText,
        style: TextStyle(
          color: Colors.black,
          fontSize: 14,
          fontWeight: FontWeight.bold,
        ),
      );
      
      final textPainter = TextPainter(
        text: textSpan,
        textDirection: TextDirection.ltr,
      );
      
      textPainter.layout();
      
      // Fondo del label
      final labelRect = Rect.fromLTWH(
        left,
        top - 22,
        textPainter.width + 8,
        22,
      );
      
      canvas.drawRect(labelRect, labelBgPaint);
      textPainter.paint(canvas, Offset(left + 4, top - 20));
      
      // Punto central
      drawCenterPoint(canvas, detection.x * scaleX, detection.y * scaleY);
    }
  }
  
  void drawCorners(Canvas canvas, Rect rect) {
    final cornerLength = 15.0;
    final cornerPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.0
      ..color = Colors.cyanAccent;
    
    // Esquinas
    canvas.drawLine(Offset(rect.left, rect.top), Offset(rect.left + cornerLength, rect.top), cornerPaint);
    canvas.drawLine(Offset(rect.left, rect.top), Offset(rect.left, rect.top + cornerLength), cornerPaint);
    
    canvas.drawLine(Offset(rect.right, rect.top), Offset(rect.right - cornerLength, rect.top), cornerPaint);
    canvas.drawLine(Offset(rect.right, rect.top), Offset(rect.right, rect.top + cornerLength), cornerPaint);
    
    canvas.drawLine(Offset(rect.left, rect.bottom), Offset(rect.left + cornerLength, rect.bottom), cornerPaint);
    canvas.drawLine(Offset(rect.left, rect.bottom), Offset(rect.left, rect.bottom - cornerLength), cornerPaint);
    
    canvas.drawLine(Offset(rect.right, rect.bottom), Offset(rect.right - cornerLength, rect.bottom), cornerPaint);
    canvas.drawLine(Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - cornerLength), cornerPaint);
  }
  
  void drawCenterPoint(Canvas canvas, double x, double y) {
    final centerPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = Colors.redAccent;
    
    canvas.drawCircle(Offset(x, y), 4, centerPaint);
    
    final centerBorderPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..color = Colors.white;
    
    canvas.drawCircle(Offset(x, y), 4, centerBorderPaint);
  }
  
  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}