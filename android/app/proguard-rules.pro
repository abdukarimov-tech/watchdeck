# Pulse Android R8 rules.
# protobuf-lite и okhttp поставляют consumer rules сами; добавляем страховку
# для сгенерированного протокола и JNI-мостов Firebase.

-keep class pulse.v1.** { *; }
-keep class com.google.protobuf.** { *; }
# BouncyCastle нужен sshj целиком: провайдер грузится рефлексией по имени класса,
# а регистрации JCE-алгоритмов завязаны на имена/ресурсы, которые R8 не сохраняет
-keep class org.bouncycastle.** { *; }
# EdDSA-Java (зависимость sshj) ссылается на JDK-класс, которого нет на Android —
# код не вызывается, R8 достаточно не ругаться
-dontwarn sun.security.x509.X509Key
-dontwarn com.google.protobuf.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
