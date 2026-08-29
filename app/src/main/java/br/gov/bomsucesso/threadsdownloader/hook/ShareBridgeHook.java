package br.gov.bomsucesso.threadsdownloader.hook;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entrada Xposed mínima para o fluxo baseado no compartilhamento nativo.
 *
 * Não desenha overlays e não varre a árvore de Views. O download exato é iniciado
 * pelo destino de compartilhamento do Android. Este hook mantém apenas o heartbeat
 * e uma captura leve da última mídia como fallback explícito para posts privados.
 */
public final class ShareBridgeHook implements IXposedHookLoadPackage {
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";
    private static final String MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader";
    private static final String PROVIDER_URI =
            "content://br.gov.bomsucesso.threadsdownloader.settings/current";
    private static final String RECEIVER_CLASS = MODULE_PACKAGE + ".CapturedMediaReceiver";
    private static final String ACTION_CAPTURED = MODULE_PACKAGE + ".MEDIA_CAPTURED";
    private static final String ACTION_HOOK_ACTIVE = MODULE_PACKAGE + ".HOOK_ACTIVE";
    private static final String HOOK_VERSION = "3.4.0-share-bridge";

    private static final AtomicBoolean MEDIA_HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final LinkedHashSet<String> RECENT = new LinkedHashSet<>();
    private static final int MAX_RECENT = 48;

    private static volatile String processName = THREADS_PACKAGE;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!THREADS_PACKAGE.equals(param.packageName)) return;

        processName = param.processName;
        XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed share bridge: pacote carregado em " + param.processName
        );

        hookApplicationAttach();
        if (THREADS_PACKAGE.equals(param.processName)) {
            installMediaFallbackHooks();
        }
    }

    private static void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application application = (Application) param.thisObject;
                            if (!THREADS_PACKAGE.equals(application.getPackageName())) return;

                            sendHeartbeat(application);
                            XposedBridge.log(
                                    "ThreadsEnhancer/ReLSPosed share bridge: Application.attach confirmado"
                            );
                        }
                    }
            );
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed share bridge attach: " + error);
            XposedBridge.log(error);
        }
    }

    private static void installMediaFallbackHooks() {
        if (!MEDIA_HOOKS_INSTALLED.compareAndSet(false, true)) return;

        try {
            XposedBridge.hookAllConstructors(URL.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberMedia(param.thisObject == null ? null : param.thisObject.toString());
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed URL fallback hook: " + error);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Uri.class,
                    "parse",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            rememberMedia((String) param.args[0]);
                        }
                    }
            );
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed Uri fallback hook: " + error);
        }
    }

    private static void rememberMedia(String candidate) {
        if (candidate == null) return;
        String url = candidate.trim();
        if (!isLikelyMedia(url)) return;

        synchronized (RECENT) {
            if (RECENT.remove(url)) {
                // Reinsere ao final para manter a ordem de uso mais recente.
            }
            RECENT.add(url);
            while (RECENT.size() > MAX_RECENT) {
                String first = RECENT.iterator().next();
                RECENT.remove(first);
            }
        }

        Context context = AndroidAppHelper.currentApplication();
        if (context != null) sendCaptured(context, url);
    }

    private static void sendHeartbeat(Context context) {
        Bundle extras = new Bundle();
        extras.putString("framework", "ReLSPosed");
        extras.putString("process", processName);
        extras.putString("hook_version", HOOK_VERSION);

        boolean accepted = callProvider(context, "heartbeat", extras);
        if (!accepted) {
            sendFallbackBroadcast(context, ACTION_HOOK_ACTIVE, null);
        }

        XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed share bridge: heartbeat enviado provider=" + accepted
        );
    }

    private static void sendCaptured(Context context, String url) {
        Bundle extras = new Bundle();
        extras.putString("framework", "ReLSPosed");
        extras.putString("process", processName);
        extras.putString("hook_version", HOOK_VERSION);
        extras.putString("media_type", isVideo(url) ? "video" : "image");
        extras.putString("url", url);

        if (!callProvider(context, "captured", extras)) {
            sendFallbackBroadcast(context, ACTION_CAPTURED, url);
        }
    }

    private static boolean callProvider(Context context, String method, Bundle extras) {
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse(PROVIDER_URI),
                    method,
                    null,
                    extras
            );
            return result != null && result.getBoolean("ok", false);
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed share bridge IPC " + method + ": " + error);
            return false;
        }
    }

    private static void sendFallbackBroadcast(Context context, String action, String url) {
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, RECEIVER_CLASS));
            if (url != null) intent.putExtra("url", url);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed share bridge broadcast: " + error);
        }
    }

    private static boolean isLikelyMedia(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) return false;

        boolean hostMatches = lower.contains("fbcdn.net") ||
                lower.contains("cdninstagram.com") ||
                lower.contains("instagram.com");
        boolean mediaMatches = lower.contains(".mp4") ||
                lower.contains(".m4v") ||
                lower.contains(".jpg") ||
                lower.contains(".jpeg") ||
                lower.contains(".png") ||
                lower.contains(".webp") ||
                lower.contains("/t16/") ||
                lower.contains("/t51.");
        return hostMatches && mediaMatches;
    }

    private static boolean isVideo(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".mp4") ||
                lower.contains(".m4v") ||
                lower.contains("/t16/") ||
                lower.contains("video");
    }
}
