# Ask Claude's SDK under R8. Robot's release build is not minified today (sherpa-onnx's JNI and LiteRT-LM's native
# callbacks, see app/build.gradle), so these rules are inert; they make sure that turning R8 on later can't break the
# Anthropic SDK, whose request and response models are bound by Jackson through reflection (annotations, generic
# signatures, Kotlin metadata for jackson-module-kotlin).
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
# Unreachable on Robot's path (checked with R8 against android.jar, min API 34): Jackson's optional java.beans support
# (it loads it defensively) and the SDK's class-to-JSON-schema helpers for structured outputs (Robot never calls them).
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
