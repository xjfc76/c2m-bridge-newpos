# CouchToMouth Bridge App ProGuard Rules

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep GSON serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.couchtommouth.bridge.printer.ReceiptData { *; }
-keep class com.couchtommouth.bridge.printer.ReceiptItem { *; }
-keep class com.couchtommouth.bridge.printer.ReceiptModifier { *; }

# Keep JavaScript interface methods
-keepclassmembers class com.couchtommouth.bridge.ui.MainActivity$POSBridge {
    public *;
}

# SumUp SDK (when added)
# -keep class com.sumup.** { *; }

# Zettle SDK (when added)
# -keep class com.izettle.** { *; }
