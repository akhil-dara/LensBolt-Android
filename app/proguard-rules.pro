# LensBolt release (R8) rules. The app uses no reflection, serialization or dynamic class loading; the only
# entry points R8 cannot see are the JNI ones.

# The Rust library (android/native/src/lib.rs) exports Java_com_lensbolt_app_Native_<method>: the class name,
# the method names and their descriptors must stay exactly as written, and the object must not be merged,
# inlined or have its static initializer (System.loadLibrary) moved.
-keep class com.lensbolt.app.Native { *; }

# Any other native method anywhere (none today): keep it and the types in its signature.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The native code calls nothing back in Java (no FindClass / GetMethodID), so no further keeps are needed.
# If that changes, keep every class and member the Rust side looks up by name here.
