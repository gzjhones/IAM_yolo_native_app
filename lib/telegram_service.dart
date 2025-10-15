import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'dart:typed_data';
import 'config_manager.dart';

class TelegramService {
  static const platform = MethodChannel('telegram_channel');
  
  static Future<void> initialize() async {
        
    platform.setMethodCallHandler((call) async {
      if (call.method == 'sendToTelegram') {
        final imageBytes = call.arguments['image'] as Uint8List;
        final message = call.arguments['message'] as String;
        
        await sendPhotoToTelegram(imageBytes, message);
      }
    });
  }
  
  static Future<bool> sendPhotoToTelegram(Uint8List imageBytes, String message) async {
    try {
      final botToken = ConfigManager.getTelegramBotToken();
      final chatId = ConfigManager.getTelegramChatId();
      
      if (botToken.isEmpty || chatId.isEmpty) {
        print('Telegram not configured in services_config.json');
        return false;
      }
      
      print('SENDING TO TELEGRAM');
      print('Bot Token: ${botToken.substring(0, 10)}...');
      print('Chat ID: $chatId');
      print('Image size: ${imageBytes.length} bytes');
      print('Message: $message');
      
      final url = Uri.parse('https://api.telegram.org/bot$botToken/sendPhoto');
      
      var request = http.MultipartRequest('POST', url);
      request.fields['chat_id'] = chatId;
      request.fields['caption'] = message;
      request.fields['parse_mode'] = 'HTML';
      
      request.files.add(http.MultipartFile.fromBytes(
        'photo',
        imageBytes,
        filename: 'detection_${DateTime.now().millisecondsSinceEpoch}.jpg',
      ));
      
      final response = await request.send().timeout(Duration(seconds: 15));
      final responseBody = await response.stream.bytesToString();
      
      print('📨 Response status: ${response.statusCode}');
      
      if (response.statusCode == 200) {
        print('SUCCESS: Photo sent to Telegram');
        return true;
      } else {
        print('ERROR: Status ${response.statusCode}');
        print('Response: $responseBody');
        return false;
      }
    } catch (e, stackTrace) {
      print('EXCEPTION sending to Telegram');
      print('Error: $e');
      return false;
    }
  }
}