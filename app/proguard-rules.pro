# Xeno Live — R8 / ProGuard keep rules for the optimized release build.

# Keep line numbers for readable crash traces; hide original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault, *Annotation*

# ---- Kotlin / Coroutines -------------------------------------------------
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Moshi (reflection + @JsonClass codegen adapters) --------------------
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-keepnames @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-dontwarn com.squareup.moshi.**
# Our Gemini Live wire DTOs are (de)serialized by Moshi — keep their shape.
-keep class com.example.live.** { *; }

# ---- OkHttp / Okio / Retrofit -------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ---- SceneView / Filament (the 3D Nazim renderer) -----------------------
-keep class io.github.sceneview.** { *; }
-keep class com.google.android.filament.** { *; }
-keep class com.google.ar.** { *; }
-dontwarn io.github.sceneview.**
-dontwarn com.google.android.filament.**
-dontwarn com.google.ar.**

# ---- Room ----------------------------------------------------------------
-dontwarn androidx.room.**

# ---- CameraX -------------------------------------------------------------
-dontwarn androidx.camera.**

# ---- Framework-instantiated components (declared in the manifest) --------
-keep class com.example.MainActivity { *; }
-keep class com.example.accessibility.AgentAccessibilityService { *; }
-keep class com.example.voice.VoiceService { *; }
-keep class com.example.vision.ScreenCaptureService { *; }
