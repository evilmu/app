package br.gov.bomsucesso.threadsdownloader.hook;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Bootstrap mínimo para ReLSPosed.
 *
 * Mantido no DEX principal para que versões do ReLSPosed que não resolvem
 * corretamente a classe Kotlin em DEX secundário ainda consigam confirmar
 * a ativação do módulo e iniciar o hook completo quando possível.
 */
public final class ActivationHook implements IXposedHookLoadPackage {
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";
    private static final String MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader";
    private static final String PROVIDER_URI =
            "content://br.gov.bomsucesso.threadsdownloader.settings/current";
    private static final String HOOK_VERSION = "3.3.1-bootstrap";

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
