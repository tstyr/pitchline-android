# ONNX Runtime calls Java methods and constructors from JNI.
# https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# jni.cpp exports Java_com_pitchline_android_NativeDsp_* by name.
-keep class com.pitchline.android.NativeDsp { *; }
