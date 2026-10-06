# R8 rules for apps that use :core:ai.
# Tink, Play Integrity, WorkManager, Ktor and kotlinx.serialization ship their own consumer rules; the Anthropic SDK
# rules come with :core:ai-claude (META-INF/proguard/anthropic-sdk.pro).

# Ktor's OkHttp engine is found through ServiceLoader on some code paths.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-dontwarn org.slf4j.**
