# kotlinx.serialization: keep generated serializers of the models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.chiarasystems.chiara.** { *** Companion; }
-keepclasseswithmembers class com.chiarasystems.chiara.** { kotlinx.serialization.KSerializer serializer(...); }
