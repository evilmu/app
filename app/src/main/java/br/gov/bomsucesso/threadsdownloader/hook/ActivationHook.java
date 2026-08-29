package br.gov.bomsucesso.threadsdownloader.hook;

import android.app.Activity;
import android.app.Application;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Bootstrap mínimo para ReLSPosed.
 *
 * O botão de download é ancorado à barra de ações da publicação por meio da
 * árvore de acessibilidade do Threads. Assim ele acompanha o botão Compartilhar,
 * usa uma aparência monocromática e desaparece em telas que não possuem uma
 * barra de ações de publicação válida.
 */
public final class ActivationHook implements IXposedHookLoadPackage {
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";
    private static final String MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader";
    private static final String PROVIDER_URI =
            "content://br.gov.bomsucesso.threadsdownloader.settings/current";
    private static final String HOOK_VERSION = "3.3.4-inline";
    private static final String INLINE_TAG = "threads_enhancer_inline_download_334";
    private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);

    private static final Runnable INLINE_SYNC = new Runnable() {
        @Override
        public void run() {
            Activity activity = resumedActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

            updateInlineButton(activity);
            MAIN_HANDLER.postDelayed(this, 850L);
        }
    };

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
                resumedActivity = new WeakReference<>(activity);
                MAIN_HANDLER.removeCallbacks(INLINE_SYNC);
                MAIN_HANDLER.postDelayed(INLINE_SYNC, 250L);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumedActivity.get() == activity) {
                    resumedActivity.clear();
                    MAIN_HANDLER.removeCallbacks(INLINE_SYNC);
                }
                removeInlineButton(activity);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                removeInlineButton(activity);
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

            @Override
            public void onActivityDestroyed(Activity activity) {
                removeInlineButton(activity);
            }
        });
    }

    private static void updateInlineButton(Activity activity) {
        try {
            FrameLayout content = findContentFrame(activity);
            if (content == null) return;

            Rect shareBounds = findShareActionBounds(activity);
            if (shareBounds == null) {
                removeInlineButton(content);
                return;
            }

            int size = dp(activity, 40);
            int gap = dp(activity, 2);
            int edge = dp(activity, 6);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;

            int left = shareBounds.right + gap;
            if (left + size > screenWidth - edge) {
                left = shareBounds.left - gap - size;
            }
            int top = shareBounds.centerY() - size / 2;
            left = clamp(left, edge, Math.max(edge, screenWidth - size - edge));
            top = clamp(top, edge, Math.max(edge, screenHeight - size - edge));

            int[] contentLocation = new int[2];
            content.getLocationOnScreen(contentLocation);
            left -= contentLocation[0];
            top -= contentLocation[1];

            View existing = content.findViewWithTag(INLINE_TAG);
            ImageButton button;
            boolean created = false;
            if (existing instanceof ImageButton) {
                button = (ImageButton) existing;
            } else {
                removeInlineButton(content);
                button = createInlineButton(activity);
                created = true;
            }

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = left;
            params.topMargin = top;

            if (created) {
                content.addView(button, params);
                reportEvent(activity, "inline_button", "anchored_to_share");
                XposedBridge.log("ThreadsEnhancer/ReLSPosed: botão inline ancorado ao Compartilhar");
            } else {
                button.setLayoutParams(params);
                button.setVisibility(View.VISIBLE);
            }
            button.bringToFront();
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed inline button: " + error);
            XposedBridge.log(error);
            reportEvent(activity, "hook_error", "inline_button: " + error);
        }
    }

    private static ImageButton createInlineButton(Activity activity) {
        int iconColor = resolvePrimaryTextColor(activity);
        int rippleColor = Color.argb(
                38,
                Color.red(iconColor),
                Color.green(iconColor),
                Color.blue(iconColor)
        );

        ImageButton button = new ImageButton(activity);
        button.setTag(INLINE_TAG);
        button.setContentDescription("Baixar mídia");
        button.setImageDrawable(new DownloadIconDrawable(iconColor));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(0f);
        button.setAlpha(0.98f);

        ShapeDrawable rippleMask = new ShapeDrawable(new OvalShape());
        rippleMask.getPaint().setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                null,
                rippleMask
        ));

        button.setOnClickListener(view -> downloadLatest(activity));
        button.setOnLongClickListener(view -> {
            Toast.makeText(
                    activity,
                    "Baixar a mídia detectada nesta publicação",
                    Toast.LENGTH_SHORT
            ).show();
            return true;
        });
        return button;
    }

    private static Rect findShareActionBounds(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        List<NodeRecord> records = new ArrayList<>();
        int[] visited = new int[]{0};

        AccessibilityNodeInfo root = null;
        try {
            root = decor.createAccessibilityNodeInfo();
            collectAccessibilityNodes(root, records, 0, visited);
        } catch (Throwable ignored) {
            // Algumas builds do Threads não expõem todos os nós pelo host principal.
        } finally {
            recycleQuietly(root);
        }

        if (!containsShareRecord(records)) {
            visited[0] = 0;
            collectFromViewTree(decor, records, 0, visited);
        }

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int verticalTolerance = dp(activity, 42);
        NodeRecord best = null;
        int bestScore = Integer.MIN_VALUE;

        for (NodeRecord candidate : records) {
            if (!isShareLabel(candidate.label)) continue;
            if (!isUsefulBounds(candidate.bounds, screenWidth, screenHeight)) continue;

            int companions = 0;
            int closestDistance = Integer.MAX_VALUE;
            for (NodeRecord other : records) {
                if (other == candidate || !isCompanionActionLabel(other.label)) continue;
                if (!isUsefulBounds(other.bounds, screenWidth, screenHeight)) continue;

                int verticalDistance = Math.abs(other.bounds.centerY() - candidate.bounds.centerY());
                int horizontalDistance = Math.abs(other.bounds.centerX() - candidate.bounds.centerX());
                if (verticalDistance <= verticalTolerance && horizontalDistance <= screenWidth * 0.62f) {
                    companions++;
                    closestDistance = Math.min(closestDistance, horizontalDistance);
                }
            }

            if (companions < 2) continue;

            int centerPenalty = Math.abs(candidate.bounds.centerY() - screenHeight / 2) / 8;
            int edgePenalty = candidate.bounds.centerX() > screenWidth * 0.92f ? 30 : 0;
            int score = companions * 120 - centerPenalty - edgePenalty;
            if (closestDistance != Integer.MAX_VALUE) score -= closestDistance / 24;

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best == null ? null : new Rect(best.bounds);
    }

    private static void collectAccessibilityNodes(
            AccessibilityNodeInfo node,
            List<NodeRecord> result,
            int depth,
            int[] visited
    ) {
        if (node == null || depth > 22 || visited[0]++ > 700) return;

        try {
            CharSequence description = node.getContentDescription();
            CharSequence text = node.getText();
            String label = normalize(
                    description != null && description.length() > 0
                            ? description.toString()
                            : text == null ? "" : text.toString()
            );

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!label.isEmpty() && !bounds.isEmpty()) {
                result.add(new NodeRecord(label, bounds));
            }

            int childCount = Math.min(node.getChildCount(), 80);
            for (int index = 0; index < childCount; index++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(index);
                    collectAccessibilityNodes(child, result, depth + 1, visited);
                } catch (Throwable ignored) {
                    // Um nó virtual pode desaparecer enquanto o Compose recompõe.
                } finally {
                    recycleQuietly(child);
                }
            }
        } catch (Throwable ignored) {
            // A interface pode mudar durante a coleta; a próxima varredura tentará novamente.
        }
    }

    private static void collectFromViewTree(
            View view,
            List<NodeRecord> result,
            int depth,
            int[] visited
    ) {
        if (view == null || depth > 18 || visited[0]++ > 450 || !view.isShown()) return;

        AccessibilityNodeInfo info = null;
        try {
            info = view.createAccessibilityNodeInfo();
            if (info != null) {
                CharSequence description = info.getContentDescription();
                CharSequence text = info.getText();
                String label = normalize(
                        description != null && description.length() > 0
                                ? description.toString()
                                : text == null ? "" : text.toString()
                );
                Rect bounds = new Rect();
                info.getBoundsInScreen(bounds);
                if (!label.isEmpty() && !bounds.isEmpty()) {
                    result.add(new NodeRecord(label, bounds));
                }
            }
        } catch (Throwable ignored) {
            // Continua percorrendo os filhos reais da árvore Android.
        } finally {
            recycleQuietly(info);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = Math.min(group.getChildCount(), 100);
            for (int index = 0; index < childCount; index++) {
                collectFromViewTree(group.getChildAt(index), result, depth + 1, visited);
            }
        }
    }

    private static boolean containsShareRecord(List<NodeRecord> records) {
        for (NodeRecord record : records) {
            if (isShareLabel(record.label)) return true;
        }
        return false;
    }

    private static boolean isShareLabel(String label) {
        return containsAny(label, "compartilhar", "share", "enviar publicacao", "send post");
    }

    private static boolean isCompanionActionLabel(String label) {
        return containsAny(
                label,
                "curtir", "curtida", "like",
                "responder", "resposta", "reply", "comment",
                "repost", "republicar", "citar", "quote"
        );
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static boolean isUsefulBounds(Rect bounds, int screenWidth, int screenHeight) {
        return !bounds.isEmpty()
                && bounds.right > 0
                && bounds.bottom > 0
                && bounds.left < screenWidth
                && bounds.top < screenHeight
                && bounds.width() <= screenWidth * 0.45f
                && bounds.height() <= screenHeight * 0.16f;
    }

    private static String normalize(String value) {
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutAccents
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static FrameLayout findContentFrame(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof FrameLayout) return (FrameLayout) content;
        if (content != null && content.getParent() instanceof FrameLayout) {
            return (FrameLayout) content.getParent();
        }
        return null;
    }

    private static void removeInlineButton(Activity activity) {
        FrameLayout content = findContentFrame(activity);
        if (content != null) removeInlineButton(content);
    }

    private static void removeInlineButton(FrameLayout content) {
        View button = content.findViewWithTag(INLINE_TAG);
        if (button != null && button.getParent() == content) {
            content.removeView(button);
        }
    }

    private static int resolvePrimaryTextColor(Context context) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
                if (value.resourceId != 0) {
                    ColorStateList colors = context.getColorStateList(value.resourceId);
                    if (colors != null) return colors.getDefaultColor();
                }
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
            }
        } catch (Throwable ignored) {
            // Fallback abaixo.
        }
        int night = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? Color.WHITE
                : Color.BLACK;
    }

    private static final class DownloadIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DownloadIconDrawable(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            float centerX = bounds.exactCenterX();
            paint.setStrokeWidth(Math.max(2f, width * 0.075f));

            float stemTop = bounds.top + height * 0.17f;
            float arrowBottom = bounds.top + height * 0.63f;
            canvas.drawLine(centerX, stemTop, centerX, arrowBottom, paint);
            canvas.drawLine(
                    centerX,
                    arrowBottom,
                    bounds.left + width * 0.31f,
                    bounds.top + height * 0.48f,
                    paint
            );
            canvas.drawLine(
                    centerX,
                    arrowBottom,
                    bounds.right - width * 0.31f,
                    bounds.top + height * 0.48f,
                    paint
            );

            float trayLeft = bounds.left + width * 0.24f;
            float trayRight = bounds.right - width * 0.24f;
            float trayTop = bounds.top + height * 0.70f;
            float trayBottom = bounds.top + height * 0.82f;
            canvas.drawLine(trayLeft, trayTop, trayLeft, trayBottom, paint);
            canvas.drawLine(trayLeft, trayBottom, trayRight, trayBottom, paint);
            canvas.drawLine(trayRight, trayBottom, trayRight, trayTop, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class NodeRecord {
        final String label;
        final Rect bounds;

        NodeRecord(String label, Rect bounds) {
            this.label = label;
            this.bounds = new Rect(bounds);
        }
    }

    private static void recycleQuietly(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            node.recycle();
        } catch (Throwable ignored) {
            // Sem ação.
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
                        "Nenhuma mídia detectada. Abra a imagem ou o vídeo e tente novamente.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            String lower = url.toLowerCase(Locale.ROOT);
            boolean video = lower.contains(".mp4") || lower.contains("/video") || lower.contains("t16/");
            String extension;
            String mime;
            if (video) {
                extension = ".mp4";
                mime = "video/mp4";
            } else if (lower.contains(".webp")) {
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
            reportEvent(context, "inline_download", fileName);
            Toast.makeText(context, "Download iniciado", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed inline download: " + error);
            XposedBridge.log(error);
            reportEvent(context, "hook_error", "inline_download: " + error);
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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
