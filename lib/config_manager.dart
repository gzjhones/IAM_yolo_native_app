import 'dart:convert';
import 'package:flutter/services.dart';

class ConfigManager {
  static Map<String, dynamic>? _config;
  
  static Future<void> load() async {
    try {
      final String jsonString = await rootBundle.loadString('assets/services_config.json');
      _config = json.decode(jsonString);
      print('Configuration loaded successfully');
      print('Telegram threshold: ${getTelegramThreshold()}%');
      print('Telegram configured: ${isTelegramConfigured()}');
    } catch (e) {
      print('Error loading config: $e');
      _config = _getDefaultConfig();
    }
  }
  
  static Map<String, dynamic> _getDefaultConfig() {
    return {
      'telegram': {
        'bot_token': '',
        'chat_id': '',
        'threshold': 75
      },
      'google_sheets': {
        'enabled': true,
        'high_confidence_threshold': 70,
        'low_confidence_threshold': 40
      }
    };
  }
  
  // Telegram getters
  static String getTelegramBotToken() {
    return _config?['telegram']?['bot_token'] ?? '';
  }
  
  static String getTelegramChatId() {
    return _config?['telegram']?['chat_id'] ?? '';
  }
  
  static int getTelegramThreshold() {
    return _config?['telegram']?['threshold'] ?? 75;
  }
  
  static bool isTelegramConfigured() {
    final botToken = getTelegramBotToken();
    final chatId = getTelegramChatId();
    return botToken.isNotEmpty && chatId.isNotEmpty;
  }
  
  // Google Sheets getters
  static bool isGoogleSheetsEnabled() {
    return _config?['google_sheets']?['enabled'] ?? true;
  }
  
  static int getHighConfidenceThreshold() {
    return _config?['google_sheets']?['high_confidence_threshold'] ?? 70;
  }
  
  static int getLowConfidenceThreshold() {
    return _config?['google_sheets']?['low_confidence_threshold'] ?? 40;
  }
  
  // Debug info
  static void printConfig() {
    print('Telegram:');
    print('  - Bot Token: ${getTelegramBotToken().isNotEmpty ? "Configured" : "Not configured"}');
    print('  - Chat ID: ${getTelegramChatId()}');
    print('  - Threshold: ${getTelegramThreshold()}%');
    print('');
    print('Google Sheets:');
    print('  - Enabled: ${isGoogleSheetsEnabled()}');
    print('  - High threshold: >${getHighConfidenceThreshold()}%');
    print('  - Low threshold: <${getLowConfidenceThreshold()}%');
  }
}