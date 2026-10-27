# Pulse Android R8 rules.
# protobuf-lite и okhttp поставляют consumer rules сами; добавляем страховку
# для сгенерированного протокола и JNI-мостов Firebase.

-keep class pulse.v1.** { *; }
-keep class com.google.protobuf.** { *; }
# BouncyCastle нужен sshj целиком: провайдер грузится рефлексией по имени класса,
# а регистрации JCE-алгоритмов завязаны на имена/ресурсы, которые R8 не сохраняет
-keep class org.bouncycastle.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
