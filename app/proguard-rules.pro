-keepattributes *Annotation*

# Room entities/DAOs/TypeConverters — accessed reflectively for migrations and field mapping
-keep class com.stashapp.data.** { *; }

# WorkManager instantiates workers by class name via reflection
-keep class com.stashapp.sync.SyncWorker { *; }

# ZXing — core barcode engine
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# OkHttp and okio ship their own consumer rules; suppress residual warnings
-dontwarn okhttp3.**
-dontwarn okio.**
