package com.pr0gramm.app.api.pr0gramm.response;

import android.support.annotation.Nullable;

import com.pr0gramm.app.feed.FeedItem;
import com.pr0gramm.app.services.HasThumbnail;

import org.immutables.gson.Gson;
import org.immutables.value.Value;
import org.joda.time.Instant;

/**
 * A message received from the pr0gramm api.
 */
@Value.Immutable
@Value.Style(init = "with*")
@Gson.TypeAdapters
public abstract class Message implements HasThumbnail {
    public abstract long id();

    public abstract Instant getCreated();

    public abstract long getItemId();

    public abstract int getMark();

    public abstract String getMessage();

    public abstract String getName();

    public abstract int getScore();

    public abstract int getSenderId();

    @Nullable
    @Gson.Named("thumb")
    public abstract String thumbnail();

    public static Message of(PrivateMessage message) {
        return ImmutableMessage.builder()
                .withMessage(message.getMessage())
                .withId(message.getId())
                .withItemId(0)
                .withCreated(message.getCreated())
                .withMark(message.getSenderMark())
                .withName(message.getSenderName())
                .withSenderId(message.getSenderId())
                .withScore(0)
                .withThumbnail(null)
                .build();
    }

    public static Message of(FeedItem item, Comment comment) {
        return ImmutableMessage.builder()
                .withId((int) comment.getId())
                .withItemId((int) item.id())
                .withMessage(comment.getContent())
                .withSenderId(0)
                .withName(comment.getName())
                .withMark(comment.getMark())
                .withScore(comment.getUp() - comment.getDown())
                .withCreated(comment.getCreated())
                .withThumbnail(item.thumbnail())
                .build();
    }

    public static Message of(UserComments.User sender, UserComments.Comment comment) {
        return of(sender.getId(), sender.getName(), sender.getMark(), comment);
    }

    public static Message of(Info.User sender, UserComments.Comment comment) {
        return of(sender.getId(), sender.getName(), sender.getMark(), comment);
    }

    public static Message of(int senderId, String name, int mark, UserComments.Comment comment) {
        return ImmutableMessage.builder()
                .withId((int) comment.getId())
                .withCreated(comment.getCreated())
                .withScore(comment.getUp() - comment.getDown())
                .withItemId((int) comment.getItemId())
                .withMark(mark)
                .withName(name)
                .withSenderId(senderId)
                .withMessage(comment.getContent())
                .withThumbnail(comment.getThumb())
                .build();
    }
}
