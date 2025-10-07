import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';

class GoogleSheetsService {
  static const platform = MethodChannel('google_sheets');
  
  static const String webAppUrl = '';
  
  static void initialize() {
    platform.setMethodCallHandler((call) async {
      if (call.method == 'sendToSheets') {
        final data = call.arguments['data'] as Map<dynamic, dynamic>;
        await sendToGoogleSheets(data);
      }
    });
  }
  
  static Future<bool> sendToGoogleSheets(Map<dynamic, dynamic> data) async {
    try {
      print('Enviando datos a Google Sheets: $data');
      
      final response = await http.post(
        Uri.parse(webAppUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'detectionId': data['detectionId'],
          'className': data['className'],
          'classId': data['classId'],
          'confidence': data['confidence'],
          'date': data['date'],
          'time': data['time'],
          'timestamp': data['timestamp'],
          'coordinates': {
            'x': data['x'],
            'y': data['y'],
            'width': data['width'],
            'height': data['height'],
          },
          'inferenceTimeMs': data['inferenceTimeMs'],
          'deviceId': data['deviceId'],
          'alertType': data['alertType'],
        }),
      );
      
      if (response.statusCode == 200) {
        print('Datos enviados exitosamente a Google Sheets');
        return true;
      } else {
        print('Error al enviar: ${response.statusCode}');
        return false;
      }
    } catch (e) {
      print('Excepción al enviar a Google Sheets: $e');
      return false;
    }
  }
}
