# Entrada carregada pelo ReLSPosed a partir de assets/xposed_init.
-keep class br.gov.bomsucesso.threadsdownloader.hook.NativePostMenuHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.NativePostMenuHook$* { *; }

# Classes do aplicativo ainda usadas pela interface principal.
-keep class br.gov.bomsucesso.threadsdownloader.SettingsProvider { *; }
-keep class br.gov.bomsucesso.threadsdownloader.CapturedMediaReceiver { *; }

-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-dontwarn de.robv.android.xposed.**
