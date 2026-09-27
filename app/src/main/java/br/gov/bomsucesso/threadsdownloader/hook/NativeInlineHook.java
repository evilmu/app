package br.gov.bomsucesso.threadsdownloader.hook;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.Configuration;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * An inline per-post UI for the official Threads Compose feed.
 *
 * It does not place a single floating button over the application. Instead it
 * locates each visible media post's native share/menu accessibility nodes,
 * positions a download glyph in that particular post's action row and uses
 * that exact post's native menu to bind the corresponding Media model.
 *
 * The integration is isolated to Threads 439.x. If the semantics or obfuscated
 * classes differ, fail closed and log a diagnostic rather than downloading
 * a cached URL from some other post.
 */
public final class NativeInlineHook {
    private static final String THREADS = "com.instagram.barcelona";
    private static final AtomicReference<Pending> PENDING = new AtomicReference<>();
    private static final WeakHashMap<Activity, InlineUi> ACTIVE = new WeakHashMap<>();
    private static final long REQUEST_TIMEOUT_MS = 4500;

    public static void install(final ClassLoader hostLoader) {
        Class<?> sheet = XposedHelpers.findClassIfExists("X.0NyL", hostLoader);
        if (sheet == null) {
            XposedBridge.log("ThreadsInline: PostActionMenuSheet hook ausente; versão do Threads incompatível");
            return;
        }
        // A Compose menu lambda may be constructed while the feed is first loaded.
        // Its invoke() is the reliable moment when the selected post is opened.
        XC_MethodHook selectedPostHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                captureSelectedPost(param.thisObject);
            }
        };
        // Hook both the lambda and the constructor: some 439.x builds allocate
        // the menu lazily; others reuse a lambda created before the click.
        int invokeHooks = 0;
        for (java.lang.reflect.Method method : sheet.getDeclaredMethods()) {
            if (!method.getName().equals("invoke") || method.getParameterTypes().length != 2)
                continue;
            XposedBridge.hookMethod(method, selectedPostHook);
            invokeHooks++;
        }
        if (invokeHooks == 0) {
            XposedBridge.log("ThreadsInline: invoke/2 ausente; menu incompatível");
        }
        XposedBridge.hookAllConstructors(sheet, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                // Constructor fallback is useful only when the menu is allocated
                // on demand after the user's click. invoke() handles reused menus.
                captureSelectedPost(param.thisObject);
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!THREADS.equals(activity.getPackageName())) return;
                activity.getWindow().getDecorView().post(() -> {
                    synchronized (ACTIVE) {
                        InlineUi current = ACTIVE.get(activity);
                        if (current == null) {
                            current = new InlineUi(activity);
                            ACTIVE.put(activity, current);
                        }
                        current.start();
                    }
                });
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                synchronized (ACTIVE) {
                    InlineUi ui = ACTIVE.get(activity);
                    if (ui != null) ui.stop();
                }
                Pending current = PENDING.get();
                if (current != null && current.activity.get() == activity &&
                    System.currentTimeMillis() > current.deadline) {
                    PENDING.compareAndSet(current, null);
                }
            }
        });
        XposedBridge.log("ThreadsInline: hooks de post e interface registrados");
    }

    private static void captureSelectedPost(Object sheet) {
        Pending selected = PENDING.get();
        if (selected == null) return;
        if (System.currentTimeMillis() > selected.deadline) {
            PENDING.compareAndSet(selected, null);
            return;
        }
        Object media;
        try { media = XposedHelpers.getObjectField(sheet, "A0B"); }
        catch (Throwable problem) {
            XposedBridge.log("ThreadsInline: A0B indisponível: " +
                    problem.getClass().getSimpleName());
            return;
        }
        if (media == null || !PENDING.compareAndSet(selected, null)) return;
        Activity activity = selected.activity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final Object ownMedia = media;
        activity.runOnUiThread(() -> {
            XposedBridge.log("ThreadsInline: mídia da publicação selecionada vinculada");
            MediaFromPost.download(activity.getApplicationContext(), ownMedia);
        });
    }

    private static final class Pending {
        final WeakReference<Activity> activity;
        final long deadline;
        Pending(Activity activity) {
            this.activity = new WeakReference<>(activity);
            this.deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
        }
    }

    private static final class Snapshot {
        final AccessibilityNodeProvider provider;
        final int id;
        final Rect rect;
        final String label;
        final String cls;
        final boolean clickable;

        Snapshot(AccessibilityNodeProvider provider, int id, Rect rect,
                 String label, String cls, boolean clickable) {
            this.provider = provider;
            this.id = id;
            this.rect = rect;
            this.label = label;
            this.cls = cls;
            this.clickable = clickable;
        }

        boolean isMenu() {
            String s = label.toLowerCase(Locale.ROOT);
            return clickable &&
                (s.contains("mais opções") || s.contains("mais opcoes") ||
                 s.contains("more options") || s.contains("post options") ||
                 s.contains("opções da publicação") || s.contains("menu da publicação"));
        }

        boolean isShare() {
            String s = label.toLowerCase(Locale.ROOT);
            return clickable && (s.contains("compartilhar") || s.contains("share"));
        }

        boolean isMedia(int minWidth, int minHeight) {
            if (rect.width() < minWidth || rect.height() < minHeight) return false;
            String s = label.toLowerCase(Locale.ROOT);
            String c = cls.toLowerCase(Locale.ROOT);
            return c.contains("image") || c.contains("video") || c.contains("player") ||
                   s.contains("vídeo") || s.contains("video") || s.contains("imagem") ||
                   s.contains("image") || s.contains("foto") || s.contains("photo") ||
                   s.contains("gif");
        }
    }

    private static final class Visit {
        final int id;
        final int depth;
        Visit(int id, int depth) { this.id = id; this.depth = depth; }
    }

    private static final class InlineUi implements Runnable {
        final Activity activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        final FrameLayout root;
        final int dp;
        boolean running;
        long lastDiagnostic;
        final HashMap<String, TextView> controls = new HashMap<>();

        InlineUi(Activity activity) {
            this.activity = activity;
            dp = Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density));
            root = new FrameLayout(activity);
            root.setClipChildren(false);
            root.setClipToPadding(false);
            root.setClickable(false);
            root.setFocusable(false);
            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void start() {
            if (running) return;
            running = true;
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup && root.getParent() == null) {
                ((ViewGroup) decor).addView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            }
            handler.postDelayed(this, 450);
        }

        void stop() {
            running = false;
            handler.removeCallbacks(this);
            root.removeAllViews();
            controls.clear();
            if (root.getParent() instanceof ViewGroup)
                ((ViewGroup) root.getParent()).removeView(root);
        }

        @Override public void run() {
            if (!running || activity.isFinishing() || activity.isDestroyed()) return;
            try {
                // Never obstruct the native sheet, notifications or other windows.
                if (!activity.hasWindowFocus() || PENDING.get() != null) {
                    root.setVisibility(View.INVISIBLE);
                } else {
                    root.setVisibility(View.VISIBLE);
                    update();
                }
            } catch (Throwable problem) {
                if (System.currentTimeMillis() - lastDiagnostic > 20000) {
                    lastDiagnostic = System.currentTimeMillis();
                    XposedBridge.log("ThreadsInline: diagnóstico de feed: " +
                            problem.getClass().getSimpleName());
                }
            }
            if (running) handler.postDelayed(this, 900);
        }

        void update() {
            ArrayList<View> composeHosts = new ArrayList<>();
            findCompose(activity.getWindow().getDecorView(), composeHosts, 0);
            if (composeHosts.isEmpty()) {
                clearControls();
                diagnostic("nenhuma AndroidComposeView visível");
                return;
            }
            ArrayList<Snapshot> nodes = new ArrayList<>();
            for (View compose : composeHosts) {
                try {
                    AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
                    if (provider != null) readNodes(provider, nodes);
                } catch (Throwable ignored) { }
            }
            ArrayList<Snapshot> menus = new ArrayList<>();
            ArrayList<Snapshot> shares = new ArrayList<>();
            ArrayList<Snapshot> media = new ArrayList<>();
            int minWidth = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.35f);
            int minHeight = Math.max(90 * dp, Math.round(
                    activity.getResources().getDisplayMetrics().heightPixels * .08f));
            for (Snapshot node : nodes) {
                if (node.isMenu()) menus.add(node);
                if (node.isShare()) shares.add(node);
                if (node.isMedia(minWidth, minHeight)) media.add(node);
            }
            Collections.sort(menus, Comparator.comparingInt(a -> a.rect.centerY()));
            HashSet<String> stillVisible = new HashSet<>();
            int[] origin = new int[2];
            root.getLocationOnScreen(origin);
            for (int i = 0; i < menus.size(); i++) {
                Snapshot menu = menus.get(i);
                int nextY = i + 1 < menus.size() ? menus.get(i + 1).rect.centerY()
                        : Integer.MAX_VALUE;
                Snapshot postMedia = null;
                for (Snapshot item : media) {
                    if (item.rect.centerY() <= menu.rect.centerY() + 15 * dp) continue;
                    if (item.rect.centerY() >= nextY) continue;
                    if (postMedia == null || item.rect.centerY() < postMedia.rect.centerY())
                        postMedia = item;
                }
                if (postMedia == null) continue; // Text-only posts never get a button.
                Snapshot nativeShare = null;
                for (Snapshot share : shares) {
                    if (share.rect.centerY() < postMedia.rect.bottom - 36 * dp) continue;
                    if (share.rect.centerY() >= nextY) continue;
                    if (share.rect.centerY() > postMedia.rect.bottom + 140 * dp) continue;
                    nativeShare = share;
                    break;
                }
                if (nativeShare == null) continue; // Only inject beside native UFI actions.
                final int virtualId = menu.id;
                final AccessibilityNodeProvider provider = menu.provider;
                String key = System.identityHashCode(provider) + ":" + virtualId;
                stillVisible.add(key);
                TextView button = controls.get(key);
                if (button == null) {
                    button = createButton();
                    controls.put(key, button);
                    root.addView(button, new FrameLayout.LayoutParams(46 * dp, 46 * dp));
                }
                int x = nativeShare.rect.right + 7 * dp - origin[0];
                int y = nativeShare.rect.centerY() - 23 * dp - origin[1];
                // Respect the action bar's right edge without creating a floating button.
                x = Math.min(x, root.getWidth() - 48 * dp);
                if (x < 0 || y < -20 * dp || y > root.getHeight()) continue;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
                lp.leftMargin = x;
                lp.topMargin = y;
                button.setLayoutParams(lp);
                button.setVisibility(View.VISIBLE);
                button.setOnClickListener(view -> clickPost(provider, virtualId));
            }
            ArrayList<String> expired = new ArrayList<>();
            for (String key : controls.keySet()) if (!stillVisible.contains(key)) expired.add(key);
            for (String key : expired) {
                root.removeView(controls.remove(key));
            }
            if (controls.isEmpty()) {
                diagnostic("nós=" + nodes.size() + " menus=" + menus.size() +
                           " compartilhamentos=" + shares.size() + " mídias=" + media.size());
            }
        }

        private void clickPost(AccessibilityNodeProvider provider, int menuId) {
            if (PENDING.get() != null) return;
            Pending request = new Pending(activity);
            PENDING.set(request);
            root.setVisibility(View.INVISIBLE);
            boolean opened = false;
            try {
                opened = provider.performAction(menuId, AccessibilityNodeInfo.ACTION_CLICK, new Bundle());
            } catch (Throwable error) {
                XposedBridge.log("ThreadsInline: ação nativa indisponível");
            }
            if (!opened) {
                PENDING.compareAndSet(request, null);
                root.setVisibility(View.VISIBLE);
                Toast.makeText(activity,
                    "Não foi possível abrir as ações deste post. Verifique a versão do Threads.",
                    Toast.LENGTH_LONG).show();
            } else {
                handler.postDelayed(() -> {
                    if (PENDING.compareAndSet(request, null)) {
                        Toast.makeText(activity,
                            "O Threads não forneceu os dados desta publicação.",
                            Toast.LENGTH_LONG).show();
                    }
                }, REQUEST_TIMEOUT_MS);
            }
        }

        private TextView createButton() {
            TextView view = new TextView(activity);
            view.setText("↓");
            view.setTextSize(27);
            view.setGravity(Gravity.CENTER);
            view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            boolean dark = (activity.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            view.setTextColor(dark ? android.graphics.Color.WHITE : android.graphics.Color.BLACK);
            view.setContentDescription("Baixar mídia desta publicação");
            view.setClickable(true);
            view.setFocusable(true);
            return view;
        }

        private void diagnostic(String message) {
            if (System.currentTimeMillis() - lastDiagnostic >= 20000) {
                lastDiagnostic = System.currentTimeMillis();
                XposedBridge.log("ThreadsInline: " + message);
            }
        }

        private void clearControls() {
            if (!controls.isEmpty()) {
                controls.clear();
                root.removeAllViews();
            }
        }

        private void findCompose(View view, List<View> output, int depth) {
            if (view == null || view == root || depth > 25 || output.size() > 16) return;
            if (view.getClass().getName().contains("AndroidComposeView") &&
                view.getVisibility() == View.VISIBLE && view.isShown()) output.add(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int children = Math.min(group.getChildCount(), 70);
                for (int i = 0; i < children; i++)
                    findCompose(group.getChildAt(i), output, depth + 1);
            }
        }

        private void readNodes(AccessibilityNodeProvider provider, List<Snapshot> output) {
            ArrayDeque<Visit> queue = new ArrayDeque<>();
            Set<Integer> visited = new HashSet<>();
            queue.addLast(new Visit(AccessibilityNodeProvider.HOST_VIEW_ID, 0));
            int nodes = 0;
            while (!queue.isEmpty() && nodes < 700) {
                Visit visit = queue.removeFirst();
                if (!visited.add(visit.id) || visit.depth > 24) continue;
                AccessibilityNodeInfo info;
                try { info = provider.createAccessibilityNodeInfo(visit.id); }
                catch (Throwable error) { continue; }
                if (info == null) continue;
                nodes++;
                try {
                    Rect bounds = new Rect();
                    info.getBoundsInScreen(bounds);
                    CharSequence desc = info.getContentDescription();
                    CharSequence text = info.getText();
                    String label = desc != null ? desc.toString() :
                            (text == null ? "" : text.toString());
                    String cls = info.getClassName() == null ? "" : info.getClassName().toString();
                    if (!bounds.isEmpty() && info.isVisibleToUser())
                        output.add(new Snapshot(provider, visit.id, bounds,
                                label, cls, info.isClickable()));
                    for (int i = 0; i < Math.min(50, info.getChildCount()); i++) {
                        Integer id = childVirtualId(info, i);
                        if (id != null && !visited.contains(id))
                            queue.addLast(new Visit(id, visit.depth + 1));
                    }
                } finally {
                    info.recycle();
                }
            }
        }

        private Integer childVirtualId(AccessibilityNodeInfo info, int position) {
            try {
                // Android exposes node IDs internally as viewId << 32 | virtualId.
                Object full = XposedHelpers.callMethod(info, "getChildId", position);
                if (full instanceof Long) return (int) ((Long) full).longValue();
            } catch (Throwable ignored) { }
            try {
                Object ids = XposedHelpers.getObjectField(info, "mChildNodeIds");
                Object full = XposedHelpers.callMethod(ids, "get", position);
                if (full instanceof Long) return (int) ((Long) full).longValue();
            } catch (Throwable ignored) { }
            try {
                AccessibilityNodeInfo child = info.getChild(position);
                if (child == null) return null;
                try {
                    Object full = XposedHelpers.callMethod(child, "getSourceNodeId");
                    if (full instanceof Long) return (int) ((Long) full).longValue();
                } finally { child.recycle(); }
            } catch (Throwable ignored) { }
            return null;
        }
    }

    private NativeInlineHook() { }
}
