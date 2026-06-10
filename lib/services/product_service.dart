import '../models/product.dart';

class ProductService {
  // Simulate a CMS or API delay
  Future<List<Product>> getProducts() async {
    await Future.delayed(const Duration(milliseconds: 400));
    return _mockCatalog;
  }

  Future<Product?> getProductById(String id) async {
    await Future.delayed(const Duration(milliseconds: 200));
    try {
      return _mockCatalog.firstWhere((p) => p.id == id);
    } catch (_) {
      return null;
    }
  }

  static final List<Product> _mockCatalog = [
    const Product(
      id: 'lumen-floor-lamp',
      name: 'Lumen Floor Lamp',
      category: 'Lighting',
      price: 12499,
      description: 'Modern arc floor lamp with warm LED, perfect for living rooms and reading nooks.',
      specs: 'Warm LED 2700K, dimmable, matte black finish, 180cm height',
      roomFit: 'Fits rooms from 10ft × 12ft',
      colorHex: 0xFFE67E22,
      rating: 4.7,
      reviewCount: 248,
    ),
    const Product(
      id: 'zephyr-ceiling-fan',
      name: 'Zephyr Ceiling Fan',
      category: 'Fans',
      price: 8999,
      description: 'High-efficiency BLDC ceiling fan with remote control and sleep timer.',
      specs: '1200mm sweep, BLDC motor, 5 speed, remote included',
      roomFit: 'Ideal for rooms up to 14ft × 16ft',
      colorHex: 0xFF2980B9,
      rating: 4.5,
      reviewCount: 412,
    ),
    const Product(
      id: 'aurora-table-lamp',
      name: 'Aurora Table Lamp',
      category: 'Lighting',
      price: 4299,
      description: 'Touch-sensitive table lamp with three brightness levels and USB charging port.',
      specs: 'Touch control, 3 brightness, USB-C port, linen shade',
      roomFit: 'Perfect for bedside or study desk',
      colorHex: 0xFF8E44AD,
      rating: 4.6,
      reviewCount: 189,
    ),
    const Product(
      id: 'nexus-smart-sofa',
      name: 'Nexus Smart Sofa',
      category: 'Furniture',
      price: 54999,
      description: 'L-shaped sectional with built-in USB ports, cup holders and recliner seats.',
      specs: 'L-shape, 4-seater, fabric upholstery, 2 recliners, USB ports',
      roomFit: 'Fits living rooms 15ft × 18ft or larger',
      colorHex: 0xFF27AE60,
      rating: 4.4,
      reviewCount: 93,
    ),
    const Product(
      id: 'orbit-pendant-light',
      name: 'Orbit Pendant Light',
      category: 'Lighting',
      price: 6799,
      description: 'Industrial-style pendant cluster with Edison bulbs for dining and kitchen islands.',
      specs: '3-bulb cluster, E27 socket, adjustable cord 30–150cm, matte iron',
      roomFit: 'Best over dining table or kitchen island',
      colorHex: 0xFFE74C3C,
      rating: 4.8,
      reviewCount: 334,
    ),
    const Product(
      id: 'vega-study-chair',
      name: 'Vega Study Chair',
      category: 'Furniture',
      price: 9499,
      description: 'Ergonomic mesh back chair with lumbar support, armrests, and height adjustment.',
      specs: 'Mesh back, lumbar support, adjustable armrests, 5-wheel base',
      roomFit: 'For home office or study room',
      colorHex: 0xFF16A085,
      rating: 4.3,
      reviewCount: 567,
    ),
    const Product(
      id: 'solaris-floor-fan',
      name: 'Solaris Tower Fan',
      category: 'Fans',
      price: 5299,
      description: 'Slim tower fan with oscillation, ionizer, and app-based control.',
      specs: '120cm height, 360° oscillation, ionizer, WiFi + app control',
      roomFit: 'Any room up to 12ft × 14ft',
      colorHex: 0xFFF39C12,
      rating: 4.2,
      reviewCount: 221,
    ),
    const Product(
      id: 'luxe-bookshelf',
      name: 'Luxe Bookshelf',
      category: 'Furniture',
      price: 18999,
      description: 'Solid wood 6-shelf bookcase with integrated LED strip lighting on each tier.',
      specs: 'Solid sheesham wood, 6 shelves, integrated LED strip, 180cm tall',
      roomFit: 'Living room or study — needs 30cm wall clearance',
      colorHex: 0xFF795548,
      rating: 4.6,
      reviewCount: 145,
    ),
  ];
}
