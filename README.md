# GameBot (RedMagic 6 Pro)

On-device game agent for your own game: screen capture (MediaProjection) → GPU neural detector (MediaPipe EfficientDet) → Utility-AI brain with a strategic state machine (scout / engage / regroup / ambush) → real touches through an AccessibilityService. No internet permission. Acts only while the selected game is in the foreground. Red ■ button stops it instantly.

Put a trained detector at `app/src/main/assets/enemy_detector.tflite` (MediaPipe-compatible, classes `enemy` / `head`) and it is used automatically instead of the generic person detector.
