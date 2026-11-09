# Keep Retrofit2 & Gson API DTO models
-keep class com.awaken.sightseeingtravel.core.model.** { *; }
-keepclassmembers class com.awaken.sightseeingtravel.core.model.** { *; }

# Retrofit2 generic signatures
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# MapLibre Native SDK
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
