# ONNX Runtime calls Java methods and constructors from JNI.
# https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# jni.cpp exports Java_com_pitchline_android_NativeDsp_* by name.
-keep class com.pitchline.android.NativeDsp { *; }

# Instrument the shipped optimized APK. Preserve the public boundaries used by
# audio/Compose tests. Shared library APIs must also retain their overridability:
# instrumentation supplies subclasses R8 cannot see while optimizing the app.
-keep,allowoptimization,allowobfuscation class com.pitchline.android.** { public protected *; }
-keep,allowobfuscation class androidx.compose.ui.** { public protected *; }
-keep,allowobfuscation class androidx.compose.runtime.** { public protected *; }
-keep,allowobfuscation class androidx.lifecycle.** { public protected *; }
-keep,allowobfuscation class kotlin.** { public protected *; }
-keep,allowobfuscation class kotlinx.coroutines.** { public protected *; }
-keep,allowobfuscation class androidx.concurrent.futures.** { public protected *; }
-keep,allowobfuscation class androidx.collection.** { public protected *; }
-keep,allowobfuscation interface com.google.common.util.concurrent.ListenableFuture { *; }
# AndroidJUnitRunner references this shared dependency by its original API.
-keep,allowoptimization class androidx.tracing.** { *; }
