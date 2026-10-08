# kotlinx.serialization: keep generated serializers for the API's JSON classes.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.meterreading.reader.api.**$$serializer { *; }
-keepclassmembers class com.meterreading.reader.api.** { *** Companion; }
-keepclasseswithmembers class com.meterreading.reader.api.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor and okio name classes that are not on Android (logging back ends, JVM management); not used by the app.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
