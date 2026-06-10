import 'dart:async';
import 'package:flutter/services.dart';

class NativeBridge {
  static const MethodChannel _channel = MethodChannel(
    'com.example.ar_ecommerce/native',
  );

  static final StreamController<Map<String, dynamic>> _messageController =
      StreamController<Map<String, dynamic>>.broadcast();

  static Stream<Map<String, dynamic>> get messages => _messageController.stream;

  static void initialize() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onNativeMessage') {
        final Map<String, dynamic> data = Map<String, dynamic>.from(call.arguments as Map);
        _messageController.add(data);
      }
      return null;
    });
  }

  static Future<Map<String, dynamic>> pingNative(String productId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'pingNative',
      {'productId': productId},
    );
    return Map<String, dynamic>.from(result ?? {});
  }

  static Future<void> openArScreen(String productId) async {
    await _channel.invokeMethod<void>('openArScreen', {'productId': productId});
  }

  static Future<void> openVrScreen(String productId) async {
    await _channel.invokeMethod<void>('openVrScreen', {'productId': productId});
  }

  static Future<void> openUnityScene(String productId) async {
    await _channel.invokeMethod<void>('openUnityScene', {'productId': productId});
  }
}
