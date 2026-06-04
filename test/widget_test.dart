// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:ar_ecommerce_app/main.dart';

void main() {
  testWidgets('FloorFlow app renders bridge screen', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const FloorFlowApp());

    expect(find.text('FloorFlow AR Commerce'), findsOneWidget);
    expect(find.text('Ping Native'), findsOneWidget);
    expect(find.text('Open AR View'), findsOneWidget);
    expect(find.text('Open Unity Showroom'), findsOneWidget);
  });
}
