package com.pr0gramm.app.ui.views.viewer;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.akodiakson.sdk.simple.Sdk;
import com.jakewharton.rxbinding.view.RxView;
import com.pr0gramm.app.ActivityComponent;
import com.pr0gramm.app.BuildConfig;
import com.pr0gramm.app.Dagger;
import com.pr0gramm.app.R;
import com.pr0gramm.app.api.meta.ImmutableSizeInfo;
import com.pr0gramm.app.services.InMemoryCacheService;
import com.pr0gramm.app.services.ThemeHelper;
import com.pr0gramm.app.services.proxy.ProxyService;
import com.pr0gramm.app.ui.BackgroundBitmapDrawable;
import com.pr0gramm.app.ui.FancyThumbnailGenerator;
import com.pr0gramm.app.ui.PreviewInfo;
import com.pr0gramm.app.ui.views.AspectImageView;
import com.pr0gramm.app.util.AndroidUtility;
import com.pr0gramm.app.util.BackgroundScheduler;
import com.squareup.picasso.NetworkPolicy;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;
import com.trello.rxlifecycle.RxLifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

import javax.annotation.Nullable;
import javax.inject.Inject;

import butterknife.ButterKnife;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.subjects.BehaviorSubject;

import static android.view.GestureDetector.SimpleOnGestureListener;

/**
 */
public abstract class MediaView extends FrameLayout {
    private static final Logger logger = LoggerFactory.getLogger("MediaView");

    private static final float MIN_PREVIEW_ASPECT = 1 / 3.0f;

    protected static final int ANIMATION_DURATION = 500;

    private final PreviewTarget previewTarget = new PreviewTarget(this);
    private final BehaviorSubject<Void> onViewListener = BehaviorSubject.create();
    private final GestureDetector gestureDetector;
    private final MediaUri mediaUri;

    @Nullable
    private BackgroundBitmapDrawable blurredBackground;

    private boolean mediaShown;
    private TapListener tapListener;
    private boolean resumed;
    private boolean playing;

    private float viewAspect = -1;

    @Nullable
    private Rect clipBounds;

    @Nullable
    private View busyIndicator;

    @Nullable
    private AspectImageView preview;
    private Drawable previewDrawable;

    @Inject
    Picasso picasso;

    @Inject
    InMemoryCacheService inMemoryCacheService;

    @Inject
    ProxyService proxyService;

    @Inject
    FancyThumbnailGenerator fancyThumbnailGenerator;

    protected MediaView(Activity activity, @LayoutRes Integer layoutId, MediaUri mediaUri,
                        Runnable onViewListener) {

        super(activity);

        // inject all the stuff!
        injectComponent(Dagger.activityComponent(activity));

        this.mediaUri = mediaUri;
        this.onViewListener.subscribe(event -> onViewListener.run());

        setLayoutParams(DEFAULT_PARAMS);
        if (layoutId != null) {
            LayoutInflater.from(activity).inflate(layoutId, this);
            ButterKnife.bind(this);

            preview = ButterKnife.findById(this, R.id.preview);
            busyIndicator = ButterKnife.findById(this, R.id.busy_indicator);
        }

        // register the detector to handle double taps
        gestureDetector = new GestureDetector(activity, gestureListener);

        showPreloadedIndicator();
        addBlurredBackground();

        RxView.detaches(this).subscribe(event -> {
            picasso.cancelRequest(previewTarget);

            if (playing)
                stopMedia();

            if (resumed)
                onPause();
        });

        if (ThumbyService.isEligibleForPreview(mediaUri)) {
            RxView.attaches(this).limit(1).subscribe(event -> {
                // test if we need to load the thumby preview.
                if (hasPreviewView()) {
                    Uri uri = ThumbyService.thumbUri(mediaUri);
                    picasso.load(uri).noPlaceholder().into(previewTarget);
                }
            });
        }
    }

    private void addBlurredBackground() {
        // try to get a preview
        Bitmap bitmap = inMemoryCacheService.lowQualityPreview(mediaUri.getId());
        if (bitmap != null) {
            blurredBackground = new BackgroundBitmapDrawable(
                    new BitmapDrawable(getResources(), bitmap));

            applyBlurredBackground();
        }
    }

