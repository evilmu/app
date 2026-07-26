# Classes carregadas pelo ReLSPosed a partir de assets/xposed_init ou por reflexão.
-keep class br.gov.bomsucesso.threadsdownloader.hook.GeometryActivationHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.GeometryActivationHook$* { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.InlineActivationHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.InlineActivationHook$* { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.ActivationHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.ActivationHook$* { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.ThreadsHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.ThreadsHook$* { *; }

-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-dontwarn de.robv.android.xposed.**
