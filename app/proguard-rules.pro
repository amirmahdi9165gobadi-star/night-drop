# NightDrop ProGuard / R8 Optimization & Shrinking Rules

# Preserve line numbers for clean crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AccessibilityService, Services, BroadcastReceivers, and Activities
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity
-keepclassmembers class com.example.automation.MacroAccessibilityService {
    *;
}

# Room Database Entities, DAOs, and generated *_Impl classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-keep @androidx.room.Dao class *
-keep class * extends androidx.room.RoomDatabase$Callback
-dontwarn androidx.room.paging.**

# Data models, Domain models, and JSON / Serialization helpers
-keepclassmembers class com.example.data.local.** {
    <fields>;
    <init>(...);
}
-keepclassmembers class com.example.automation.UiNodeSnapshot {
    <fields>;
    <init>(...);
}
-keepclassmembers class com.example.automation.BatteryStatus {
    <fields>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# OkHttp3 & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# DataStore Preferences
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