    @SuppressLint("SetTextI18n")
    private void showPreloadedIndicator() {
        if (BuildConfig.DEBUG && mediaUri.isLocal()) {
            TextView preloadHint = new TextView(getContext());
            preloadHint.setText("preloaded");
            preloadHint.setLayoutParams(DEFAULT_PARAMS);
            preloadHint.setTextColor(ContextCompat.getColor(getContext(), ThemeHelper.primaryColor()));
            addView(preloadHint);
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (getPaddingLeft() != left || getPaddingTop() != top || getPaddingRight() != right || getPaddingBottom() != bottom) {
            super.setPadding(left, top, right, bottom);
            applyBlurredBackground();
        }
    }

    private void applyBlurredBackground() {
        if (hasPreviewView() && blurredBackground != null) {
            // update the views background with the correct insets
            AndroidUtility.setViewBackground(this, new InsetDrawable(blurredBackground,
                    getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom()));
        }
    }

    protected <T> Observable.Transformer<T, T> backgroundBindView() {
        return observable -> observable
                .subscribeOn(BackgroundScheduler.instance())
                .unsubscribeOn(BackgroundScheduler.instance())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(RxLifecycle.<T>bindView(this));
    }

    protected <T> Observable.Transformer<T, T> simpleBindView() {
        return observable -> observable
                .observeOn(AndroidSchedulers.mainThread())
                .compose(RxLifecycle.<T>bindView(this));
    }

    /**
     * Implement to do dependency injection.
     */
    protected abstract void injectComponent(ActivityComponent component);

    /**
     * Sets the pixels image for this media view. You need to provide a width and height.
     * Those values will be used to place the pixels image correctly.
     */
    public void setPreviewInfo(PreviewInfo info) {
        if (!hasPreviewView())
            return;

        assert preview != null;
        if (info.getWidth() > 0 && info.getHeight() > 0) {
            float aspect = (float) info.getWidth() / (float) info.getHeight();
            setViewAspect(aspect);
        }

        if (info.getPreview() != null) {
            Drawable image = info.getPreview();
            if (image instanceof BitmapDrawable && info.getHeight() > 0) {
                float aspect = info.getWidth() / (float) info.getHeight();
                Bitmap bitmap = ((BitmapDrawable) image).getBitmap();
                bitmap = fancyThumbnailGenerator.fancyThumbnail(bitmap, aspect);
                image = new BitmapDrawable(getResources(), bitmap);
            }

            setPreviewDrawable(image);

        } else if (info.getPreviewUri() != null) {
            if (mediaIsPreloaded()) {
                picasso.load(info.getPreviewUri())
                        .networkPolicy(NetworkPolicy.OFFLINE, NetworkPolicy.NO_STORE)
                        .into(previewTarget);

            } else {
                // quickly load the pixels into this view
                picasso.load(info.getPreviewUri()).into(previewTarget);
            }

        } else {
            // We have no preview image or this image, so we can remove the view entirely
            removePreviewImage();
        }
    }

    private boolean mediaIsPreloaded() {
        return getMediaUri().isLocal();
    }

    private boolean hasPreviewView() {
        return preview != null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (viewAspect <= 0) {
            // If we dont have a view aspect, we will let the content decide
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        } else {
            boolean heightUnspecified = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED;

            // we shouldnt get larger than this.
            int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
            int maxHeight = MeasureSpec.getSize(heightMeasureSpec);

            int width;
            int height;
            if (heightUnspecified || maxWidth / (double) maxHeight < viewAspect) {
                width = maxWidth;
                height = (int) (maxWidth / viewAspect) + getPaddingTop() + getPaddingBottom();
            } else {
                width = (int) (maxHeight * viewAspect);
                height = maxHeight;
            }

            // use the calculated sizes!
            setMeasuredDimension(width, height);
            measureChildren(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /**
     * Removes the preview image from this view.
     * This will not remove the view until the transition ended.
     */
    public void removePreviewImage() {
        picasso.cancelRequest(previewTarget);
        AndroidUtility.removeView(preview);
        removeBlurredBackground();
        onPreviewRemoved();
    }

    protected void onPreviewRemoved() {
        // implement action that should be executed
        // the moment the preview is removed
    }

    protected void removeBlurredBackground() {
        blurredBackground = null;
        AndroidUtility.setViewBackground(this, null);
    }

    private boolean hasBlurredBackground() {
        return blurredBackground != null;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }

    /**
     * The listener that handles double tapping
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final SimpleOnGestureListener gestureListener = new SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return MediaView.this.onDoubleTap();
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return onSingleTap();
        }
    };

    protected boolean onDoubleTap() {
        return tapListener != null && tapListener.onDoubleTap();

    }

    protected boolean onSingleTap() {
        return tapListener != null && tapListener.onSingleTap();
    }

    /**
     * Displays an indicator that something is loading. You need to pair
     * this with a call to {@link #hideBusyIndicator()}
     */
    protected void showBusyIndicator() {
        if (busyIndicator != null) {
            if (busyIndicator.getParent() == null)
                addView(busyIndicator);

            busyIndicator.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Hides the busy indicator that was shown in {@link #showBusyIndicator()}.
     */
    protected void hideBusyIndicator() {
        AndroidUtility.removeView(busyIndicator);
    }

    @Nullable
    public View getProgressView() {
        return busyIndicator;
    }

    @Nullable
    public AspectImageView getPreviewView() {
        return preview;
    }

    public boolean isResumed() {
        return resumed;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void onPause() {
        resumed = false;
    }

    public void onResume() {
        resumed = true;
    }

    public TapListener getTapListener() {
        return tapListener;
    }

    public void setTapListener(TapListener tapListener) {
        this.tapListener = tapListener;
    }

    /**
     * Sets the aspect ratio of this view. Will be ignored, if not positive and size
     * is then estimated from the children. If aspect is provided, the size of
     * the view is estimated from its parents width.
     */
    public void setViewAspect(float viewAspect) {
        if (hasPreviewView()) {
            assert preview != null;
            preview.setAspect(viewAspect > 0
                    ? Math.max(viewAspect, MIN_PREVIEW_ASPECT)
                    : -1);
        }

        if (this.viewAspect != viewAspect) {
            this.viewAspect = viewAspect;
            requestLayout();
        }
    }

    public float getViewAspect() {
        return viewAspect;
    }

    /**
     * Returns the url that this view should display.
     */
    public MediaUri getMediaUri() {
        return mediaUri;
    }

    /**
     * Gets the effective uri that should be downloaded
     */
    public Uri getEffectiveUri() {
        if (!mediaUri.isLocal() && mediaUri.hasProxyFlag()) {
            return proxyService.proxy(mediaUri.getBaseUri());
        } else {
            return mediaUri.getBaseUri();
        }
    }

    public void playMedia() {
        logger.info("Should start playing media");
        playing = true;

        if (mediaShown) {
            onMediaShown();
        }
    }

    public void stopMedia() {
        logger.info("Should stop playing media");
        playing = false;
    }

    protected void onMediaShown() {
        removePreviewImage();
        mediaShown = true;

        if (isPlaying() && onViewListener != null) {
            if (!onViewListener.hasCompleted()) {
                onViewListener.onNext(null);
                onViewListener.onCompleted();
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawWithClipBounds(canvas, c -> super.dispatchDraw(canvas));
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void draw(Canvas canvas) {
        drawWithClipBounds(canvas, super::draw);
    }

    private void drawWithClipBounds(Canvas canvas, Action1<Canvas> action) {
        if (clipBounds != null) {
            canvas.save();

            // clip and draw!
            canvas.clipRect(clipBounds);
            action.call(canvas);

            canvas.restore();

        } else {
            action.call(canvas);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    public void setClipBoundsCompat(Rect clipBounds) {
        if (Sdk.isAtLeastLollipop()) {
            setClipBounds(clipBounds);
        } else if (this.clipBounds != clipBounds) {
            this.clipBounds = clipBounds;
            if (Sdk.isAtLeastJellyBeanMR2()) {
                setClipBounds(clipBounds);
            } else {
                invalidate();
            }
        }
    }

    public void rewind() {
        // do nothing by default
    }

    public MediaView getActualMediaView() {
        return this;
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        super.setLayoutParams(params);

        // forward the gravity to the preview if possible
        if (params instanceof FrameLayout.LayoutParams) {
            int gravity = ((LayoutParams) params).gravity;
            if (hasPreviewView()) {
                assert preview != null;
                ((LayoutParams) preview.getLayoutParams()).gravity = gravity;
            }
        }
    }

    protected void cacheMediaSize(int width, int height) {
        if (mediaUri.getId() > 0) {
            inMemoryCacheService.cacheSizeInfo(ImmutableSizeInfo.builder()
                    .id(mediaUri.getId())
                    .width(width)
                    .height(height)
                    .build());
        }
    }

    @SuppressWarnings("ConstantConditions")
    public void setPreviewDrawable(Drawable previewDrawable) {
        if (hasPreviewView()) {
            this.preview.setImageDrawable(previewDrawable);
            this.previewDrawable = previewDrawable;
        }
    }

    public interface TapListener {
        boolean onSingleTap();

        boolean onDoubleTap();
    }

    /**
     * Puts the loaded image into the pixels container, if there
     * still is a pixels container.
     */
    private static class PreviewTarget implements Target {
        private final WeakReference<MediaView> mediaView;

        public PreviewTarget(MediaView mediaView) {
            this.mediaView = new WeakReference<>(mediaView);
        }

        @Override
        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
            MediaView mediaView = this.mediaView.get();
            if (mediaView != null && mediaView.preview != null) {

                if (mediaView.previewDrawable != null) {
                    Drawable newImage = new BitmapDrawable(mediaView.getResources(), bitmap);
                    Drawable[] drawables = {new CenterDrawable(mediaView.previewDrawable), newImage};
                    TransitionDrawable drawable = new TransitionDrawable(drawables);
                    drawable.startTransition(ANIMATION_DURATION);

                    mediaView.setPreviewDrawable(drawable);
                } else {
                    // we have no other preview, lets do fancy stuff.
                    float aspect = mediaView.getViewAspect();
                    if (aspect > 0) {
                        bitmap = mediaView.fancyThumbnailGenerator.fancyThumbnail(bitmap, aspect);
                    }

                    Drawable preview = new BitmapDrawable(mediaView.getResources(), bitmap);
                    mediaView.setPreviewDrawable(preview);
                }
            }

        }

        @Override
        public void onBitmapFailed(Drawable errorDrawable) {
        }

        @Override
        public void onPrepareLoad(Drawable placeHolderDrawable) {
        }
    }

    private static final LayoutParams DEFAULT_PARAMS = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL);
}
