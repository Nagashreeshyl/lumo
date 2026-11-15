# Coroutines
-keepclassmembers class * { void resume(...); void resumeWithException(...); }

# kotlinx.serialization (models are decoded by reflection-free generated serializers; keep their companions)
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> { static <1>$Companion Companion; }
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclasseswithmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class ** { public static ** INSTANCE; }
-keepclasseswithmembers class <1> { public static <1> INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep class com.music.innertube.models.** { *; }

# NewPipe extractor (stream fallback) and its JavaScript engine
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.servlet.ServletContainerInitializer
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-keepclassmembers class * implements java.io.Serializable {
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern
