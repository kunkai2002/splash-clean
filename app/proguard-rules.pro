# ONNX Runtime uses JNI and reflection
-keep class ai.onnxruntime.** { *; }
# Conscrypt / Bouncy Castle / libadb (TLS pairing, reflection on org.conscrypt.Conscrypt)
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
# exp4j (position expressions)
-keep class net.objecthunter.exp4j.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.github.kunkai2002.splashclean.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class io.github.kunkai2002.splashclean.** { *** Companion; }
