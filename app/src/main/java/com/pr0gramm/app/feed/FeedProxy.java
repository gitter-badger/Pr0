package com.pr0gramm.app.feed;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.collect.Ordering;
import com.pr0gramm.app.api.Feed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rx.Observable;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.toArray;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Arrays.asList;
import static rx.android.observables.AndroidObservable.bindFragment;

/**
 */
public class FeedProxy {
    private final List<FeedItem> items = new ArrayList<>();

    private final Query query;
    private boolean loading;
    private boolean atEnd;
    private boolean atStart;

    @Nullable
    private transient OnChangeListener onChangeListener;

    @Nullable
    private transient Loader loader;

    public FeedProxy(Query query) {
        this.query = query;
    }

    private FeedProxy(Query query, List<FeedItem> items) {
        this.query = query;
        this.items.addAll(items);

        checkFeedOrder();
    }

    /**
     * Binds the observable to some kind of context.
     * Use {@link rx.android.observables.AndroidObservable#bindActivity(android.app.Activity, rx.Observable)}
     * or {@link rx.android.observables.AndroidObservable#bindFragment(Object, rx.Observable)} on
     * the given observable.
     *
     * @param observable The observable to bind.
     * @return The bound observable.
     */
    private <E> Observable<E> bind(Observable<E> observable) {
        if (loader == null) {
            observable = Observable.empty();
        } else {
            observable = loader.bind(observable);
        }

        return observable
                .finallyDo(this::onLoadFinished)
                .finallyDo(() -> loading = false);
    }

    /**
     * Returns a list of all the items in this feed.
     *
     * @return A list of all items in the feed.
     */
    public List<FeedItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Returns the feed item at the given index.
     */
    public FeedItem getItemAt(int idx) {
        return items.get(idx);
    }

    /**
     * Returns the query that is used to build this feed.
     *
     * @return A query that is used to build the feed
     */
    public Query getQuery() {
        return query;
    }

    /**
     * Asynchronously loads the before-page before
     */
    public void loadPreviousPage() {
        if (loading || items.isEmpty() || isAtStart() || loader == null)
            return;

        loading = true;
        onLoadStart();

        // do the loading.
        long newest = items.get(0).getId(query.getFeedType());
        bind(loader.getFeedService().getFeedItemsNewer(query, newest))
                .map(this::enhance)
                .subscribe(this::store, this::onError);
    }

    /**
     * Enhances the feed by additional data.
     *
     * @param feed The feed to enhance
     * @return The enhanced feed to display
     */
    private EnhancedFeed enhance(Feed feed) {
        List<FeedItem> items = new ArrayList<FeedItem>();
        for (Feed.Item item : feed.getItems())
            items.add(new FeedItem(item, false));

        return new EnhancedFeed(feed, items);
    }

    /**
     * Asynchronously loads the next page
     */
    public void loadNextPage() {
        if (loading || isAtEnd() || items.isEmpty())
            return;

        long oldest = items.get(items.size() - 1).getId(query.getFeedType());
        loadAfter(Optional.of(oldest));
    }

    /**
     * Loads one page of feed items after the given start post.
     *
     * @param start The post to start at.
     */
    private void loadAfter(Optional<Long> start) {
        if (loading || loader == null)
            return;

        loading = true;
        onLoadStart();

        // do the loading.
        bind(loader.getFeedService().getFeedItems(query, start))
                .map(this::enhance)
                .subscribe(this::store, this::onError);
    }

