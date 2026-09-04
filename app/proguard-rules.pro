# ArmorChat — keep Hilt / Compose entry points if release minification is enabled later.
-keepattributes RuntimeVisibleAnnotations
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
