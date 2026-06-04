import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import '../providers/cart_provider.dart';

class CheckoutScreen extends StatefulWidget {
  const CheckoutScreen({super.key});

  @override
  State<CheckoutScreen> createState() => _CheckoutScreenState();
}

class _CheckoutScreenState extends State<CheckoutScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final _addressCtrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _pincodeCtrl = TextEditingController();
  String _paymentMethod = 'UPI';
  bool _placing = false;

  final List<String> _paymentOptions = ['UPI', 'Credit / Debit Card', 'Net Banking', 'Cash on Delivery'];

  @override
  void dispose() {
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    _addressCtrl.dispose();
    _cityCtrl.dispose();
    _pincodeCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cart = context.watch<CartProvider>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Checkout'),
        backgroundColor: Theme.of(context).colorScheme.primaryContainer,
      ),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _SectionHeader('Delivery Address'),
            const SizedBox(height: 10),
            _field(_nameCtrl, 'Full Name', Icons.person_outline),
            const SizedBox(height: 10),
            _field(_phoneCtrl, 'Phone Number', Icons.phone_outlined, keyboardType: TextInputType.phone),
            const SizedBox(height: 10),
            _field(_addressCtrl, 'Address', Icons.home_outlined, maxLines: 2),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(child: _field(_cityCtrl, 'City', Icons.location_city_outlined)),
                const SizedBox(width: 10),
                Expanded(child: _field(_pincodeCtrl, 'Pincode', Icons.pin_drop_outlined, keyboardType: TextInputType.number)),
              ],
            ),
            const SizedBox(height: 20),
            _SectionHeader('Payment Method'),
            const SizedBox(height: 10),
            RadioGroup<String>(
              groupValue: _paymentMethod,
              onChanged: (v) => setState(() => _paymentMethod = v!),
              child: Column(
                children: _paymentOptions.map((opt) => RadioListTile<String>(
                  title: Text(opt),
                  value: opt,
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                )).toList(),
              ),
            ),
            const SizedBox(height: 20),
            _SectionHeader('Order Summary'),
            const SizedBox(height: 10),
            ...cart.items.map((item) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Expanded(child: Text('${item.product.name} × ${item.quantity}')),
                  Text('₹${item.totalPrice.toStringAsFixed(0)}',
                      style: const TextStyle(fontWeight: FontWeight.w500)),
                ],
              ),
            )),
            const Divider(height: 20),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('Delivery', style: TextStyle(color: Colors.grey)),
                const Text('FREE', style: TextStyle(color: Colors.green, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 4),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Total', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Text(
                  '₹${cart.totalAmount.toStringAsFixed(0)}',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                icon: _placing
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Icon(Icons.check_circle_outline_rounded),
                label: Text(_placing ? 'Placing Order…' : 'Place Order'),
                onPressed: _placing ? null : () => _placeOrder(context, cart),
              ),
            ),
            const SizedBox(height: 30),
          ],
        ),
      ),
    );
  }

  Widget _field(
    TextEditingController ctrl,
    String label,
    IconData icon, {
    int maxLines = 1,
    TextInputType keyboardType = TextInputType.text,
  }) {
    return TextFormField(
      controller: ctrl,
      maxLines: maxLines,
      keyboardType: keyboardType,
      decoration: InputDecoration(
        labelText: label,
        prefixIcon: Icon(icon),
        border: const OutlineInputBorder(),
        isDense: true,
      ),
      validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
    );
  }

  Future<void> _placeOrder(BuildContext context, CartProvider cart) async {
    if (!_formKey.currentState!.validate()) return;
    final router = GoRouter.of(context);
    setState(() => _placing = true);
    await Future.delayed(const Duration(seconds: 2));
    if (!mounted) return;
    cart.clearCart();
    router.go('/order-success');
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  const _SectionHeader(this.title);

  @override
  Widget build(BuildContext context) {
    return Text(
      title,
      style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
    );
  }
}
