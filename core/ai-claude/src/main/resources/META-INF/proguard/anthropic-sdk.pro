# R8 rules for apps that use :core:ai-claude (direct AI mode of the GitHub build, tech plan §17.2, spike S8).
# Shipped inside the jar under META-INF/proguard/, so R8 applies them to any app that depends on the module.
#
# They complement the rules bundled with anthropic-java-core (META-INF/proguard/anthropic-java-core.pro) and are
# deliberately conservative: the SDK (de)serializes its request and response models reflectively with Jackson, and
# a member R8 strips only fails at runtime, on the user's phone. Only the SDK packages the runner touches are kept
# whole (the full SDK is ~14k classes); spike S8 measures the APK cost and may tighten these rules with a release
# build test of the direct mode.

# Generic signatures, inner classes and annotations drive Jackson's type resolution.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault

# --- Anthropic SDK --------------------------------------------------------------------------------------------
# Client, HTTP layer, JSON helpers (JsonValue, JsonField, ObjectMappers) and typed errors.
-keep class com.anthropic.core.** { *; }
-keep class com.anthropic.client.** { *; }
-keep class com.anthropic.errors.** { *; }
# Beta Messages API: params, response, content-block unions, usage, stop details, output config, fallbacks.
-keep class com.anthropic.models.beta.messages.** { *; }
-keep class com.anthropic.models.beta.AnthropicBeta { *; }
-keep class com.anthropic.models.beta.AnthropicBeta$** { *; }
-keep class com.anthropic.models.messages.Model { *; }
-keep class com.anthropic.models.messages.Model$** { *; }
-keep class com.anthropic.models.ErrorType { *; }
-keep class com.anthropic.models.ErrorType$** { *; }
-keep class com.anthropic.models.ErrorObject { *; }
-keep class com.anthropic.models.ErrorObject$** { *; }
-keep class com.anthropic.models.ErrorResponse { *; }
-keep class com.anthropic.models.ErrorResponse$** { *; }
# Every other SDK model that survives shrinking keeps its Jackson creators and accessors.
-keepclassmembers class com.anthropic.models.** {
    <init>(...);
    <fields>;
    public *** _*();
}

# --- Jackson (databind, JDK 8 / JSR-310 modules, Kotlin module) ------------------------------------------------
-keep class com.fasterxml.jackson.core.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.datatype.jdk8.** { *; }
-keep class com.fasterxml.jackson.datatype.jsr310.** { *; }
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }

# jackson-module-kotlin reads Kotlin metadata through kotlin-reflect.
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }

# --- JVM-only or optional classes referenced by Jackson and the SDK, absent on Android -------------------------
-dontwarn java.beans.**
-dontwarn javax.xml.**
-dontwarn javax.annotation.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn org.slf4j.**
-dontwarn com.github.victools.jsonschema.**
-dontwarn io.swagger.v3.oas.annotations.**
-dontwarn com.standardwebhooks.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Structured outputs derived from Java classes reflect on annotated types (JDK only). The runner passes JSON schemas
# from the AI contract instead, so this path never runs on a phone.
-dontwarn java.lang.reflect.AnnotatedType
