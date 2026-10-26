package org.schabi.newpipe.watchlater;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.list.BaseListFragment;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Home tab for videos stored on the configured Watch Later server. */
public class WatchLaterFragment extends BaseListFragment<List<StreamInfoItem>, Void> {
    private Disposable request;
    private boolean resumedOnce;
    private SwipeRefreshLayout swipeRefreshLayout;

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        final View root = inflater.inflate(R.layout.fragment_kiosk, container, false);
        final View list = root.findViewById(R.id.items_list);
        final ViewGroup parent = (ViewGroup) list.getParent();
        final int index = parent.indexOfChild(list);
        final ViewGroup.LayoutParams layoutParams = list.getLayoutParams();
        parent.removeView(list);
        swipeRefreshLayout = new SwipeRefreshLayout(requireContext());
        parent.addView(swipeRefreshLayout, index, layoutParams);
        swipeRefreshLayout.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        setTitle(getString(R.string.watch_later_title));
        showListFooter(false);
    }

    @Override
    protected void initListeners() {
        super.initListeners();
        swipeRefreshLayout.setOnRefreshListener(this::reloadContent);
        infoListAdapter.setOnStreamSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final StreamInfoItem item) {
                if (item.getServiceId() < 0) {
                    ShareUtils.openUrlInBrowser(requireContext(), item.getUrl());
                } else {
                    NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                            item.getServiceId(), item.getUrl(), item.getName(), null, false);
                }
            }

            @Override
            public void held(final StreamInfoItem item) {
                if (item.getServiceId() < 0) {
                    new InfoItemDialog.Builder(requireActivity(), requireContext(),
                            WatchLaterFragment.this, item, false)
                            .addEntry(StreamDialogDefaultEntry.OPEN_IN_BROWSER)
                            .create().show();
                } else {
                    showInfoItemDialog(item);
                }
            }
        });
    }

    @Override
    protected void startLoading(final boolean forceLoad) {
        if (request != null) {
            request.dispose();
        }
        super.startLoading(forceLoad);
        final WatchLaterClient client;
        try {
            client = new WatchLaterClient(requireContext());
        } catch (final IllegalArgumentException error) {
            handleError();
            showError(new ErrorInfo(error, UserAction.SOMETHING_ELSE,
                    "Loading Watch Later videos"));
            return;
        }
        request = Single.fromCallable(() -> loadVideos(client, forceLoad))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleResult, error -> {
                    handleError();
                    showError(new ErrorInfo(error, UserAction.SOMETHING_ELSE,
                            "Loading Watch Later videos"));
                });
    }

    private List<StreamInfoItem> loadVideos(final WatchLaterClient client,
                                           final boolean forceLoad) throws Exception {
        final List<StreamInfoItem> result = new ArrayList<>();
        final List<WatchLaterClient.Video> videos = forceLoad
                ? client.getVideos() : client.getCachedOrFetchVideos();
        for (final WatchLaterClient.Video video : videos) {
            int serviceId = -1;
            try {
                serviceId = NewPipe.getServiceByUrl(video.url).getServiceId();
            } catch (final Exception ignored) {
                // Unsupported videos remain visible and open in a browser.
            }
            final StreamInfoItem item = new StreamInfoItem(serviceId, video.url,
                    video.title, StreamType.VIDEO_STREAM);
            item.setUploaderName(video.channel);
            item.setDuration(video.duration);
            item.setThumbnailUrl(video.thumbnail);
            result.add(item);
        }
        return result;
    }

    @Override
    public void handleResult(@NonNull final List<StreamInfoItem> videos) {
        swipeRefreshLayout.setRefreshing(false);
        isLoading.set(false);
        super.handleResult(videos);
        infoListAdapter.clearStreamItemList();
        if (videos.isEmpty()) {
            showEmptyState();
        } else {
            infoListAdapter.addInfoItemList(videos);
            showListFooter(false);
        }
    }

    @Override
    public void handleError() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
        super.handleError();
    }

    @Override
    protected void loadMoreItems() {
        // The Watch Later API returns the full list in one response.
    }

    @Override
    protected boolean hasMoreItems() {
        return false;
    }

    public void reloadFromCache() {
        startLoading(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (resumedOnce && !isLoading.get()) {
            reloadContent();
        }
        resumedOnce = true;
    }

    @Override
    public void onDestroyView() {
        resumedOnce = false;
        swipeRefreshLayout.setOnRefreshListener(null);
        swipeRefreshLayout = null;
        if (request != null) {
            request.dispose();
            request = null;
        }
        super.onDestroyView();
    }
}
