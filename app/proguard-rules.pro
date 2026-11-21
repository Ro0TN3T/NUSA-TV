# NextLib discovers FFmpeg renderer/decoder implementations through extension classes.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep metadata for reflective decoder discovery without disabling all Media3 shrinking.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
