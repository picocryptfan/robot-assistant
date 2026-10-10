# Ask Claude's SDK under R8. Robot's release build is not minified today (sherpa-onnx's JNI and LiteRT-LM's native
# callbacks, see app/build.gradle), so these rules are inert until it is. They keep only what Jackson binds by
# reflection, not the whole SDK: annotated members, the models' serializers and deserializers, type references,
# generic signatures and the Kotlin metadata jackson-module-kotlin reads. A minified build still needs an on-device
# test that replays every kind of stream event through the SDK before it ships.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepclassmembers class com.anthropic.** {
    @com.fasterxml.jackson.annotation.JsonCreator *;
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonAnySetter *;
    @com.fasterxml.jackson.annotation.JsonAnyGetter *;
    @com.fasterxml.jackson.annotation.JsonValue *;
}
-keep class com.anthropic.**$Deserializer { <init>(); }
-keep class com.anthropic.**$Serializer { <init>(); }
-keep class * extends com.fasterxml.jackson.core.type.TypeReference
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
# Unreachable on Robot's path (checked against android.jar, min API 34): Jackson's optional java.beans support (loaded
# defensively), the SDK's class-to-JSON-schema helpers for structured outputs (never called), kotlinx-coroutines' debug
# agent, and OkHttp's probes for other TLS providers.
-dontwarn java.beans.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.Signal*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
