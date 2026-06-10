import 'dart:async';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import '../models/product.dart';
import '../providers/cart_provider.dart';
import '../services/native_bridge.dart';
import '../services/product_service.dart';
import '../widgets/cart_badge.dart';

class ProductDetailScreen extends StatefulWidget {
  final String productId;
  const ProductDetailScreen({super.key, required this.productId});

  @override
  State<ProductDetailScreen> createState() => _ProductDetailScreenState();
}

class _ProductDetailScreenState extends State<ProductDetailScreen> {
  String _nativeStatus = '';
  bool _loading = false;
  bool _fetchingProduct = true;
  StreamSubscription? _nativeSubscription;

  Product? _product;

  @override
  void initState() {
    super.initState();
    _fetchProduct();

    _nativeSubscription = NativeBridge.messages.listen((data) {
      if (mounted && data['productId'] == widget.productId) {
        setState(() {
          _nativeStatus = data['message'] ?? '';
          _loading = false;
        });
      }
    });
  }

  @override
  void dispose() {
    _nativeSubscription?.cancel();
    super.dispose();
  }

  Future<void> _fetchProduct() async {
    final productService = context.read<ProductService>();
    final p = await productService.getProductById(widget.productId);
    if (mounted) {
      setState(() {
        _product = p;
        _fetchingProduct = false;
      });
    }
  }

  Future<void> _launch(Future<void> Function() action, String label) async {
    setState(() {
      _loading = true;
      _nativeStatus = 'Opening $label…';
    });
    try {
      await action();
    } catch (e) {
      if (mounted) {
        setState(() {
          _nativeStatus = 'Error: $e';
          _loading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_fetchingProduct) {
      return Scaffold(
        appBar: AppBar(title: const Text('Loading...')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    if (_product == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Product Not Found')),
        body: const Center(child: Text('Product not found.')),
      );
    }

    final cart = context.watch<CartProvider>();
    final inCart = cart.isInCart(_product!.id);

    return Scaffold(
      appBar: AppBar(
        title: Text(_product!.name),
        backgroundColor: Theme.of(context).colorScheme.primaryContainer,
        actions: [CartBadge(onPressed: () => context.push('/cart'))],
      ),
      body: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ProductHero(product: _product!),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _priceRow(context),
                  const SizedBox(height: 12),
                  _ratingRow(context),
                  const Divider(height: 24),
                  _section(context, 'Description', _product!.description),
                  const SizedBox(height: 12),
                  _section(context, 'Specifications', _product!.specs),
                  const SizedBox(height: 8),
                  _section(context, 'Room Fit', _product!.roomFit),
                  const Divider(height: 24),
                  _NativeLaunchSection(
                    product: _product!,
                    loading: _loading,
                    status: _nativeStatus,
                    onAr: () =>
                        _launch(() => NativeBridge.openArScreen(_product!.id), 'AR View'),
                    onVr: () =>
                        _launch(() => NativeBridge.openVrScreen(_product!.id), 'VR View'),
                    onUnity: () =>
                        _launch(() => NativeBridge.openUnityScene(_product!.id), 'Unity Showroom'),
                  ),
                  const SizedBox(height: 80),
                ],
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: _BottomBar(
        inCart: inCart,
        onAdd: () {
          context.read<CartProvider>().addItem(_product!);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('${_product!.name} added to cart')),
          );
        },
        onGoToCart: () => context.push('/cart'),
      ),
    );
  }

  Widget _priceRow(BuildContext context) => Row(
        children: [
          Text(
            '₹${_product!.price.toStringAsFixed(0)}',
            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: Theme.of(context).colorScheme.primary,
                ),
          ),
          const Spacer(),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.secondaryContainer,
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(_product!.category, style: Theme.of(context).textTheme.labelMedium),
          ),
        ],
      );

