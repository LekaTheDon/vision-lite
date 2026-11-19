# kotlinx.serialization keeps generated serializers itself (R8 rules ship with the library).
-keepclasseswithmembernames class * { native <methods>; }

# MPV: native code calls back into these by name.
-keep class is.xyz.mpv.** { *; }
# FFmpeg audio renderer is loaded by reflection (DefaultRenderersFactory extension mode).
-keep class androidx.media3.decoder.ffmpeg.** { *; }
