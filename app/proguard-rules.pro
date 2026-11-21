# ONNX Runtime calls Java methods and constructors from JNI.
# https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# jni.cpp exports Java_com_pitchline_android_NativeDsp_* by name.
-keep class com.pitchline.android.NativeDsp { *; }

# Instrument the shipped optimized APK. Preserve the public boundaries used by
# audio/Compose tests; implementation bodies can still be optimized and renamed.
-keep,allowoptimization,allowobfuscation class com.pitchline.android.** { public protected *; }
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.** { public protected *; }
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.** { public protected *; }
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.** { public protected *; }
-keep,allowoptimization,allowobfuscation class kotlin.** { public protected *; }
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.** { public protected *; }
# AndroidJUnitRunner references this shared dependency by its original API.
-keep,allowoptimization class androidx.tracing.** { *; }
