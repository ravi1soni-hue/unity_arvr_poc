import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'providers/cart_provider.dart';
import 'screens/home_screen.dart';
import 'screens/product_detail_screen.dart';
import 'screens/cart_screen.dart';
import 'screens/checkout_screen.dart';
import 'screens/order_success_screen.dart';
import 'services/product_service.dart';
import 'services/native_bridge.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  NativeBridge.initialize();

  runApp(
    MultiProvider(
      providers: [
        Provider(create: (_) => ProductService()),
        ChangeNotifierProvider(
          create: (context) => CartProvider()..loadCart(),
        ),
      ],
      child: const FloorFlowApp(),
    ),
  );
}

final _router = GoRouter(
  routes: [
    GoRoute(path: '/', builder: (context, _) => const HomeScreen()),
    GoRoute(
      path: '/product/:id',
      builder: (context, state) => ProductDetailScreen(productId: state.pathParameters['id']!),
    ),
    GoRoute(path: '/cart', builder: (context, _) => const CartScreen()),
    GoRoute(path: '/checkout', builder: (context, _) => const CheckoutScreen()),
    GoRoute(path: '/order-success', builder: (context, _) => const OrderSuccessScreen()),
  ],
);

class FloorFlowApp extends StatelessWidget {
  const FloorFlowApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'FloorFlow AR Commerce',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepOrange),
        useMaterial3: true,
      ),
      routerConfig: _router,
    );
  }
}
