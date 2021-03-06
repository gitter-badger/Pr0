package com.pr0gramm.app.services;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.app.ShareCompat;
import android.widget.Toast;

import com.google.common.base.Optional;
import com.pr0gramm.app.R;
import com.pr0gramm.app.api.pr0gramm.response.Comment;
import com.pr0gramm.app.feed.FeedItem;
import com.pr0gramm.app.feed.FeedType;

/**
 * This class helps starting "Share with"-chooser for a {@link FeedItem}.
 */
public class ShareHelper {
    private ShareHelper() {
    }

    public static void searchImage(Activity activity, FeedItem feedItem) {
        String imageUri = UriHelper.of(activity).media(feedItem).toString().replace("https://", "http://");

        Uri uri = Uri.parse("https://www.google.com/searchbyimage").buildUpon()
                .appendQueryParameter("hl", "en")
                .appendQueryParameter("safe", "off")
                .appendQueryParameter("site", "search")
                .appendQueryParameter("image_url", imageUri)
                .build();

        Track.searchImage();
        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    public static void sharePost(Activity activity, FeedItem feedItem) {
        String text = feedItem.getPromotedId() > 0
                ? UriHelper.of(activity).post(FeedType.PROMOTED, feedItem.id()).toString()
                : UriHelper.of(activity).post(FeedType.NEW, feedItem.id()).toString();

        ShareCompat.IntentBuilder.from(activity)
                .setType("text/plain")
                .setText(text)
                .setChooserTitle(R.string.share_with)
                .startChooser();

        Track.share("post");
    }

    public static void shareDirectLink(Activity activity, FeedItem feedItem) {
        String uri = UriHelper.of(activity).noPreload().media(feedItem).toString();

        ShareCompat.IntentBuilder.from(activity)
                .setType("text/plain")
                .setText(uri)
                .setChooserTitle(R.string.share_with)
                .startChooser();

        Track.share("image_link");
    }

    public static void shareImage(Activity activity, FeedItem feedItem) {
        Optional<String> mimetype = ShareProvider.guessMimetype(activity, feedItem);
        if (mimetype.isPresent()) {
            ShareCompat.IntentBuilder.from(activity)
                    .setType(mimetype.get())
                    .addStream(ShareProvider.getShareUri(activity, feedItem))
                    .setChooserTitle(R.string.share_with)
                    .startChooser();

            Track.share("image");
        }
    }

    public static void copyLink(Context context, FeedItem feedItem) {
        UriHelper helper = UriHelper.of(context);
        String uri = helper.post(FeedType.NEW, feedItem.id()).toString();
        copyToClipboard(context, uri);
    }

    public static void copyLink(Context context, FeedItem feedItem, Comment comment) {
        UriHelper helper = UriHelper.of(context);
        String uri = helper.post(FeedType.NEW, feedItem.id(), comment.getId()).toString();
        copyToClipboard(context, uri);
    }

    private static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboardManager = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);

        clipboardManager.setPrimaryClip(ClipData.newPlainText(text, text));
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }
}
