# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.atomkarnickel.overhead.**$$serializer { *; }
-keepclassmembers class com.atomkarnickel.overhead.** {
    *** Companion;
}
-keepclasseswithmembers class com.atomkarnickel.overhead.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# MapLibre (eigene consumer rules; optionale Abhängigkeiten)
-dontwarn com.google.android.gms.**
-dontwarn org.maplibre.android.location.engine.**
