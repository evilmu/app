# Classes carregadas pelo ReLSPosed a partir de assets/xposed_init.
-keep class br.gov.bomsucesso.threadsdownloader.hook.ShareBridgeHook { *; }
-keep class br.gov.bomsucesso.threadsdownloader.hook.ShareBridgeHook$* { *; }

# Activity exportada como destino de compartilhamento.
-keep class br.gov.bomsucesso.threadsdownloader.share.ShareDownloadActivity { *; }
-keep class br.gov.bomsucesso.threadsdownloader.share.PostMediaResolver { *; }
-keep class br.gov.bomsucesso.threadsdownloader.share.PostMediaResolver$* { *; }

-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-dontwarn de.robv.android.xposed.**
