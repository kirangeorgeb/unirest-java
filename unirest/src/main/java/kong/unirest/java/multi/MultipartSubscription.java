package kong.unirest.java.multi;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.List;
import java.util.concurrent.Flow;

import static java.nio.charset.StandardCharsets.UTF_8;

final class MultipartSubscription extends AbstractSubscription<ByteBuffer> {

    private static final VarHandle PART_SUBSCRIBER;

    static {
        try {
            PART_SUBSCRIBER =
                    MethodHandles.lookup()
                            .findVarHandle(MultipartSubscription.class, "partSubscriber", Flow.Subscriber.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // A tombstone to protect against race conditions that would otherwise occur if a
    // thread tries to abort() while another tries to nextPartHeaders(), which might lead
    // to a newly subscribed part being missed by abort().
    private static final Flow.Subscriber<ByteBuffer> CANCELLED_SUBSCRIBER =
            new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) {}
                @Override public void onNext(ByteBuffer item) {}
                @Override public void onError(Throwable throwable) {}
                @Override public void onComplete() {}
            };

    private final String boundary;
    private final List<MultipartBodyPublisher.Part> parts;
    private volatile Flow.Subscriber<ByteBuffer> partSubscriber;
    private int partIndex;
    private boolean complete;

    MultipartSubscription(
            MultipartBodyPublisher upstream, Flow.Subscriber<? super ByteBuffer> downstream) {
        super(downstream, MultipartBodyPublisher.SYNC_EXECUTOR);
        boundary = upstream.boundary();
        parts = upstream.parts();
    }

    @Override
    protected long emit(Flow.Subscriber<? super ByteBuffer> downstream, long emit) {
        long submitted = 0L;
        while (true) {
            ByteBuffer batch;
            if (complete) {
                cancelOnComplete(downstream);
                return 0;
            } else if (submitted >= emit
                    || (batch = pollNext()) == null) { // exhausted demand or batches
                return submitted;
            } else if (submitOnNext(downstream, batch)) {
                submitted++;
            } else {
                return 0;
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void abort(boolean flowInterrupted) {
        Flow.Subscriber<ByteBuffer> previous =
                (Flow.Subscriber<ByteBuffer>) PART_SUBSCRIBER.getAndSet(this, CANCELLED_SUBSCRIBER);
        if (previous instanceof PartSubscriber) {
            ((PartSubscriber) previous).abortUpstream(flowInterrupted);
        }
    }

    private ByteBuffer pollNext() {
        Flow.Subscriber<ByteBuffer> subscriber = partSubscriber;
        if (subscriber instanceof PartSubscriber) { // not cancelled & not null
            ByteBuffer next = ((PartSubscriber) subscriber).pollNext();
            if (next != PartSubscriber.END_OF_PART) {
                return next;
            }
        }
        return subscriber != CANCELLED_SUBSCRIBER ? nextPartHeaders() : null;
    }

    private ByteBuffer nextPartHeaders() {
        StringBuilder heading = new StringBuilder();
        BoundaryAppender.get(partIndex, parts.size()).append(heading, boundary);
        if (partIndex < parts.size()) {
            MultipartBodyPublisher.Part part = parts.get(partIndex++);
            if (!subscribeToPart(part)) {
                return null;
            }
            MultipartBodyPublisher.appendPartHeaders(heading, part);
            heading.append("\r\n");
        } else {
            partSubscriber = CANCELLED_SUBSCRIBER; // race against abort() here is OK
            complete = true;
        }
        return UTF_8.encode(CharBuffer.wrap(heading));
    }

    private boolean subscribeToPart(MultipartBodyPublisher.Part part) {
        PartSubscriber subscriber = new PartSubscriber(this);
        Flow.Subscriber<ByteBuffer> current = partSubscriber;
        if (current != CANCELLED_SUBSCRIBER && PART_SUBSCRIBER.compareAndSet(this, current, subscriber)) {
            part.bodyPublisher().subscribe(subscriber);
            return true;
        }
        return false;
    }
}
