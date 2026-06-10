import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/cart_item.dart';
import '../models/product.dart';

class CartProvider extends ChangeNotifier {
  final Map<String, CartItem> _items = {};
  static const String _cartKey = 'user_cart_data';

  List<CartItem> get items => _items.values.toList();
  int get itemCount => _items.values.fold(0, (sum, item) => sum + item.quantity);
  double get totalAmount => _items.values.fold(0.0, (sum, item) => sum + item.totalPrice);

  bool isInCart(String productId) => _items.containsKey(productId);

  Future<void> loadCart() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cartString = prefs.getString(_cartKey);
      if (cartString != null) {
        final Map<String, dynamic> cartData = json.decode(cartString);
        _items.clear();
        cartData.forEach((key, value) {
          _items[key] = CartItem.fromJson(value as Map<String, dynamic>);
        });
        notifyListeners();
      }
    } catch (e) {
      debugPrint('Error loading cart: $e');
    }
  }

  Future<void> _saveCart() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cartData = _items.map((key, value) => MapEntry(key, value.toJson()));
      await prefs.setString(_cartKey, json.encode(cartData));
    } catch (e) {
      debugPrint('Error saving cart: $e');
    }
  }

  void addItem(Product product) {
    if (_items.containsKey(product.id)) {
      _items[product.id]!.quantity++;
    } else {
      _items[product.id] = CartItem(product: product);
    }
    notifyListeners();
    _saveCart();
  }

  void removeItem(String productId) {
    _items.remove(productId);
    notifyListeners();
    _saveCart();
  }

  void decreaseQuantity(String productId) {
    if (!_items.containsKey(productId)) return;
    if (_items[productId]!.quantity <= 1) {
      _items.remove(productId);
    } else {
      _items[productId]!.quantity--;
    }
    notifyListeners();
    _saveCart();
  }

  void clearCart() {
    _items.clear();
    notifyListeners();
    _saveCart();
  }
}
