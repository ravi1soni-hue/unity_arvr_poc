import 'package:flutter/material.dart';
import '../models/product.dart';

class ProductCard extends StatelessWidget {
  final Product product;
  final VoidCallback onTap;
  final VoidCallback onAddToCart;

  const ProductCard({
    super.key,
    required this.product,
    required this.onTap,
    required this.onAddToCart,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Use Flexible to allow the image to shrink if space is tight
            Flexible(
              flex: 3,
              child: _ProductImage(product: product),
            ),
            Flexible(
              flex: 4,
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.secondaryContainer,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        product.category,
                        style: Theme.of(context).textTheme.labelSmall,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      product.name,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        Icon(Icons.star_rounded, size: 14, color: Colors.amber[700]),
                        const SizedBox(width: 2),
                        Text('${product.rating}', style: Theme.of(context).textTheme.labelSmall),
                        Expanded(
                          child: Text(
                            ' (${product.reviewCount})',
                            style: Theme.of(context).textTheme.labelSmall?.copyWith(color: Colors.grey),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                    const Spacer(),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          '₹${product.price.toStringAsFixed(0)}',
                          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                            color: Theme.of(context).colorScheme.primary,
                          ),
                        ),
                        SizedBox(
                          height: 28,
                          child: IconButton.filledTonal(
                            onPressed: onAddToCart,
                            iconSize: 18,
                            padding: EdgeInsets.zero,
                            icon: const Icon(Icons.add_shopping_cart_rounded),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    _ArVrBadges(product: product),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ProductImage extends StatelessWidget {
  final Product product;
  const _ProductImage({required this.product});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: Color(product.colorHex).withValues(alpha: 0.15),
      child: Stack(
        children: [
          Center(
            child: Icon(
              _iconForCategory(product.category),
              size: 48,
              color: Color(product.colorHex).withValues(alpha: 0.7),
            ),
          ),
          if (product.arEnabled)
            Positioned(
              top: 4,
              right: 4,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.black87,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: const Text('AR', style: TextStyle(color: Colors.white, fontSize: 8, fontWeight: FontWeight.bold)),
              ),
            ),
        ],
      ),
    );
  }

  IconData _iconForCategory(String category) {
    switch (category) {
      case 'Lighting': return Icons.lightbulb_outline_rounded;
      case 'Fans': return Icons.air_rounded;
      case 'Furniture': return Icons.chair_alt_rounded;
      default: return Icons.inventory_2_rounded;
    }
  }
}

class _ArVrBadges extends StatelessWidget {
  final Product product;
  const _ArVrBadges({required this.product});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          if (product.arEnabled) _badge(context, 'AR', Icons.view_in_ar, Colors.deepOrange),
          if (product.vrEnabled) ...[const SizedBox(width: 4), _badge(context, 'VR', Icons.vrpano, Colors.indigo)],
          if (product.unityEnabled) ...[const SizedBox(width: 4), _badge(context, '3D', Icons.auto_awesome, Colors.teal)],
        ],
      ),
    );
  }

  Widget _badge(BuildContext context, String label, IconData icon, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
      decoration: BoxDecoration(
        border: Border.all(color: color.withValues(alpha: 0.5)),
        borderRadius: BorderRadius.circular(4),
        color: color.withValues(alpha: 0.08),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 8, color: color),
          const SizedBox(width: 2),
          Text(label, style: TextStyle(fontSize: 8, color: color, fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}
