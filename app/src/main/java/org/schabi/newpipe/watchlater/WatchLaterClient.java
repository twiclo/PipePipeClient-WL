package org.schabi.newpipe.watchlater;

import android.content.Context;

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

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Client for the self-hosted Watch Later API. All network methods run off the UI thread. */
public final class WatchLaterClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS).build();

    private final HttpUrl videosUrl;

    public WatchLaterClient(final Context context) {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.watch_later_url_key), "").trim();
        final HttpUrl base = HttpUrl.parse(value.endsWith("/") ? value : value + "/");
        if (base == null || !("http".equals(base.scheme()) || "https".equals(base.scheme()))) {
            throw new IllegalArgumentException(context.getString(R.string.watch_later_invalid_url));
        }
        videosUrl = base.newBuilder().addPathSegment("videos").build();
    }

    public static boolean isConfigured(final Context context) {
        return !PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.watch_later_url_key), "")
                .trim().isEmpty();
    }

    public List<Video> getVideos() throws IOException, JSONException {
        final Request request = new Request.Builder().url(videosUrl).get()
                .header("Cache-Control", "no-cache").build();
        try (Response response = HTTP.newCall(request).execute()) {
            ensureSuccess(response);
            final JSONArray array = new JSONArray(response.body().string());
            final List<Video> videos = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                final JSONObject item = array.getJSONObject(i);
                final JSONObject thumbnails = item.optJSONObject("thumbnails");
                videos.add(new Video(item.getLong("video_id"),
                        item.getString("url"), item.optString("title"),
                        item.optString("channel"), item.optLong("duration", -1),
                        thumbnails == null ? "" : thumbnails.optString("maxres"),
                        item.optString("external_id")));
            }
            return videos;
        }
    }

    public Video findVideo(final String url) throws IOException, JSONException {
        for (final Video video : getVideos()) {
            if (sameVideo(url, video)) {
                return video;
            }
        }
        return null;
    }

    public void addVideo(final String url) throws IOException, JSONException {
        final String body = new JSONObject().put("url", url).toString();
        final Request request = new Request.Builder().url(videosUrl)
                .post(RequestBody.create(JSON, body)).build();
        try (Response response = HTTP.newCall(request).execute()) {
            ensureSuccess(response);
        }
    }

    public void removeVideo(final long id) throws IOException {
        final Request request = new Request.Builder()
                .url(videosUrl.newBuilder().addPathSegment(Long.toString(id)).build())
                .delete().build();
        try (Response response = HTTP.newCall(request).execute()) {
            ensureSuccess(response);
        }
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
