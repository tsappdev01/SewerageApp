# kotlinx.serialization: keep generated serializers for the API's JSON classes.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.meterreading.reader.api.**$$serializer { *; }
-keepclassmembers class com.meterreading.reader.api.** { *** Companion; }
-keepclasseswithmembers class com.meterreading.reader.api.** { kotlinx.serialization.KSerializer serializer(...); }

# MSAL (company sign-in) uses reflection and Gson for its configuration and cache.
-keep class com.microsoft.identity.** { *; }
-dontwarn com.microsoft.identity.**
