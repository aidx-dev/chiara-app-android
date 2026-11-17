# kotlinx.serialization: keep generated serializers of the models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.chiarasystems.chiara.** { *** Companion; }
-keepclasseswithmembers class com.chiarasystems.chiara.** { kotlinx.serialization.KSerializer serializer(...); }

# MediaPipe LLM Inference (JNI + protobuf-lite): keep what native code reaches by name.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.auto.value.**
-dontwarn com.google.protobuf.**
-dontwarn javax.lang.model.**
# OkHttp / Okio optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.mediapipe.framework.image.**
