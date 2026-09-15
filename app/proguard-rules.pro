# ————— Reflection keeps —————
#
# F3/#86: this used to keep the whole first-party package
# (`-keep class com.holopengin.instantjpdict.** { *; }`) plus a rule for
# ai.onnxruntime, a dependency removed with the LiteRT/ORT cutover. The
# catch-all made R8's minification a no-op for the app's own code; the rules
# below are the classes that are actually reached reflectively.
#
# JNI entry points (RecNcnn/DetNcnn/KanaSizeNcnn) need no rule of their own:
# proguard-android-optimize.txt keeps every class that declares a native method
# (`-keepclasseswithmembernames,includedescriptorclasses`), which is what the
# `Java_com_holopengin_...` symbols resolve against.

# Gson: deinflect.json is mapped onto [com.holopengin.instantjpdict.util.DeinflectionRule]
# by field name (util/Deinflector.kt). Field names and the class name must survive.
-keep class com.holopengin.instantjpdict.util.DeinflectionRule { *; }

# Gson / Room: the entities and DAO payloads are mapped and instantiated by name.
-keep class com.holopengin.instantjpdict.data.** { *; }

# The OCR line payload: the JNI-adjacent result type the overlay and share
# activity pass around; kept whole so its fields stay readable to Gson/reflection
# paths without re-listing each one here.
-keep class com.holopengin.instantjpdict.LineResult { *; }

# Keep UniFFI generated classes
-keep class uniffi.nav_graph_core.** { *; }

# JNA Rules
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** {
    public *;
}

# Added to fix R8 missing classes warnings
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