  Widget _ratingRow(BuildContext context) => Row(
        children: [
          ...List.generate(
              5,
              (i) => Icon(
                    i < _product!.rating.floor()
                        ? Icons.star_rounded
                        : Icons.star_outline_rounded,
                    size: 18,
                    color: Colors.amber[700],
                  )),
          const SizedBox(width: 6),
          Text('${_product!.rating}', style: Theme.of(context).textTheme.bodyMedium),
          Text(' · ${_product!.reviewCount} reviews',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.grey)),
        ],
      );

  Widget _section(BuildContext context, String title, String body) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          Text(body, style: Theme.of(context).textTheme.bodyMedium),
        ],
      );
}

class _ProductHero extends StatelessWidget {
  final Product product;
  const _ProductHero({required this.product});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 220,
      width: double.infinity,
      color: Color(product.colorHex).withValues(alpha: 0.15),
      child: Center(
        child: Icon(
          _iconFor(product.category),
          size: 110,
          color: Color(product.colorHex).withValues(alpha: 0.6),
        ),
      ),
    );
  }

  IconData _iconFor(String cat) {
    switch (cat) {
      case 'Lighting':
        return Icons.lightbulb_outline_rounded;
      case 'Fans':
        return Icons.air_rounded;
      case 'Furniture':
        return Icons.chair_alt_rounded;
      default:
        return Icons.inventory_2_rounded;
    }
  }
}

class _NativeLaunchSection extends StatelessWidget {
  final Product product;
  final bool loading;
  final String status;
  final VoidCallback onAr;
  final VoidCallback onVr;
  final VoidCallback onUnity;

  const _NativeLaunchSection({
    required this.product,
    required this.loading,
    required this.status,
    required this.onAr,
    required this.onVr,
    required this.onUnity,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Visualize in your space',
          style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 4),
        Text(
          'All experiences run natively (ARCore / SceneKit / Unity) — no Flutter plugins.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.grey),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
                child: _LaunchButton(
              icon: Icons.view_in_ar_rounded,
              label: 'AR View',
              subtitle: 'ARCore / ARKit',
              color: Colors.deepOrange,
              onTap: loading ? null : onAr,
            )),
            const SizedBox(width: 10),
            Expanded(
                child: _LaunchButton(
              icon: Icons.vrpano_rounded,
              label: 'VR 360°',
              subtitle: 'Cardboard / SceneKit',
              color: Colors.indigo,
              onTap: loading ? null : onVr,
            )),
            const SizedBox(width: 10),
            Expanded(
                child: _LaunchButton(
              icon: Icons.auto_awesome_rounded,
              label: 'Unity 3D',
              subtitle: 'Unity Showroom',
              color: Colors.teal,
              onTap: loading ? null : onUnity,
            )),
          ],
        ),
        if (status.isNotEmpty) ...[
          const SizedBox(height: 12),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              children: [
                if (loading)
                  const SizedBox(
                      width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                if (loading) const SizedBox(width: 8),
                Expanded(child: Text(status, style: Theme.of(context).textTheme.bodySmall)),
              ],
            ),
          ),
        ],
      ],
    );
  }
}

class _LaunchButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final String subtitle;
  final Color color;
  final VoidCallback? onTap;

  const _LaunchButton({
    required this.icon,
    required this.label,
    required this.subtitle,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 6),
        decoration: BoxDecoration(
          border: Border.all(color: color.withValues(alpha: 0.4)),
          borderRadius: BorderRadius.circular(10),
          color: color.withValues(alpha: 0.06),
        ),
        child: Column(
          children: [
            Icon(icon, color: color, size: 26),
            const SizedBox(height: 4),
            Text(label,
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: color)),
            Text(subtitle,
                style: const TextStyle(fontSize: 9, color: Colors.grey),
                textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

class _BottomBar extends StatelessWidget {
  final bool inCart;
  final VoidCallback onAdd;
  final VoidCallback onGoToCart;

  const _BottomBar({required this.inCart, required this.onAdd, required this.onGoToCart});

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            Expanded(
              child: FilledButton.icon(
                onPressed: inCart ? onGoToCart : onAdd,
                icon: Icon(inCart ? Icons.shopping_cart_rounded : Icons.add_shopping_cart_rounded),
                label: Text(inCart ? 'Go to Cart' : 'Add to Cart'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
