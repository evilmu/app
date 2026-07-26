package br.gov.bomsucesso.threadsdownloader.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Integra as ações de download ao menu nativo de publicação do Threads 439.x.
 *
 * O APK analisado cria o conteúdo do PostActionMenuSheet em X.0NyL e renderiza
 * cada linha por X.01CX.A0A. O hook acompanha a composição atual, recupera o
 * Media exato do campo A0B e acrescenta duas células BDS nativas.
 */
public final class NativePostMenuHook implements IXposedHookLoadPackage {
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";
    private static final Uri PROVIDER_URI =
            Uri.parse("content://br.gov.bomsucesso.threadsdownloader.settings/current");
    private static final String HOOK_VERSION = "3.5.0-native-menu";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final ThreadLocal<ArrayDeque<MenuFrame>> MENU_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> INJECTING =
            ThreadLocal.withInitial(() -> false);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "threads-enhancer-download");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Application application;
    private static volatile ClassLoader targetLoader;
    private static volatile Method rowMethod;
    private static volatile Method clickableMethod;
    private static volatile Method roleMethod;
    private static volatile Method iconMethod;
    private static volatile Object baseModifier;
    private static volatile Class<?> function0Class;
    private static volatile Object kotlinUnit;

    private static final ArrayDeque<TimedUrl> RECENT_URLS = new ArrayDeque<>();
    private static final int MAX_RECENT_URLS = 80;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!THREADS_PACKAGE.equals(param.packageName)) return;

        XposedBridge.log("ThreadsEnhancer/NativeMenu: carregado em " + param.processName);

        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hookParam) {
                        Application app = (Application) hookParam.thisObject;
                        if (!THREADS_PACKAGE.equals(app.getPackageName())) return;

                        application = app;
                        sendHeartbeat(app, param.processName);

                        if (THREADS_PACKAGE.equals(param.processName)
                                && INSTALLED.compareAndSet(false, true)) {
                            targetLoader = param.classLoader;
                            try {
                                resolveNativeApi(param.classLoader);
                                installMenuHooks(param.classLoader);
                                installRecentUrlHooks();
                                XposedBridge.log("ThreadsEnhancer/NativeMenu: hooks nativos instalados");
                            } catch (Throwable error) {
                                reportError(app, "install", error);
                            }
                        }
                    }
                }
        );
    }

    private static void resolveNativeApi(ClassLoader loader) throws Exception {
        Class<?> composerClass = XposedHelpers.findClass("X.02tQ", loader);
        Class<?> modifierClass = XposedHelpers.findClass("X.02sM", loader);
        Class<?> textStyleClass = XposedHelpers.findClass("X.02uO", loader);
        Class<?> iconClass = XposedHelpers.findClass("X.0Hjk", loader);
        Class<?> rowClass = XposedHelpers.findClass("X.01CX", loader);
        Class<?> menuClass = XposedHelpers.findClass("X.0NyL", loader);
        Class<?> clickableClass = XposedHelpers.findClass("X.0161", loader);
        Class<?> roleFactoryClass = XposedHelpers.findClass("X.0022", loader);
        Class<?> roleClass = XposedHelpers.findClass("X.02yL", loader);
        Class<?> modifierSingletonClass = XposedHelpers.findClass("X.02vZ", loader);

        rowMethod = rowClass.getDeclaredMethod(
                "A0A",
                composerClass,
                modifierClass,
                textStyleClass,
                iconClass,
                String.class
        );
        rowMethod.setAccessible(true);

        clickableMethod = clickableClass.getDeclaredMethod(
                "A0F",
                modifierClass,
                roleClass,
                Object.class,
                String.class,
                boolean.class
        );
        clickableMethod.setAccessible(true);

        roleMethod = roleFactoryClass.getDeclaredMethod("A0i", int.class);
        roleMethod.setAccessible(true);

        iconMethod = menuClass.getDeclaredMethod("A00", composerClass, int.class);
        iconMethod.setAccessible(true);

        Field baseField = modifierSingletonClass.getDeclaredField("A02");
        baseField.setAccessible(true);
        baseModifier = baseField.get(null);

        function0Class = Class.forName("kotlin.jvm.functions.Function0", false, loader);
        Class<?> unitClass = Class.forName("kotlin.Unit", false, loader);
        kotlinUnit = unitClass.getField("INSTANCE").get(null);
    }

    private static void installMenuHooks(ClassLoader loader) throws Exception {
        Class<?> menuClass = XposedHelpers.findClass("X.0NyL", loader);
        Method invoke = menuClass.getDeclaredMethod("invoke", Object.class, Object.class);
        invoke.setAccessible(true);

        XposedBridge.hookMethod(invoke, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object media = readField(param.thisObject, "A0B");
                Activity activity = (Activity) readField(param.thisObject, "A00");
                Context context = (Context) readField(param.thisObject, "A01");
                MENU_STACK.get().push(new MenuFrame(param.thisObject, media, activity, context));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ArrayDeque<MenuFrame> stack = MENU_STACK.get();
                if (!stack.isEmpty()) stack.pop();
                if (stack.isEmpty()) MENU_STACK.remove();
            }
        });

        XposedBridge.hookMethod(rowMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(INJECTING.get())) return;

                ArrayDeque<MenuFrame> stack = MENU_STACK.get();
                MenuFrame frame = stack.peek();
                if (frame == null || frame.injected) return;

                frame.rowCount++;
                String existingLabel = String.valueOf(param.args[4]);
                boolean explicitAnchor = isAnchor(existingLabel);
                boolean fallbackAnchor = frame.rowCount == 4;
                if (!explicitAnchor && !fallbackAnchor) return;

                frame.injected = true;
                try {
                    INJECTING.set(true);
                    Object composer = param.args[0];
                    Object textStyle = param.args[2];
                    Object fallbackIcon = param.args[3];

                    renderRow(
                            composer,
                            textStyle,
                            fallbackIcon,
                            "Baixar",
                            () -> onQuickDownload(frame)
                    );
                    renderRow(
                            composer,
                            textStyle,
                            fallbackIcon,
                            "Opções de download",
                            () -> onDownloadOptions(frame)
                    );
                    sendEvent(frame.contextOrApplication(), "menu_injected", existingLabel);
                    XposedBridge.log(
                            "ThreadsEnhancer/NativeMenu: duas ações inseridas após '" + existingLabel + "'"
                    );
                } catch (Throwable error) {
                    reportError(frame.contextOrApplication(), "render", error);
                } finally {
                    INJECTING.set(false);
                }
            }
        });
    }

    private static boolean isAnchor(String label) {
        String value = normalize(label);
        return value.contains("copiar link")
                || value.contains("copy link")
                || value.equals("salvar")
                || value.equals("save")
                || value.contains("adicionar aos favoritos")
                || value.contains("add to favorites");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static void renderRow(
            Object composer,
            Object textStyle,
            Object fallbackIcon,
            String label,
            Runnable action
    ) throws Exception {
        Object role = roleMethod.invoke(null, 0);
        Object callback = createFunction0(label, action);
        Object modifier = clickableMethod.invoke(
                null,
                baseModifier,
                role,
                callback,
                label,
                true
        );

        Object icon;
        try {
            icon = iconMethod.invoke(null, composer, android.R.drawable.stat_sys_download_done);
        } catch (Throwable ignored) {
            icon = fallbackIcon;
        }

        rowMethod.invoke(null, composer, modifier, textStyle, icon, label);
    }

    private static Object createFunction0(String label, Runnable action) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("invoke".equals(name)) {
                try {
                    action.run();
                } catch (Throwable error) {
                    reportError(application, "click:" + label, error);
                }
                return kotlinUnit;
            }
            if ("toString".equals(name)) return "ThreadsEnhancer(" + label + ")";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            return null;
        };
        return Proxy.newProxyInstance(targetLoader, new Class<?>[]{function0Class}, handler);
    }

    private static void onQuickDownload(MenuFrame frame) {
        Activity activity = frame.activity;
        dismissMenu(activity);
        showToast(frame.contextOrApplication(), "Localizando a mídia…");
        WORKER.execute(() -> {
            List<MediaItem> items = extractMedia(frame.media);
            MediaItem best = chooseBest(items);
            runOnMain(() -> {
                if (best == null) {
                    showToast(frame.contextOrApplication(), "Mídia desta publicação não encontrada");
                } else {
                    enqueue(frame.contextOrApplication(), best);
                }
            });
        });
    }

    private static void onDownloadOptions(MenuFrame frame) {
        Activity activity = frame.activity;
        dismissMenu(activity);
        showToast(frame.contextOrApplication(), "Lendo as opções da publicação…");
        WORKER.execute(() -> {
            List<MediaItem> items = extractMedia(frame.media);
            runOnMain(() -> showOptions(activity, frame.contextOrApplication(), items));
        });
    }

    private static void dismissMenu(Activity activity) {
        if (activity == null) return;
        runOnMain(() -> {
            try {
                activity.onBackPressed();
            } catch (Throwable ignored) {
                // O menu pode já ter sido fechado pelo próprio Compose.
            }
        });
    }

    private static void showOptions(Activity activity, Context context, List<MediaItem> items) {
        if (activity == null || activity.isFinishing()) {
            showToast(context, "Não foi possível abrir as opções");
            return;
        }
        if (items.isEmpty()) {
            showToast(context, "Mídia desta publicação não encontrada");
            return;
        }

        List<MediaItem> videos = filter(items, true);
        List<MediaItem> images = filter(items, false);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (!videos.isEmpty()) {
            labels.add(images.isEmpty() ? "Baixar vídeo" : "Baixar como vídeo (com áudio)");
            actions.add(() -> enqueue(context, videos.get(0)));
        }
        if (!images.isEmpty()) {
            labels.add(videos.isEmpty() ? "Baixar imagem" : "Baixar como imagem");
            actions.add(() -> enqueue(context, images.get(0)));
        }
        if (videos.size() > 1) {
            labels.add("Baixar todos os vídeos (" + videos.size() + ")");
            actions.add(() -> enqueueAll(context, videos));
        }
        if (images.size() > 1) {
            labels.add("Baixar todas as imagens (" + images.size() + ")");
            actions.add(() -> enqueueAll(context, images));
        }
        if (items.size() > 1) {
            labels.add("Baixar tudo (" + items.size() + ")");
            actions.add(() -> enqueueAll(context, items));
        }

        new AlertDialog.Builder(activity)
                .setTitle("Opções de download")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static List<MediaItem> filter(List<MediaItem> items, boolean video) {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : items) if (item.video == video) result.add(item);
        return result;
    }

    private static void enqueueAll(Context context, List<MediaItem> items) {
        for (MediaItem item : items) enqueue(context, item);
    }

    private static void enqueue(Context context, MediaItem item) {
        if (context == null || item == null) return;
        try {
            String extension = item.extension();
            String fileName = "threads_" + System.currentTimeMillis() + "_" +
                    Math.abs(item.url.hashCode()) + extension;
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.url))
                    .setTitle(item.video ? "Vídeo do Threads" : "Imagem do Threads")
                    .setDescription("Download em andamento")
                    .setMimeType(item.mimeType())
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
            showToast(context, "Download iniciado");
        } catch (Throwable error) {
            reportError(context, "download", error);
            showToast(context, "Erro ao iniciar o download");
        }
    }

    private static List<MediaItem> extractMedia(Object media) {
        LinkedHashMap<String, MediaItem> found = new LinkedHashMap<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Node> queue = new ArrayDeque<>();
        if (media != null) queue.add(new Node(media, 0));

        int inspected = 0;
        while (!queue.isEmpty() && inspected < 1800) {
            Node node = queue.removeFirst();
            Object value = node.value;
            if (value == null || node.depth > 8) continue;
            inspected++;

            if (value instanceof CharSequence) {
                addCandidate(found, value.toString(), "media_graph");
                continue;
            }
            if (value instanceof Uri || value instanceof URL) {
                addCandidate(found, value.toString(), "media_graph");
                continue;
            }
            Class<?> type = value.getClass();
            if (isSimple(type) || !visited.add(value)) continue;

            if (value instanceof Iterable<?>) {
                int count = 0;
                for (Object child : (Iterable<?>) value) {
                    if (count++ >= 80) break;
                    queue.addLast(new Node(child, node.depth + 1));
                }
                continue;
            }
            if (value instanceof Map<?, ?>) {
                int count = 0;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (count++ >= 80) break;
                    queue.addLast(new Node(entry.getKey(), node.depth + 1));
                    queue.addLast(new Node(entry.getValue(), node.depth + 1));
                }
                continue;
            }
            if (type.isArray()) {
                int length = Math.min(Array.getLength(value), 80);
                for (int i = 0; i < length; i++) {
                    queue.addLast(new Node(Array.get(value, i), node.depth + 1));
                }
                continue;
            }

            invokeKnownGetters(value, queue, node.depth);
            for (Field field : allFields(type)) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object child = field.get(value);
                    if (child != null) queue.addLast(new Node(child, node.depth + 1));
                } catch (Throwable ignored) {
                    // Campos nativos ou protegidos são ignorados.
                }
            }
        }

        long now = System.currentTimeMillis();
        synchronized (RECENT_URLS) {
            for (TimedUrl recent : RECENT_URLS) {
                if (now - recent.time <= 15_000L) {
                    addCandidate(found, recent.url, "recent_network");
                }
            }
        }

        List<MediaItem> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparingInt((MediaItem item) -> item.score).reversed());
        return result;
    }

    private static void invokeKnownGetters(Object value, ArrayDeque<Node> queue, int depth) {
        String className = value.getClass().getName();
        boolean mediaRelated = className.contains("Media")
                || className.contains("Image")
                || className.contains("Video")
                || className.contains("Candidate")
                || className.contains("TypedUrl")
                || className.contains("Tree");
        if (!mediaRelated) return;

        String[] names = {"getUrl", "CSX", "A0D", "A0E", "Bza"};
        for (String name : names) {
            try {
                Method method = value.getClass().getMethod(name);
                if (method.getParameterTypes().length != 0) continue;
                method.setAccessible(true);
                Object child = method.invoke(value);
                if (child != null && child != value) queue.addLast(new Node(child, depth + 1));
            } catch (Throwable ignored) {
                // O método pode não existir nesta implementação concreta.
            }
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class && fields.size() < 120) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }

    private static boolean isSimple(Class<?> type) {
        return type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Class.class == type
                || type.isEnum();
    }

    private static void addCandidate(
            LinkedHashMap<String, MediaItem> found,
            String raw,
            String source
    ) {
        if (raw == null) return;
        String url = raw.trim().replace("\\/", "/").replace("\\u0026", "&");
        if (!isDirectMediaUrl(url)) return;

        boolean video = isVideoUrl(url);
        int score = video ? 800 : 500;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("/t16/")) score += 260;
        if (lower.contains(".mp4")) score += 220;
        if (lower.contains("scontent-") || lower.contains("cdninstagram.com")) score += 80;
        if ("media_graph".equals(source)) score += 350;
        if (lower.contains("t51.2885-19") || lower.contains("profile")
                || lower.contains("150x150") || lower.contains("320x320")) score -= 900;
        if (score <= 0) return;

        MediaItem candidate = new MediaItem(url, video, score);
        MediaItem previous = found.get(url);
        if (previous == null || candidate.score > previous.score) found.put(url, candidate);
    }

    private static boolean isDirectMediaUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) return false;
        boolean trusted = lower.contains("fbcdn.net")
                || lower.contains("cdninstagram.com")
                || lower.contains("instagram.com");
        if (!trusted) return false;
        return lower.contains(".mp4")
                || lower.contains(".m4v")
                || lower.contains(".jpg")
                || lower.contains(".jpeg")
                || lower.contains(".png")
                || lower.contains(".webp")
                || lower.contains("/t16/")
                || lower.contains("/t51.");
    }

    private static boolean isVideoUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".mp4") || lower.contains(".m4v") || lower.contains("/t16/");
    }

    private static MediaItem chooseBest(List<MediaItem> items) {
        if (items.isEmpty()) return null;
        for (MediaItem item : items) if (item.video) return item;
        return items.get(0);
    }

    private static void installRecentUrlHooks() {
        try {
            XposedBridge.hookAllConstructors(URL.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberRecent(String.valueOf(param.thisObject));
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/NativeMenu URL hook: " + error);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Uri.class,
                    "parse",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            rememberRecent((String) param.args[0]);
                        }
                    }
            );
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/NativeMenu Uri hook: " + error);
        }
    }

    private static void rememberRecent(String url) {
        if (!isDirectMediaUrl(url)) return;
        synchronized (RECENT_URLS) {
            RECENT_URLS.removeIf(item -> item.url.equals(url));
            RECENT_URLS.addLast(new TimedUrl(url, System.currentTimeMillis()));
            while (RECENT_URLS.size() > MAX_RECENT_URLS) RECENT_URLS.removeFirst();
        }
    }

    private static Object readField(Object owner, String name) {
        if (owner == null) return null;
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable error) {
            return null;
        }
    }

    private static void sendHeartbeat(Context context, String processName) {
        Bundle extras = new Bundle();
        extras.putString("framework", "ReLSPosed");
        extras.putString("process", processName);
        extras.putString("hook_version", HOOK_VERSION);
        try {
            Bundle result = context.getContentResolver().call(PROVIDER_URI, "heartbeat", null, extras);
            XposedBridge.log(
                    "ThreadsEnhancer/NativeMenu: heartbeat="
                            + (result != null && result.getBoolean("ok", false))
            );
        } catch (Throwable error) {
            XposedBridge.log("ThreadsEnhancer/NativeMenu heartbeat: " + error);
        }
    }

    private static void sendEvent(Context context, String event, String details) {
        if (context == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("event", event);
            extras.putString("details", details == null ? "" : details);
            context.getContentResolver().call(PROVIDER_URI, "event", null, extras);
        } catch (Throwable ignored) {
            // Telemetria não deve impedir a interface.
        }
    }

    private static void reportError(Context context, String stage, Throwable error) {
        String details = stage + ": " + error;
        XposedBridge.log("ThreadsEnhancer/NativeMenu " + details);
        XposedBridge.log(error);
        sendEvent(context, "hook_error", details);
    }

    private static void showToast(Context context, String text) {
        if (context == null) return;
        runOnMain(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            new Handler(Looper.getMainLooper()).post(action);
        }
    }

    private static final class MenuFrame {
        final Object menu;
        final Object media;
        final Activity activity;
        final Context context;
        boolean injected;
        int rowCount;

        MenuFrame(Object menu, Object media, Activity activity, Context context) {
            this.menu = menu;
            this.media = media;
            this.activity = activity;
            this.context = context;
        }

        Context contextOrApplication() {
            if (activity != null) return activity;
            if (context != null) return context;
            return application;
        }
    }

    private static final class Node {
        final Object value;
        final int depth;

        Node(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private static final class TimedUrl {
        final String url;
        final long time;

        TimedUrl(String url, long time) {
            this.url = url;
            this.time = time;
        }
    }

    private static final class MediaItem {
        final String url;
        final boolean video;
        final int score;

        MediaItem(String url, boolean video, int score) {
            this.url = url;
            this.video = video;
            this.score = score;
        }

        String extension() {
            String path = url.toLowerCase(Locale.ROOT).split("\\?", 2)[0];
            if (video) return path.endsWith(".m4v") ? ".m4v" : ".mp4";
            if (path.endsWith(".webp")) return ".webp";
            if (path.endsWith(".png")) return ".png";
            return ".jpg";
        }

        String mimeType() {
            String extension = extension();
            if (".mp4".equals(extension)) return "video/mp4";
            if (".m4v".equals(extension)) return "video/x-m4v";
            if (".webp".equals(extension)) return "image/webp";
            if (".png".equals(extension)) return "image/png";
            return "image/jpeg";
        }
    }
}
