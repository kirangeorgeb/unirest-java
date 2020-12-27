package kong.unirest.java.multi;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

public final class PartSubscriber implements Flow.Subscriber<ByteBuffer> {

    static final ByteBuffer END_OF_PART = ByteBuffer.allocate(0);

    private final MultipartSubscription downstream; // for signalling
    private final ConcurrentLinkedQueue<ByteBuffer> buffers;
    private final Upstream upstream;
    private final Prefetcher prefetcher;

    PartSubscriber(MultipartSubscription downstream) {
        this.downstream = downstream;
        buffers = new ConcurrentLinkedQueue<>();
        upstream = new Upstream();
        prefetcher = new Prefetcher();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        requireNonNull(subscription);
        if (upstream.setOrCancel(subscription)) {
            // The only possible concurrent access to prefetcher applies here.
            // But the operation need not be atomic as other reads/writes
            // are done serially when ByteBuffers are polled, which is only
            // possible after this volatile write.
            prefetcher.initialize(upstream);
        }
    }

    @Override
    public void onNext(ByteBuffer item) {
        requireNonNull(item);
        buffers.offer(item);
        downstream.signal(false);
    }

    @Override
    public void onError(Throwable throwable) {
        requireNonNull(throwable);
        abortUpstream(false);
        downstream.signalError(throwable);
    }

    @Override
    public void onComplete() {
        abortUpstream(false);
        buffers.offer(END_OF_PART);
        downstream.signal(true); // force completion signal
    }

    void abortUpstream(boolean cancel) {
        if (cancel) {
            upstream.cancel();
        } else {
            upstream.clear();
        }
    }


    ByteBuffer pollNext() {
        ByteBuffer next = buffers.peek();
        if (next != null && next != END_OF_PART) {
            buffers.poll(); // remove
            prefetcher.update(upstream);
        }
        return next;
    }
}
