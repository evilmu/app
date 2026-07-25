package br.gov.bomsucesso.threadsdownloader.hook;

import android.app.Activity;
import android.app.Application;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Bootstrap mínimo para ReLSPosed.
 *
 * Além do heartbeat, instala um botão flutuante independente da implementação
 * interna do menu do Threads. Isso mantém o download utilizável quando o menu
 * é renderizado por Compose e não expõe TextViews tradicionais.
 */
public final class ActivationHook implements IXposedHookLoadPackage {
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";
    private static final String MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader";
    private static final String PROVIDER_URI =
            "content://br.gov.bomsucesso.threadsdownloader.settings/current";
    private static final String HOOK_VERSION = "3.3.3-floating";
    private static final String FLOATING_TAG = "threads_enhancer_floating_download_333";
    private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!THREADS_PACKAGE.equals(param.packageName)) return;

        XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed bootstrap: pacote carregado em " + param.processName
        );

        hookApplicationAttach();
        startFullHook(param);
    }

    private static void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new AttachCallback()
            );
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed bootstrap attach: " + error);
            XposedBridge.log(error);
        }
    }

    private static final class AttachCallback extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Application application = (Application) param.thisObject;
            if (!THREADS_PACKAGE.equals(application.getPackageName())) return;

            XposedBridge.log("ThreadsEnhancer/ReLSPosed bootstrap: Application.attach confirmado");
            sendHeartbeat(application);
            registerLifecycle(application);
        }
    }

    private static void registerLifecycle(Application application) {
        if (!LIFECYCLE_REGISTERED.compareAndSet(false, true)) return;

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) { }

            @Override
            public void onActivityStarted(Activity activity) { }

            @Override
            public void onActivityResumed(Activity activity) {
                activity.getWindow().getDecorView().postDelayed(
                        () -> installFloatingButton(activity),
                        550L
                );
            }

            @Override
            public void onActivityPaused(Activity activity) { }

            @Override
            public void onActivityStopped(Activity activity) { }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

            @Override
            public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void installFloatingButton(Activity activity) {
        try {
            View contentView = activity.findViewById(android.R.id.content);
            if (!(contentView instanceof FrameLayout)) return;

            FrameLayout content = (FrameLayout) contentView;
            if (content.findViewWithTag(FLOATING_TAG) != null) return;

            TextView button = new TextView(activity);
            button.setTag(FLOATING_TAG);
            button.setText("↓");
            button.setTextSize(30f);
            button.setTextColor(Color.WHITE);
            button.setGravity(Gravity.CENTER);
            button.setContentDescription("Baixar mídia detectada pelo Threads Enhancer");
            button.setClickable(true);
            button.setFocusable(true);
            button.setElevation(dp(activity, 10));

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(Color.rgb(0, 125, 61));
            background.setStroke(dp(activity, 2), Color.WHITE);
            button.setBackground(background);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(activity, 58),
                    dp(activity, 58)
            );
            params.gravity = Gravity.END | Gravity.BOTTOM;
            params.setMargins(dp(activity, 16), dp(activity, 16), dp(activity, 18), dp(activity, 92));

            button.setOnClickListener(view -> downloadLatest(activity));
            button.setOnLongClickListener(view -> {
                Toast.makeText(
                        activity,
                        "Threads Enhancer: toque para baixar a mídia detectada mais recentemente",
                        Toast.LENGTH_LONG
                ).show();
                return true;
            });

            content.addView(button, params);
            reportEvent(activity, "floating_button", "installed");
            XposedBridge.log("ThreadsEnhancer/ReLSPosed: botão flutuante instalado");
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed floating button: " + error);
            XposedBridge.log(error);
            reportEvent(activity, "hook_error", "floating_button: " + error);
        }
    }

    private static void downloadLatest(Context context) {
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse(PROVIDER_URI),
                    "latest",
                    null,
                    null
            );
            String url = result == null ? null : result.getString("url");
            if (url == null || !url.startsWith("https://")) {
                Toast.makeText(
                        context,
                        "Nenhuma mídia detectada. Role até a imagem ou vídeo e tente novamente.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            boolean video = url.toLowerCase().contains(".mp4") ||
                    url.toLowerCase().contains("/video") ||
                    url.toLowerCase().contains("t16/");
            String extension;
            String mime;
            if (video) {
                extension = ".mp4";
                mime = "video/mp4";
            } else if (url.toLowerCase().contains(".webp")) {
                extension = ".webp";
                mime = "image/webp";
            } else {
                extension = ".jpg";
                mime = "image/jpeg";
            }

            String fileName = "threads_" + System.currentTimeMillis() + extension;
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(video ? "Vídeo do Threads" : "Imagem do Threads")
                    .setDescription("Baixando mídia")
                    .setMimeType(mime)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "Threads/" + fileName
                    );

            DownloadManager manager =
                    (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            reportEvent(context, "floating_download", fileName);
            Toast.makeText(context, "Download iniciado", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed floating download: " + error);
            XposedBridge.log(error);
            reportEvent(context, "hook_error", "floating_download: " + error);
            Toast.makeText(context, "Não foi possível iniciar o download", Toast.LENGTH_LONG).show();
        }
    }

    private static void sendHeartbeat(Context context) {
        boolean providerAccepted = false;

        try {
            Bundle extras = new Bundle();
            extras.putString("framework", "ReLSPosed");
            extras.putString("process", context.getApplicationInfo().processName);
            extras.putString("hook_version", HOOK_VERSION);

            Bundle result = context.getContentResolver().call(
                    Uri.parse(PROVIDER_URI),
                    "heartbeat",
                    null,
                    extras
            );
            providerAccepted = result != null && result.getBoolean("ok", false);
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed bootstrap IPC: " + error);
        }

        if (!providerAccepted) {
            try {
                Intent fallback = new Intent(MODULE_PACKAGE + ".HOOK_ACTIVE");
                fallback.setComponent(new ComponentName(
                        MODULE_PACKAGE,
                        MODULE_PACKAGE + ".CapturedMediaReceiver"
                ));
                context.sendBroadcast(fallback);
            } catch (Throwable error) {
                XposedBridge.log("ThreadsEnhancer/ReLSPosed bootstrap broadcast: " + error);
            }
        }

        XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed bootstrap: heartbeat enviado provider="
                        + providerAccepted
        );
    }

    private static void reportEvent(Context context, String event, String details) {
        try {
            Bundle extras = new Bundle();
            extras.putString("event", event);
            extras.putString("details", details == null ? "" : details);
            context.getContentResolver().call(
                    Uri.parse(PROVIDER_URI),
                    "event",
                    null,
                    extras
            );
        } catch (Throwable ignored) {
            // O log do ReLSPosed continua sendo a fonte de diagnóstico de fallback.
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void startFullHook(XC_LoadPackage.LoadPackageParam param) {
        try {
            Class<?> hookClass = Class.forName(
                    "br.gov.bomsucesso.threadsdownloader.hook.ThreadsHook",
                    true,
                    ActivationHook.class.getClassLoader()
            );
            Object hook = hookClass.getDeclaredConstructor().newInstance();
            if (!(hook instanceof IXposedHookLoadPackage)) {
                throw new IllegalStateException("ThreadsHook não implementa IXposedHookLoadPackage");
            }
            ((IXposedHookLoadPackage) hook).handleLoadPackage(param);
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed bootstrap delegate: " + error);
            XposedBridge.log(error);
        }
    }
}
