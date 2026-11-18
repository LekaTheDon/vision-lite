# kotlinx.serialization keeps generated serializers itself (R8 rules ship with the library).
-keepclasseswithmembernames class * { native <methods>; }
