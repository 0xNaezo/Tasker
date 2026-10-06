# R8 rules for apps that use :core:ai.
# Tink, Play Integrity, WorkManager, Ktor and kotlinx.serialization ship their own consumer rules. :core:ai-openrouter
# needs none: its answer classes are read through their generated serializers, never by reflection.

# Ktor's OkHttp engine is found through ServiceLoader on some code paths.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-dontwarn org.slf4j.**
