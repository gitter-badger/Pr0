package com.pr0gramm.app.api.pr0gramm.response;

import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.List;

/**
 */
@Value.Immutable
@Gson.TypeAdapters
public interface NewComment {
    long getCommentId();

    List<Comment> getComments();
}
