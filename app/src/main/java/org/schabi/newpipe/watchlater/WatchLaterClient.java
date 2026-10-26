package org.schabi.newpipe.watchlater;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.NewPipe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Client for the self-hosted Watch Later API. All network methods run off the UI thread. */
public final class WatchLaterClient {
    private static final String TAG = "WatchLaterClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS).build();
    private static final long CACHE_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long PROGRESS_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final Object CACHE_LOCK = new Object();
    private static final Object PROGRESS_LOCK = new Object();
    private static HttpUrl cachedUrl;
    private static List<Video> cachedVideos;
    private static long cachedAt;
    private static String lastProgressUrl;
    private static long lastProgressAt;

    private final HttpUrl videosUrl;

    public WatchLaterClient(final Context context) {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.watch_later_url_key), "").trim();
        final HttpUrl base = HttpUrl.parse(value.endsWith("/") ? value : value + "/");
        if (base == null || !"https".equals(base.scheme())) {
            throw new IllegalArgumentException(context.getString(R.string.watch_later_invalid_url));
        }
        videosUrl = base.newBuilder().addPathSegment("videos").build();
    }

    public static boolean isConfigured(final Context context) {
        return !PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.watch_later_url_key), "")
                .trim().isEmpty();
    }

    public static void recordPlaybackProgress(final Context context, final String url,
                                              final long positionMs) {
        if (!isConfigured(context) || positionMs < 0) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (PROGRESS_LOCK) {
            if (!url.equals(lastProgressUrl)) {
                lastProgressUrl = url;
                lastProgressAt = now;
                return;
            }
            if (now - lastProgressAt < PROGRESS_INTERVAL_MS) {
                return;
            }
            lastProgressAt = now;
        }
        final Context appContext = context.getApplicationContext();
        Schedulers.io().scheduleDirect(() -> {
            try {
                final WatchLaterClient client = new WatchLaterClient(appContext);
                final Video video = client.findVideo(url);
                if (video != null) {
                    client.updateTimestamp(video.id, positionMs / 1000.0);
                }
            } catch (final IOException | JSONException | IllegalArgumentException error) {
                Log.w(TAG, "Could not update Watch Later playback position", error);
            }
        });
    }

    public List<Video> getVideos() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            selectCache();
            final Request request = new Request.Builder().url(videosUrl).get()
                    .header("Cache-Control", "no-cache").build();
            try (Response response = HTTP.newCall(request).execute()) {
                ensureSuccess(response);
                final JSONArray array = new JSONArray(response.body().string());
                final List<Video> videos = new ArrayList<>(array.length());
                for (int i = 0; i < array.length(); i++) {
                    videos.add(parseVideo(array.getJSONObject(i)));
                }
                cachedVideos = videos;
                cachedAt = SystemClock.elapsedRealtime();
                return new ArrayList<>(videos);
            }
        }
    }

    public List<Video> getCachedOrFetchVideos() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            selectCache();
            if (cachedVideos != null
                    && SystemClock.elapsedRealtime() - cachedAt < CACHE_MAX_AGE_MS) {
                return new ArrayList<>(cachedVideos);
            }
            return getVideos();
        }
    }

    public Video findVideo(final String url) throws IOException, JSONException {
        for (final Video video : getCachedOrFetchVideos()) {
            if (sameVideo(url, video)) {
                return video;
            }
        }
        return null;
    }

    public void addVideo(final String url) throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            selectCache();
            final String body = new JSONObject().put("url", url).toString();
            final Request request = new Request.Builder().url(videosUrl)
                    .post(RequestBody.create(JSON, body)).build();
            try (Response response = HTTP.newCall(request).execute()) {
                ensureSuccess(response);
                final Video added = parseVideo(new JSONObject(response.body().string()));
                if (cachedVideos != null) {
                    cachedVideos.removeIf(video -> video.id == added.id);
                    cachedVideos.add(added);
                }
            }
        }
    }

    public void removeVideo(final long id) throws IOException {
        synchronized (CACHE_LOCK) {
            selectCache();
            final Request request = new Request.Builder()
                    .url(videosUrl.newBuilder().addPathSegment(Long.toString(id)).build())
                    .delete().build();
            try (Response response = HTTP.newCall(request).execute()) {
                ensureSuccess(response);
                if (cachedVideos != null) {
                    cachedVideos.removeIf(video -> video.id == id);
                }
            }
        }
    }

    private void updateTimestamp(final long id, final double seconds)
            throws IOException, JSONException {
        final String body = new JSONObject().put("timestamp", seconds).toString();
        final Request request = new Request.Builder()
                .url(videosUrl.newBuilder().addPathSegment(Long.toString(id)).build())
                .put(RequestBody.create(JSON, body)).build();
        try (Response response = HTTP.newCall(request).execute()) {
            ensureSuccess(response);
        }
    }

    public void invalidateCache() {
        synchronized (CACHE_LOCK) {
            selectCache();
            cachedVideos = null;
        }
    }

    private void selectCache() {
        if (!videosUrl.equals(cachedUrl)) {
            cachedUrl = videosUrl;
            cachedVideos = null;
        }
    }

    private static Video parseVideo(final JSONObject item) throws JSONException {
        final JSONObject thumbnails = item.optJSONObject("thumbnails");
        return new Video(item.getLong("video_id"),
                item.getString("url"), item.optString("title"),
                item.optString("channel"), item.optLong("duration", -1),
                thumbnails == null ? "" : thumbnails.optString("maxres"),
                item.optString("external_id"));
    }

    private static void ensureSuccess(final Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("Watch Later returned HTTP " + response.code());
        }
    }

    private static boolean sameVideo(final String url, final Video video) {
        if (url.equals(video.url)) {
            return true;
        }
        try {
            final var service = NewPipe.getServiceByUrl(url);
            if (service.getServiceId() != NewPipe.getServiceByUrl(video.url).getServiceId()) {
                return false;
            }
            final String id = service.getStreamLHFactory().getId(url);
            return id.equals(video.externalId)
                    || id.equals(service.getStreamLHFactory().getId(video.url));
        } catch (final Exception ignored) {
            return false;
        }
    }

    public static final class Video {
        public final long id;
        public final String url;
        public final String title;
        public final String channel;
        public final long duration;
        public final String thumbnail;
        public final String externalId;

        private Video(final long id, final String url, final String title,
                      final String channel, final long duration, final String thumbnail,
                      final String externalId) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
            this.thumbnail = thumbnail;
            this.externalId = externalId;
        }
    }
}
