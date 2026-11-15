package io.pclnt.minecraft;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Byte counts for launcher-owned transfers and file streams, scoped to the task that opened them. */
public final class TaskIo {
    private static final InheritableThreadLocal<Counter> CURRENT = new InheritableThreadLocal<>();

    public static final class Counter {
        private final LongAdder received = new LongAdder(), read = new LongAdder(), written = new LongAdder();
        private final LongAdder transfers = new LongAdder();
        private volatile Transfer current;

        public long received() { return received.sum(); }
        public long read() { return read.sum(); }
        public long written() { return written.sum(); }
        public int activeTransfers() { return transfers.intValue(); }
        public String file() { return current == null ? "" : current.name; }
        public double fileProgress() {
            var transfer = current;
            return transfer == null || transfer.size <= 0 ? -1 : Math.min(1, transfer.bytes.sum() / (double) transfer.size);
        }
    }

    private record Transfer(String name, long size, LongAdder bytes) {
        Transfer(String name, long size) { this(name, size, new LongAdder()); }
    }

    private TaskIo() {}

    public static void run(Counter counter, Runnable work) {
        var previous = CURRENT.get();
        CURRENT.set(counter);
        try {
            work.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void currentFile(Path file) {
        var counter = CURRENT.get();
        if (counter != null) counter.current = new Transfer(file.getFileName().toString(), -1);
    }

    public static HttpResponse.BodyHandler<Path> download(Path staging, Path target, OpenOption... options) {
        return download(staging, target.getFileName().toString(), options);
    }

    public static HttpResponse.BodyHandler<Path> download(Path staging, String name, OpenOption... options) {
        var handler = options.length == 0 ? HttpResponse.BodyHandlers.ofFile(staging)
                : HttpResponse.BodyHandlers.ofFile(staging, options);
        var counter = CURRENT.get();
        if (counter == null) return handler;
        counter.current = new Transfer(name, -1);
        return info -> {
            var transfer = new Transfer(name,
                    info.headers().firstValueAsLong("Content-Length").orElse(-1));
            counter.current = transfer;
            var delegate = handler.apply(info);
            return new HttpResponse.BodySubscriber<Path>() {
                private final AtomicBoolean active = new AtomicBoolean();

                @Override public CompletionStage<Path> getBody() { return delegate.getBody(); }
                @Override public void onSubscribe(Flow.Subscription subscription) {
                    active.set(true);
                    counter.transfers.increment();
                    delegate.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long count) { subscription.request(count); }
                        @Override public void cancel() {
                            finish();
                            subscription.cancel();
                        }
                    });
                }
                @Override public void onError(Throwable error) {
                    finish();
                    delegate.onError(error);
                }
                @Override public void onComplete() {
                    finish();
                    delegate.onComplete();
                }
                @Override public void onNext(List<ByteBuffer> buffers) {
                    long count = 0;
                    for (var buffer : buffers) count += buffer.remaining();
                    counter.received.add(count);
                    delegate.onNext(buffers);
                    counter.written.add(count);
                    transfer.bytes.add(count);
                }

                private void finish() {
                    if (active.compareAndSet(true, false)) counter.transfers.decrement();
                }
            };
        };
    }

    public static InputStream read(InputStream stream) {
        var counter = CURRENT.get();
        if (counter == null) return stream;
        return new FilterInputStream(stream) {
            @Override public int read() throws IOException {
                int result = in.read();
                if (result >= 0) counter.read.increment();
                return result;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int result = in.read(bytes, offset, length);
                if (result > 0) counter.read.add(result);
                return result;
            }
        };
    }

    public static OutputStream write(OutputStream stream) {
        var counter = CURRENT.get();
        if (counter == null) return stream;
        return new FilterOutputStream(stream) {
            @Override public void write(int value) throws IOException {
                out.write(value);
                counter.written.increment();
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
                counter.written.add(length);
            }
        };
    }
}
