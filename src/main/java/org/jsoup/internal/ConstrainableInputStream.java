package org.jsoup.internal;

import org.jsoup.helper.DataUtil;
import org.jsoup.helper.Validate;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * A jsoup internal class (so don't use it as there is no contract API) that enables constraints on an Input Stream,
 * namely a maximum read size, and the ability to Thread.interrupt() the read.
 * @deprecated use {@link ControllableInputStream} instead (but don't use that either, because this is jsoup internal!)
 */
@Deprecated
public final class ConstrainableInputStream extends BufferedInputStream {
    private final boolean capped;
    private final int maxSize;
    private long startTime;
    private long timeout = 0; // optional max time of request
    private int remaining;
    private boolean interrupted;

    private ConstrainableInputStream(InputStream in, int bufferSize, int maxSize) {
        super(in, bufferSize);
        Validate.isTrue(maxSize >= 0);
        this.maxSize = maxSize;
        remaining = maxSize;
        capped = maxSize != 0;
        startTime = System.nanoTime();
    }

    /**
     * If this InputStream is not already a ConstrainableInputStream, let it be one.
     * @param in the input stream to (maybe) wrap
     * @param bufferSize the buffer size to use when reading
     * @param maxSize the maximum size to allow to be read. 0 == infinite.
     * @return a constrainable input stream
     */
    public static ConstrainableInputStream wrap(InputStream in, int bufferSize, int maxSize) {
        return in instanceof ConstrainableInputStream
            ? (ConstrainableInputStream) in
            : new ConstrainableInputStream(in, bufferSize, maxSize);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (interrupted || capped && remaining <= 0)
            return -1;
        if (Thread.currentThread().isInterrupted()) {
            // interrupted latches, because parse() may call twice
            interrupted = true;
            return -1;
        }
        if (expired())
            throw new SocketTimeoutException("Read timeout");

        if (capped && len > remaining)
            len = remaining; // don't read more than desired, even if available

        try {
            final int read = super.read(b, off, len);
            remaining -= read;
            return read;
        } catch (SocketTimeoutException e) {
            return 0;
        }
    }

    /**
     * Reads this inputstream to a ByteBuffer. The supplied max may be less than the inputstream's max, to support
     * reading just the first bytes.
     */
    public ByteBuffer readToByteBuffer(int max) throws IOException {
        return DataUtil.readToByteBuffer(this, max);
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        remaining = maxSize - markpos;
    }

    public ConstrainableInputStream timeout(long startTimeNanos, long timeoutMillis) {
        this.startTime = startTimeNanos;
        this.timeout = timeoutMillis * 1000000;
        return this;
    }

    private boolean expired() {
        if (timeout == 0)
            return false;

        final long now = System.nanoTime();
        final long dur = now - startTime;
        return (dur > timeout);
    }
}
