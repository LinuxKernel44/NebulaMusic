-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.nebulamusic.app.**$$serializer { *; }
-keepclassmembers class com.nebulamusic.app.** { *** Companion; }
-keepclasseswithmembers class com.nebulamusic.app.** { kotlinx.serialization.KSerializer serializer(...); }

# WorkManager builds these by reflection; R8 otherwise strips the constructors and every download fails silently.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
