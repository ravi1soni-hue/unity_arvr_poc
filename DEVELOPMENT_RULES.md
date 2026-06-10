# AI Development Rules & Project Context

This document serves as the source of truth for the AI assistant to ensure consistency, prevent loops, and maintain project integrity.

## Core Operational Principles
1.  **Do Only What is Asked:** Never implement features or make changes beyond the scope of the current request.
2.  **Verify Code Flow:** Always read surrounding files and dependencies before implementation. Read the resulting code after implementation to verify correctness.
3.  **Prevent Loops:** Never repeat the same sequence of edits (Redo/Undo). If a "version solving" or "compilation" error persists, stop and analyze the root cause instead of trying the same fix again.
4.  **Consistency First:** Match the existing code style, naming conventions, and architectural patterns of the project.

## Project Environment
-   **Dart SDK Version:** `^3.8.1` (Constraint in `pubspec.yaml` set to `'>=3.0.0 <4.0.0'` for maximum compatibility).
-   **Flutter Lints:** Pinned to `^3.0.0` to avoid SDK version conflicts.
-   **State Management:** `Provider` (using `CartProvider`).
-   **Navigation:** `GoRouter` (declarative routing).

## Technical Patterns
-   **Native Bridge:**
    -   Channel Name: `com.example.ar_ecommerce/native`.
    -   Implementation: Always check `if (!mounted)` before calling `setState` or navigating after an `await` from a native bridge call.
-   **AR/VR Integration:**
    -   AR uses native ARCore Activities.
    -   VR uses a custom OpenGL 360 sphere renderer.
    -   Unity uses a reflection-based Activity (`UnityActivity.kt`) to allow the project to compile without the Unity library present.
-   **Form Handling:**
    -   Use standard Material widgets.
    -   Use `RadioListTile` for selection groups in forms (avoid creating undefined custom `RadioGroup` widgets).

## Checklist Before Writing
- [ ] Have I read the target file recently?
- [ ] Have I checked if the suggested package version is compatible with Dart `3.8.1`?
- [ ] Am I about to repeat a command I just ran? (If yes, STOP).
