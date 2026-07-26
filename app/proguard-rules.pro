# Classe carregada pelo ReLSPosed a partir de assets/xposed_init.
-keep class br.gov.bomsucesso.threadsdownloader.hook.NativeMenuProbeHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.NativeMenuProbeHook$* { *; }

# DexKit usa JNI e reflexão para resolver os métodos do APK alvo.
-keep class org.luckypray.dexkit.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Mantém a Activity atual enquanto o fluxo antigo é retirado da interface final.
-keep class br.gov.bomsucesso.threadsdownloader.share.ShareDownloadActivity { *; }
-keep class br.gov.bomsucesso.threadsdownloader.share.PostMediaResolver { *; }
-keep class br.gov.bomsucesso.threadsdownloader.share.PostMediaResolver$* { *; }

-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-dontwarn de.robv.android.xposed.**
-dontwarn org.luckypray.dexkit.**
