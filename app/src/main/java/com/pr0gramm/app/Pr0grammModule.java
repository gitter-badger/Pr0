package com.pr0gramm.app;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.pr0gramm.app.api.Api;
import com.pr0gramm.app.api.InstantDeserializer;
import com.squareup.picasso.OkHttpDownloader;
import com.squareup.picasso.Picasso;

import org.joda.time.Instant;

import java.io.File;

import retrofit.RestAdapter;
import retrofit.converter.GsonConverter;

/**
 */
@SuppressWarnings("UnusedDeclaration")
public class Pr0grammModule extends AbstractModule {
    @Override
    protected void configure() {

    }

    @Provides
    @Singleton
    public Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(Instant.class, new InstantDeserializer())
                .create();
    }

    @Provides
    @Singleton
    public RestAdapter restAdapter(Gson gson) {
        return new RestAdapter.Builder()
                .setEndpoint("http://pr0gramm.com")
                .setConverter(new GsonConverter(gson))
                .setLogLevel(RestAdapter.LogLevel.HEADERS_AND_ARGS)
                .build();
    }

    @Provides
    @Singleton
    public Picasso picasso(Context context) {
        File cache = new File(context.getCacheDir(), "imgCache");

        return new Picasso.Builder(context)
                .downloader(new OkHttpDownloader(cache))
                .loggingEnabled(true)
                .indicatorsEnabled(true)
                .build();
    }

    @Provides
    @Singleton
    public Api api(RestAdapter restAdapter) {
        return restAdapter.create(Api.class);
    }
}