    /**
     * Clears this adapter and loads the first page of items again.
     */
    public void restart() {
        if (loading)
            return;

        // remove all previous items from the adapter.
        int oldSize = items.size();
        items.clear();

        if (onChangeListener != null)
            onChangeListener.onItemRangeRemoved(0, oldSize);

        // and start loading the first page
        atEnd = false;
        atStart = true;
        loadAfter(Optional.<Long>absent());
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isAtStart() {
        return atStart;
    }

    public boolean isAtEnd() {
        return atEnd;
    }

    private void store(EnhancedFeed feed) {
        if (feed.getFeed().isAtEnd())
            atEnd = true;

        if (feed.getFeed().isAtStart())
            atStart = true;

        Ordering<Feed.Item> ordering = Ordering.natural().reverse().onResultOf(this::feedTypeId);
        List<Feed.Item> newItems = ordering.sortedCopy(feed.getFeed().getItems());

        if (newItems.size() > 0) {
            // calculate where to insert
            int index = 0;

            // get the maximum and the minimum id
            long newMaxId = feedTypeId(newItems.get(0));
            long newMinId = feedTypeId(getLast(newItems));

            if (!items.isEmpty()) {
                long oldMaxId = items.get(0).getId(query.getFeedType());
                long oldMinId = items.get(items.size() - 1).getId(query.getFeedType());

                if (newMinId > oldMaxId) {
                    Log.i("Feed", "Okay, prepending new data to stored feed");
                    index = 0;

                } else if (newMaxId < oldMinId) {
                    Log.i("Feed", "Okay, appending new data to stored feed");
                    index = items.size();

                } else if (newMinId < oldMinId) {
                    // mixed!
                    Log.w("Feed", "New data is overlapping with old data! Appending new data.");
                    index = items.size();

                } else if (newMaxId > oldMaxId) {
                    Log.w("Feed", "New data is overlapping with old data! Prepending new data.");
                    index = items.size();
                }
            }

            // insert and notify observers about changes
            items.addAll(index, feed.getFeedItems());

            if (onChangeListener != null)
                onChangeListener.onItemRangeInserted(index, newItems.size());
        }

        checkFeedOrder();
    }

    private void checkFeedOrder() {
        // lets validate
        long prev = Integer.MAX_VALUE;
        for (FeedItem item : items) {
            long id = item.getId(query.getFeedType());
            if (prev <= id) {
                Log.w("Feed", "feed not in order!!");
                break;
            }

            prev = id;
        }
    }

    private long feedTypeId(Feed.Item item) {
        return query.getFeedType() == FeedType.PROMOTED
                ? item.getPromoted()
                : item.getId();
    }

    protected void onError(Throwable error) {
        if (loader != null)
            loader.onError(error);
    }

    protected void onLoadStart() {
        if (loader != null)
            loader.onLoadStart();
    }

    protected void onLoadFinished() {
        if (loader != null)
            loader.onLoadFinished();
    }

    public void setOnChangeListener(@Nullable OnChangeListener onChangeListener) {
        this.onChangeListener = onChangeListener;
    }

    @Nullable
    public OnChangeListener getOnChangeListener() {
        return onChangeListener;
    }

    @Nullable
    public Loader getLoader() {
        return loader;
    }

    public void setLoader(@Nullable Loader loader) {
        this.loader = loader;
    }

    public int getItemCount() {
        return items.size();
    }

    public Bundle toBundle(int idx) {
        Bundle bundle = new Bundle();
        bundle.putParcelable("query", query);

        // add a subset of the items
        int start = min(items.size(), max(0, idx - 32));
        int stop = min(items.size(), max(0, idx + 32));
        List<FeedItem> items = this.items.subList(start, stop);
        bundle.putParcelableArray("items", toArray(items, FeedItem.class));

        return bundle;
    }

    @SuppressWarnings("unchecked")
    public static FeedProxy fromBundle(Bundle bundle) {
        Query query = bundle.getParcelable("query");
        List<FeedItem> items = (List<FeedItem>) (List) asList(bundle.getParcelableArray("items"));
        return new FeedProxy(query, items);
    }

    public Optional<Integer> getPosition(@Nullable FeedItem start) {
        if (start == null)
            return Optional.absent();

        for (int idx = 0; idx < items.size(); idx++) {
            if (start.getId() == items.get(idx).getId())
                return Optional.of(idx);
        }

        return Optional.absent();
    }

    /**
     * Feed enhanced by {@link com.pr0gramm.app.feed.FeedItem}s.
     */
    private static class EnhancedFeed {
        private final Feed feed;
        private final List<FeedItem> feedItems;

        private EnhancedFeed(Feed feed, List<FeedItem> feedItems) {
            this.feed = feed;
            this.feedItems = feedItems;
        }

        public Feed getFeed() {
            return feed;
        }

        public List<FeedItem> getFeedItems() {
            return feedItems;
        }
    }

    public interface OnChangeListener {
        void onItemRangeInserted(int start, int count);

        void onItemRangeRemoved(int start, int count);
    }

    public interface Loader {
        <T> Observable<T> bind(Observable<T> observable);

        void onLoadStart();

        void onLoadFinished();

        void onError(Throwable error);

        FeedService getFeedService();
    }

    public static class FragmentFeedLoader implements Loader {
        private final Fragment fragment;
        private final FeedService feedService;

        public FragmentFeedLoader(Fragment fragment, FeedService feedService) {
            this.fragment = fragment;
            this.feedService = feedService;
        }

        @Override
        public <T> Observable<T> bind(Observable<T> observable) {
            return bindFragment(fragment, observable);
        }

        @Override
        public void onLoadStart() {

        }

        @Override
        public void onLoadFinished() {

        }

        @Override
        public void onError(Throwable error) {
            Log.e("Feed", "Could not load feed", error);
        }

        @Override
        public FeedService getFeedService() {
            return feedService;
        }
    }
}