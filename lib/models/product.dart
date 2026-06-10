class Product {
  final String id;
  final String name;
  final String category;
  final double price;
  final String description;
  final String specs;
  final String roomFit;
  final int colorHex;
  final double rating;
  final int reviewCount;
  final bool arEnabled;
  final bool vrEnabled;
  final bool unityEnabled;

  const Product({
    required this.id,
    required this.name,
    required this.category,
    required this.price,
    required this.description,
    required this.specs,
    required this.roomFit,
    required this.colorHex,
    this.rating = 4.5,
    this.reviewCount = 0,
    this.arEnabled = true,
    this.vrEnabled = true,
    this.unityEnabled = true,
  });

  factory Product.fromJson(Map<String, dynamic> json) {
    return Product(
      id: json['id'] as String,
      name: json['name'] as String,
      category: json['category'] as String,
      price: (json['price'] as num).toDouble(),
      description: json['description'] as String,
      specs: json['specs'] as String,
      roomFit: json['roomFit'] as String,
      colorHex: json['colorHex'] as int,
      rating: (json['rating'] as num?)?.toDouble() ?? 4.5,
      reviewCount: json['reviewCount'] as int? ?? 0,
      arEnabled: json['arEnabled'] as bool? ?? true,
      vrEnabled: json['vrEnabled'] as bool? ?? true,
      unityEnabled: json['unityEnabled'] as bool? ?? true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'category': category,
      'price': price,
      'description': description,
      'specs': specs,
      'roomFit': roomFit,
      'colorHex': colorHex,
      'rating': rating,
      'reviewCount': reviewCount,
      'arEnabled': arEnabled,
      'vrEnabled': vrEnabled,
      'unityEnabled': unityEnabled,
    };
  }
}
