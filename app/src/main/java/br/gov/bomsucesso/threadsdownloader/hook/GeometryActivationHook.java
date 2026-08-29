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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entrada Xposed que encontra a barra de ações da publicação sem depender dos
 * textos internos do Threads. Primeiro tenta o rótulo de Compartilhar; quando
 * ele não existe, procura uma sequência de botões pequenos, clicáveis e
 * horizontalmente alinhados, como curtir, responder, repostar e compartilhar.
 */
public final class GeometryActivationHook implements IXposedHookLoadPackage {
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";
    private static final String MODULE_PACKAGE = "br.gov.bomsucesso.threadsdownloader";
    private static final String PROVIDER_URI =
            "content://br.gov.bomsucesso.threadsdownloader.settings/current";
    private static final String HOOK_VERSION = "3.3.6-inline-geometry";
    private static final String INLINE_TAG = "threads_enhancer_inline_download_336";

    private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);
    private static volatile Handler mainHandler;
    private static volatile boolean uiProcess;
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static long lastScanLogAt;

    private static final Runnable INLINE_SYNC = new Runnable() {
        @Override
        public void run() {
            Handler handler = mainHandler;
            Activity activity = resumedActivity.get();
            if (handler == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            updateInlineButton(activity);
            handler.postDelayed(this, 900L);
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!THREADS_PACKAGE.equals(param.packageName)) return;

        uiProcess = THREADS_PACKAGE.equals(param.processName);
        XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed geometry bootstrap: pacote carregado em " + param.processName
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
            XposedBridge.log("ThreadsEnhancer/ReLSPosed geometry attach: " + error);
            XposedBridge.log(error);
        }
    }

    private static final class AttachCallback extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Application application = (Application) param.thisObject;
            if (!THREADS_PACKAGE.equals(application.getPackageName())) return;

            if (mainHandler == null) {
                mainHandler = new Handler(application.getMainLooper());
            }

            XposedBridge.log(
                    "ThreadsEnhancer/ReLSPosed geometry bootstrap: Application.attach confirmado"
            );
            sendHeartbeat(application);

            if (uiProcess) registerLifecycle(application);
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
                Handler handler = mainHandler;
                if (handler != null) {
                    handler.removeCallbacks(INLINE_SYNC);
                    handler.postDelayed(INLINE_SYNC, 250L);
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumedActivity.get() == activity) {
                    resumedActivity.clear();
                    Handler handler = mainHandler;
                    if (handler != null) handler.removeCallbacks(INLINE_SYNC);
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

            View oldButton = content.findViewWithTag(INLINE_TAG);
            if (oldButton != null) oldButton.setVisibility(View.INVISIBLE);

            ActionAnchor anchor = findActionBarAnchor(activity);
            if (anchor == null) {
                removeInlineButton(content);
                return;
            }

            int size = dp(activity, 38);
            int gap = dp(activity, 2);
            int edge = dp(activity, 4);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;

            int left = anchor.bounds.right + gap;
            if (left + size > screenWidth - edge) {
                left = anchor.rowLeft - gap - size;
            }
            int top = anchor.bounds.centerY() - size / 2;
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
                reportEvent(
                        activity,
                        "inline_button",
                        anchor.source + " count=" + anchor.count
                );
                XposedBridge.log(
                        "ThreadsEnhancer/ReLSPosed: botão inline detectado por "
                                + anchor.source + " count=" + anchor.count
                );
            } else {
                button.setLayoutParams(params);
                button.setVisibility(View.VISIBLE);
            }
            button.bringToFront();
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed geometry button: " + error);
            XposedBridge.log(error);
            reportEvent(activity, "hook_error", "geometry_button: " + error);
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
        button.setPadding(dp(activity, 9), dp(activity, 9), dp(activity, 9), dp(activity, 9));
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

    private static ActionAnchor findActionBarAnchor(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        List<NodeRecord> records = new ArrayList<>();
        int[] visited = new int[]{0};

        AccessibilityNodeInfo root = null;
        try {
            root = decor.createAccessibilityNodeInfo();
            collectAccessibilityNodes(root, records, 0, visited);
        } catch (Throwable ignored) {
            // A coleta pela árvore real abaixo ainda será tentada.
        } finally {
            recycleQuietly(root);
        }

        visited[0] = 0;
        collectFromViewTree(decor, records, 0, visited);

        ActionAnchor labeled = findLabeledShare(activity, records);
        if (labeled != null) return labeled;

        ActionAnchor geometric = findGeometricRow(activity, records);
        if (geometric == null) {
            long now = System.currentTimeMillis();
            if (now - lastScanLogAt > 8_000L) {
                lastScanLogAt = now;
                int clickable = 0;
                for (NodeRecord record : records) if (record.clickable) clickable++;
                XposedBridge.log(
                        "ThreadsEnhancer/ReLSPosed: nenhuma barra de ações; records="
                                + records.size() + " clickable=" + clickable
                );
            }
        }
        return geometric;
    }

    private static ActionAnchor findLabeledShare(
            Activity activity,
            List<NodeRecord> records
    ) {
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int verticalTolerance = dp(activity, 42);
        NodeRecord best = null;
        int bestScore = Integer.MIN_VALUE;
        int companionsForBest = 0;
        int rowLeftForBest = 0;

        for (NodeRecord candidate : records) {
            if (!isShareLabel(candidate.label)) continue;
            if (!isUsefulBounds(candidate.bounds, screenWidth, screenHeight)) continue;

            int companions = 0;
            int rowLeft = candidate.bounds.left;
            for (NodeRecord other : records) {
                if (other == candidate || !isCompanionActionLabel(other.label)) continue;
                if (!isUsefulBounds(other.bounds, screenWidth, screenHeight)) continue;

                int verticalDistance = Math.abs(
                        other.bounds.centerY() - candidate.bounds.centerY()
                );
                int horizontalDistance = Math.abs(
                        other.bounds.centerX() - candidate.bounds.centerX()
                );
                if (verticalDistance <= verticalTolerance
                        && horizontalDistance <= screenWidth * 0.65f) {
                    companions++;
                    rowLeft = Math.min(rowLeft, other.bounds.left);
                }
            }

            if (companions < 1) continue;
            int score = companions * 180
                    - Math.abs(candidate.bounds.centerY() - (int) (screenHeight * 0.62f)) / 4;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                companionsForBest = companions;
                rowLeftForBest = rowLeft;
            }
        }

        return best == null
                ? null
                : new ActionAnchor(
                        new Rect(best.bounds),
                        rowLeftForBest,
                        companionsForBest + 1,
                        "label"
                );
    }

    private static ActionAnchor findGeometricRow(
            Activity activity,
            List<NodeRecord> records
    ) {
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int minWidth = dp(activity, 22);
        int maxWidth = dp(activity, 112);
        int minHeight = dp(activity, 22);
        int maxHeight = dp(activity, 96);
        int topLimit = dp(activity, 76);
        int bottomLimit = screenHeight - dp(activity, 92);
        int yTolerance = dp(activity, 15);
        int minGap = dp(activity, 22);
        int maxGap = dp(activity, 126);

        List<NodeRecord> small = new ArrayList<>();
        for (NodeRecord record : records) {
            Rect bounds = record.bounds;
            if (!record.clickable || !record.enabled || !record.visible) continue;
            if (isOurButton(record.label) || isNavigationLabel(record.label)) continue;
            if (!isUsefulBounds(bounds, screenWidth, screenHeight)) continue;
            if (bounds.width() < minWidth || bounds.width() > maxWidth) continue;
            if (bounds.height() < minHeight || bounds.height() > maxHeight) continue;
            if (bounds.centerY() < topLimit || bounds.centerY() > bottomLimit) continue;
            small.add(record);
        }

        Collections.sort(small, Comparator.comparingInt(record -> record.bounds.centerY()));
        ActionAnchor best = null;
        int bestScore = Integer.MIN_VALUE;

        for (NodeRecord seed : small) {
            List<NodeRecord> band = new ArrayList<>();
            for (NodeRecord record : small) {
                if (Math.abs(record.bounds.centerY() - seed.bounds.centerY()) <= yTolerance) {
                    addDistinctByCenterX(band, record, dp(activity, 10));
                }
            }
            if (band.size() < 3) continue;

            Collections.sort(band, Comparator.comparingInt(record -> record.bounds.centerX()));
            for (int start = 0; start < band.size(); start++) {
                List<NodeRecord> sequence = new ArrayList<>();
                sequence.add(band.get(start));
                for (int index = start + 1; index < band.size(); index++) {
                    NodeRecord previous = sequence.get(sequence.size() - 1);
                    NodeRecord next = band.get(index);
                    int gap = next.bounds.centerX() - previous.bounds.centerX();
                    if (gap < minGap) continue;
                    if (gap > maxGap) break;
                    sequence.add(next);
                }

                if (sequence.size() < 3 || sequence.size() > 7) continue;
                NodeRecord first = sequence.get(0);
                NodeRecord last = sequence.get(sequence.size() - 1);
                int span = last.bounds.right - first.bounds.left;
                if (span < dp(activity, 105) || span > screenWidth * 0.88f) continue;
                if (first.bounds.centerX() > screenWidth * 0.55f) continue;

                int actionLabels = 0;
                for (NodeRecord record : sequence) {
                    if (isAnyActionLabel(record.label)) actionLabels++;
                }

                int rowY = averageCenterY(sequence);
                int targetY = (int) (screenHeight * 0.62f);
                int score = sequence.size() * 260
                        + actionLabels * 140
                        - Math.abs(rowY - targetY) / 3
                        - Math.max(0, first.bounds.left - dp(activity, 40)) / 10;

                if (score > bestScore) {
                    bestScore = score;
                    best = new ActionAnchor(
                            new Rect(last.bounds),
                            first.bounds.left,
                            sequence.size(),
                            "geometry"
                    );
                }
            }
        }

        return best;
    }

    private static void addDistinctByCenterX(
            List<NodeRecord> records,
            NodeRecord candidate,
            int tolerance
    ) {
        for (int index = 0; index < records.size(); index++) {
            NodeRecord existing = records.get(index);
            if (Math.abs(existing.bounds.centerX() - candidate.bounds.centerX()) <= tolerance) {
                if (candidate.bounds.width() * candidate.bounds.height()
                        < existing.bounds.width() * existing.bounds.height()) {
                    records.set(index, candidate);
                }
                return;
            }
        }
        records.add(candidate);
    }

    private static int averageCenterY(List<NodeRecord> records) {
        int sum = 0;
        for (NodeRecord record : records) sum += record.bounds.centerY();
        return records.isEmpty() ? 0 : sum / records.size();
    }

    private static void collectAccessibilityNodes(
            AccessibilityNodeInfo node,
            List<NodeRecord> result,
            int depth,
            int[] visited
    ) {
        if (node == null || depth > 24 || visited[0]++ > 1_100) return;

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
            boolean clickable = node.isClickable() || node.isLongClickable();
            String className = node.getClassName() == null
                    ? ""
                    : node.getClassName().toString();

            if (!bounds.isEmpty() && (clickable || !label.isEmpty())) {
                addRecord(
                        result,
                        new NodeRecord(
                                label,
                                bounds,
                                clickable,
                                node.isEnabled(),
                                node.isVisibleToUser(),
                                className
                        )
                );
            }

            int childCount = Math.min(node.getChildCount(), 100);
            for (int index = 0; index < childCount; index++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(index);
                    collectAccessibilityNodes(child, result, depth + 1, visited);
                } catch (Throwable ignored) {
                    // O Compose pode recompor enquanto a árvore é percorrida.
                } finally {
                    recycleQuietly(child);
                }
            }
        } catch (Throwable ignored) {
            // A próxima varredura tentará novamente.
        }
    }

    private static void collectFromViewTree(
            View view,
            List<NodeRecord> result,
            int depth,
            int[] visited
    ) {
        if (view == null || depth > 20 || visited[0]++ > 900 || !view.isShown()) return;

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
                boolean clickable = view.isClickable()
                        || view.isLongClickable()
                        || info.isClickable()
                        || info.isLongClickable();
                String className = info.getClassName() == null
                        ? view.getClass().getName()
                        : info.getClassName().toString();
                if (!bounds.isEmpty() && (clickable || !label.isEmpty())) {
                    addRecord(
                            result,
                            new NodeRecord(
                                    label,
                                    bounds,
                                    clickable,
                                    view.isEnabled() && info.isEnabled(),
                                    info.isVisibleToUser(),
                                    className
                            )
                    );
                }
            }
        } catch (Throwable ignored) {
            // Continua percorrendo os filhos reais.
        } finally {
            recycleQuietly(info);
        }

        try {
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider != null) {
                AccessibilityNodeInfo virtualRoot = null;
                try {
                    virtualRoot = provider.createAccessibilityNodeInfo(
                            AccessibilityNodeProvider.HOST_VIEW_ID
                    );
                    collectAccessibilityNodes(virtualRoot, result, 0, new int[]{0});
                } finally {
                    recycleQuietly(virtualRoot);
                }
            }
        } catch (Throwable ignored) {
            // Nem toda implementação de provider aceita consulta direta.
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = Math.min(group.getChildCount(), 120);
            for (int index = 0; index < childCount; index++) {
                collectFromViewTree(group.getChildAt(index), result, depth + 1, visited);
            }
        }
    }

    private static void addRecord(List<NodeRecord> result, NodeRecord candidate) {
        for (NodeRecord existing : result) {
            if (existing.bounds.equals(candidate.bounds)) {
                if (existing.label.isEmpty() && !candidate.label.isEmpty()) {
                    existing.label = candidate.label;
                }
                existing.clickable = existing.clickable || candidate.clickable;
                existing.enabled = existing.enabled || candidate.enabled;
                existing.visible = existing.visible || candidate.visible;
                return;
            }
        }
        result.add(candidate);
    }

    private static boolean isShareLabel(String label) {
        return containsAny(
                label,
                "compartilhar", "share", "enviar publicacao", "send post", "enviar"
        );
    }

    private static boolean isCompanionActionLabel(String label) {
        return containsAny(
                label,
                "curtir", "curtida", "like",
                "responder", "resposta", "reply", "comment",
                "repost", "republicar", "citar", "quote"
        );
    }

    private static boolean isAnyActionLabel(String label) {
        return isShareLabel(label) || isCompanionActionLabel(label);
    }

    private static boolean isNavigationLabel(String label) {
        return containsAny(
                label,
                "pagina inicial", "inicio", "home",
                "pesquisar", "search", "explorar", "explore",
                "atividade", "activity", "notificacoes", "notifications",
                "perfil", "profile", "criar", "create",
                "nova thread", "new thread",
                "camera", "gif", "responder a"
        );
    }

    private static boolean isOurButton(String label) {
        return containsAny(label, "baixar midia", "download media");
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
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
                && bounds.width() <= screenWidth * 0.50f
                && bounds.height() <= screenHeight * 0.18f;
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
        String label;
        final Rect bounds;
        boolean clickable;
        boolean enabled;
        boolean visible;
        final String className;

        NodeRecord(
                String label,
                Rect bounds,
                boolean clickable,
                boolean enabled,
                boolean visible,
                String className
        ) {
            this.label = label == null ? "" : label;
            this.bounds = new Rect(bounds);
            this.clickable = clickable;
            this.enabled = enabled;
            this.visible = visible;
            this.className = className == null ? "" : className;
        }
    }

    private static final class ActionAnchor {
        final Rect bounds;
        final int rowLeft;
        final int count;
        final String source;

        ActionAnchor(Rect bounds, int rowLeft, int count, String source) {
            this.bounds = new Rect(bounds);
            this.rowLeft = rowLeft;
            this.count = count;
            this.source = source;
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
            boolean video = lower.contains(".mp4")
                    || lower.contains("/video")
                    || lower.contains("t16/");
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
            XposedBridge.log("ThreadsEnhancer/ReLSPosed geometry download: " + error);
            XposedBridge.log(error);
            reportEvent(context, "hook_error", "geometry_download: " + error);
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
            XposedBridge.log("ThreadsEnhancer/ReLSPosed geometry IPC: " + error);
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
                XposedBridge.log("ThreadsEnhancer/ReLSPosed geometry broadcast: " + error);
            }
        }

        XposedBridge.log(
                "ThreadsEnhancer/ReLSPosed geometry bootstrap: heartbeat enviado provider="
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
            // O log do ReLSPosed permanece como diagnóstico alternativo.
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
                    GeometryActivationHook.class.getClassLoader()
            );
            Object hook = hookClass.getDeclaredConstructor().newInstance();
            if (!(hook instanceof IXposedHookLoadPackage)) {
                throw new IllegalStateException("ThreadsHook não implementa IXposedHookLoadPackage");
            }
            ((IXposedHookLoadPackage) hook).handleLoadPackage(param);
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/ReLSPosed geometry delegate: " + error);
            XposedBridge.log(error);
        }
    }
}
