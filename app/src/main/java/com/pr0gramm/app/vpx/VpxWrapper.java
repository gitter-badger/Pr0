package com.pr0gramm.app.vpx;

import android.content.Context;
import android.graphics.Bitmap;

import com.getkeepsafe.relinker.ReLinker;
import com.google.common.base.Stopwatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkState;

/**
 */
class VpxWrapper implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger("VpxWrapper");

    private final long vpx;
    private boolean closed;

    private VpxWrapper() {
        this.vpx = vpxNewWrapper();
    }

    public void put(byte[] data, int offset, int length) {
        checkValid();
        vpxPutData(vpx, data, offset, length);
    }

    /**
     * Convenience method to push data directly from buffer.
     */
    public final void put(ByteBuffer data) {
        int offset = data.arrayOffset() + data.position();
        int length = data.remaining();
        put(data.array(), offset, length);
        data.position(data.limit());
    }

    public boolean get(Bitmap bitmap, int pixelSkip) {
        checkValid();
        return vpxGetFrame(vpx, bitmap, pixelSkip);
    }

    private void checkValid() {
        checkState(!closed, "vpx wrapper is already closed");
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            vpxFreeWrapper(vpx);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    public static VpxWrapper newInstance(Context context) {
        loadNativeLibrary(context);
        checkState(hasNativeLibrary, "Native library not loaded. Software decoder is not available");

        return new VpxWrapper();
    }

    public static boolean isAvailable() {
        return hasNativeLibrary;
    }

    private static native String getVpxString();

    private native long vpxNewWrapper();

    private native void vpxFreeWrapper(long vpx);

    private native void vpxPutData(long vpx, byte[] data, int offset, int length);

    private native boolean vpxGetFrame(long vpx, Bitmap bitmap, int pixelSkip);

    private static boolean hasNativeLibrary;
    private static boolean initialized;

    public static synchronized void loadNativeLibrary(Context context) {
        if (initialized)
            return;

        initialized = true;
        try {
            logger.info("Loading library now");

            Stopwatch watch = Stopwatch.createStarted();
            ReLinker.loadLibrary(context, "vpx-wrapper");
            hasNativeLibrary = true;

            logger.info("Native library loaded in {}", watch);
            logger.info("  vpx version: {}", getVpxString());

        } catch (Throwable error) {
            logger.warn("Could not load library", error);
        }
    }
}
