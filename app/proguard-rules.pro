-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# NextLib creates FFmpeg renderers/decoders through extension discovery and JNI.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
