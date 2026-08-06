# Add project specific ProGuard rules here.
# Minification is currently disabled (see app/build.gradle.kts); these rules are
# in place ready for when release shrinking is turned on.

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.apexlions.film2.player.**$$serializer { *; }
-keepclassmembers class com.apexlions.film2.player.** { *** Companion; }
-keepclasseswithmembers class com.apexlions.film2.player.** { kotlinx.serialization.KSerializer serializer(...); }

# Media3 / ExoPlayer
-dontwarn com.google.android.exoplayer2.**
