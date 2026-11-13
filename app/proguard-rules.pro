-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mbk.halatuju.**$$serializer { *; }
-keepclassmembers class com.mbk.halatuju.** {
    *** Companion;
}

-dontwarn org.maplibre.**
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
