package br.gov.bomsucesso.threadsdownloader.hook;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

/**
 * Only accepts media URLs reachable from the Media object belonging to the
 * selected post. Never uses the global last-loaded-CDN-URL heuristic.
 */
final class MediaFromPost {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int OBJECT_LIMIT = 400;
    private static final long FILE_LIMIT = 1_500_000_000L;

    static void download(final Context host, final Object selectedMedia) {
        if (selectedMedia == null) {
            notifyUser(host, "Não há mídia nesta publicação.");
            return;
        }
        WORKER.execute(() -> {
            try {
                List<Candidate> found = candidatesFor(selectedMedia);
                if (found.isEmpty()) {
                    notifyUser(host, "Não encontrei arquivo direto nesta publicação. Confira o log do módulo.");
                    XposedBridge.log("ThreadsInline: mídia do post sem URL direta (sem usar outra publicação)");
                    return;
                }
                // Prefer playable video to thumbnails; include all carousel items.
                boolean videoFound = false;
                for (Candidate item : found) if (item.video) videoFound = true;
                int saved = 0;
                String lastError = null;
                for (Candidate item : found) {
                    if (videoFound && !item.video) continue;
                    try {
                        save(host, item);
                        saved++;
                    } catch (IOException error) {
                        lastError = error.getMessage();
                        XposedBridge.log("ThreadsInline: download falhou: " + error.getClass().getSimpleName());
                    }
                    // A single post may have a large media carousel. Prevent abusive fanout.
                    if (saved >= 10) break;
                }
                if (saved > 0) notifyUser(host, saved + (saved == 1
                        ? " arquivo salvo em Downloads/Threads"
                        : " arquivos salvos em Downloads/Threads"));
                else notifyUser(host, "Download falhou: " +
                        (lastError == null ? "o servidor recusou o arquivo" : lastError));
            } catch (Throwable problem) {
                XposedBridge.log("ThreadsInline: extração falhou: " + problem.getClass().getSimpleName());
                notifyUser(host, "Não consegui extrair a mídia desta publicação.");
            }
        });
    }

    static final class Candidate {
        final String url;
        final String extension;
        final String mime;
        final boolean video;
        Candidate(String url, String extension, String mime) {
            this.url = url;
            this.extension = extension;
            this.mime = mime;
            this.video = mime.startsWith("video/");
        }
    }

    private static final class Node {
        final Object value;
        final int depth;
        Node(Object value, int depth) { this.value = value; this.depth = depth; }
    }

    static List<Candidate> candidatesFor(Object post) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        queue.add(new Node(post, 0));
        int processed = 0;
        while (!queue.isEmpty() && processed < OBJECT_LIMIT && urls.size() < 25) {
            Node node = queue.removeFirst();
            Object value = node.value;
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            processed++;
            if (value instanceof CharSequence) {
                String raw = value.toString().replace("\\/", "/").replace("\\u0026", "&");
                Candidate candidate = candidate(raw);
                if (candidate != null) urls.add(candidate.url);
                continue;
            }
            if (node.depth >= 5) continue;
            if (value instanceof Iterable) {
                int i = 0;
                for (Object item : (Iterable<?>) value) {
                    if (item != null) queue.addLast(new Node(item, node.depth + 1));
                    if (++i >= 40) break;
                }
                continue;
            }
            if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
                int size = Math.min(Array.getLength(value), 40);
                for (int i = 0; i < size; i++) {
                    Object item = Array.get(value, i);
                    if (item != null) queue.addLast(new Node(item, node.depth + 1));
                }
                continue;
            }
            if (value instanceof java.util.Map) {
                int i = 0;
                for (Object item : ((java.util.Map<?, ?>) value).values()) {
                    if (item != null) queue.addLast(new Node(item, node.depth + 1));
                    if (++i >= 40) break;
                }
                continue;
            }
            String className = value.getClass().getName();
            // Traverse only the selected post's media model and its typed URL/size children.
            if (!(className.startsWith("com.instagram.feed.media.") ||
                  className.startsWith("com.instagram.common.typedurl.") ||
                  className.startsWith("com.instagram.model.mediasize.") ||
                  className.startsWith("com.instagram.api.schemas.") ||
                  className.startsWith("com.facebook.pando.") ||
                  className.startsWith("X."))) continue;

