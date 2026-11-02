# The JNI entry points are looked up by name from Rust.
-keep class ai.storyteller.lightcraft.android.Engine { native <methods>; }

# ONNX Runtime reaches its Java classes from native code; the engine calls LamaPainter.paint through JNI
-keep class ai.onnxruntime.** { *; }
-keep class ai.storyteller.lightcraft.android.LamaPainter { public byte[] paint(byte[], byte[]); }