            for (Class<?> type = value.getClass();
                 type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    // Avoid chasing sessions/users or arbitrary cached feed objects.
                    String fieldType = field.getType().getName();
                    if (fieldType.contains("Session") || fieldType.contains("User") ||
                        fieldType.contains("Context") || fieldType.contains("View") ||
                        fieldType.contains("Listener")) continue;
                    try {
                        field.setAccessible(true);
                        Object next = field.get(value);
                        if (next != null && !seen.containsKey(next)) {
                            queue.addLast(new Node(next, node.depth + 1));
                        }
                    } catch (Throwable ignored) { }
                }
            }
            // Version 439 Media and ImageUrl expose these lightweight getters.
            String[] getters = {
                "getUrl", "getVideoUrl", "getVideoVersions", "getImageVersions2",
                "getCandidates", "A0D", "A0E"
            };
            for (String name : getters) {
                try {
                    Method m = value.getClass().getMethod(name);
                    if (m.getParameterTypes().length != 0 || m.getReturnType() == Void.TYPE) continue;
                    Object next = m.invoke(value);
                    if (next != null && !seen.containsKey(next)) {
                        queue.addLast(new Node(next, node.depth + 1));
                    }
                } catch (Throwable ignored) { }
            }
        }
        List<Candidate> result = new ArrayList<>();
        for (String url : urls) {
            Candidate c = candidate(url);
            if (c != null) result.add(c);
        }
        // Never download unrelated image thumbnails when the post has video.
        Collections.sort(result, (a, b) -> Boolean.compare(b.video, a.video));
        return result;
    }

    static Candidate candidate(String raw) {
        if (raw == null || raw.length() > 4096 || raw.length() < 20) return null;
        String value = raw.trim().replace("&amp;", "&");
        Uri uri;
        try { uri = Uri.parse(value); } catch (Throwable ignored) { return null; }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (!(host.equals("fbcdn.net") || host.endsWith(".fbcdn.net") ||
              host.equals("cdninstagram.com") || host.endsWith(".cdninstagram.com"))) return null;
        String path = uri.getPath();
        if (path == null) return null;
        path = path.toLowerCase(Locale.ROOT);
        if (path.contains("t51.2885-19")) return null; // Never download profile avatars.
        String ext;
        String mime;
        if (path.endsWith(".mp4")) { ext = "mp4"; mime = "video/mp4"; }
        else if (path.endsWith(".m4v")) { ext = "m4v"; mime = "video/x-m4v"; }
        else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            ext = "jpg"; mime = "image/jpeg";
        } else if (path.endsWith(".png")) { ext = "png"; mime = "image/png"; }
        else if (path.endsWith(".webp")) { ext = "webp"; mime = "image/webp"; }
        else return null;
        return new Candidate(value, ext, mime);
    }

    private static void save(Context context, Candidate candidate) throws IOException {
        HttpURLConnection conn = null;
        Uri destination = null;
        try {
            URL next = new URL(candidate.url);
            for (int n = 0; n < 6; n++) {
                if (!"https".equalsIgnoreCase(next.getProtocol()))
                    throw new IOException("Redirecionamento HTTPS inválido");
                conn = (HttpURLConnection) next.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                conn.setRequestProperty("Referer", "https://www.threads.net/");
                conn.setRequestProperty("Accept", "video/*,image/*,application/octet-stream");
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirecionamento sem endereço");
                    next = new URL(next, location);
                    conn.disconnect();
                    conn = null;
                } else break;
            }
            if (conn == null) throw new IOException("Muitos redirecionamentos");
            int status = conn.getResponseCode();
            if (status != 200 && status != 206) {
                throw new IOException(status == 403 ? "URL expirada (HTTP 403)" :
                        "Erro do servidor HTTP " + status);
            }
            String contentType = conn.getContentType();
            if (contentType != null && (contentType.contains("text/html") ||
                    contentType.contains("application/json"))) {
                throw new IOException("Servidor retornou uma página, não a mídia");
            }
            long length = conn.getContentLengthLong();
            if (length > FILE_LIMIT) throw new IOException("Arquivo grande demais");
            BufferedInputStream input = new BufferedInputStream(conn.getInputStream());
            byte[] header = new byte[32];
            int hlen = 0;
            while (hlen < header.length) {
                int n = input.read(header, hlen, header.length - hlen);
                if (n < 0) break;
                hlen += n;
            }
            if (!isRealMedia(header, hlen, candidate.extension)) {
                input.close();
                throw new IOException("Resposta não contém mídia válida");
            }
            String name = "threads_" + System.currentTimeMillis() + "_" +
                    (int) (Math.random() * 9999) + "." + candidate.extension;
            ContentValues row = new ContentValues();
            row.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            row.put(MediaStore.MediaColumns.MIME_TYPE, candidate.mime);
            row.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Threads");
            row.put(MediaStore.MediaColumns.IS_PENDING, 1);
            destination = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row);
            if (destination == null) { input.close(); throw new IOException("Não foi possível criar Downloads/Threads"); }
            try (InputStream src = input;
                 OutputStream out = context.getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new IOException("Pasta Downloads indisponível");
                out.write(header, 0, hlen);
                long total = hlen;
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = src.read(buffer)) != -1) {
                    total += n;
                    if (total > FILE_LIMIT) throw new IOException("Arquivo excedeu o limite");
                    out.write(buffer, 0, n);
                }
                out.flush();
                if (length > 0 && total < length) throw new IOException("Download incompleto");
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            int updated = context.getContentResolver().update(destination, ready, null, null);
            if (updated == 0) throw new IOException("Falha ao finalizar arquivo");
            destination = null;
        } finally {
            if (conn != null) conn.disconnect();
            if (destination != null) {
                try { context.getContentResolver().delete(destination, null, null); }
                catch (Throwable ignored) { }
            }
        }
    }

    private static boolean isRealMedia(byte[] p, int n, String extension) {
        if (n < 12) return false;
        if (extension.equals("jpg")) return (p[0] & 255) == 255 &&
                (p[1] & 255) == 216 && (p[2] & 255) == 255;
        if (extension.equals("png")) return (p[0] & 255) == 137 &&
                p[1] == 80 && p[2] == 78 && p[3] == 71;
        if (extension.equals("webp")) return p[0] == 'R' && p[1] == 'I' &&
                p[2] == 'F' && p[3] == 'F' && p[8] == 'W' && p[9] == 'E' &&
                p[10] == 'B' && p[11] == 'P';
        return p[4] == 'f' && p[5] == 't' && p[6] == 'y' && p[7] == 'p';
    }

    private static void notifyUser(Context context, String message) {
        new android.os.Handler(context.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private MediaFromPost() { }
}
