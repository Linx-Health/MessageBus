package net.engio.mbassy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.engio.mbassy.bus.AbstractPubSubSupport;
import net.engio.mbassy.bus.error.PublicationError;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 */
public class MBassador extends AbstractPubSubSupport implements IMessageBus {

    private final int numberOfMessageDispatchers;

    // all threads that are available for asynchronous message dispatching
    private final List<Thread> dispatchers;

    // all pending messages scheduled for asynchronous dispatch are queued here
    private final BlockingQueue<Object> pendingMessages = new LinkedBlockingQueue<Object>(Integer.MAX_VALUE / 16);

    protected static final ThreadFactory MessageDispatchThreadFactory = new ThreadFactory() {

        private final AtomicInteger threadID = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setDaemon(true);// do not prevent the JVM from exiting
            thread.setName("Dispatcher-" + this.threadID.getAndIncrement());
            return thread;
        }
    };


    public MBassador() {
        this(6);
    }

    public MBassador(int numberOfMessageDispatchers) {
        super();
        this.numberOfMessageDispatchers = numberOfMessageDispatchers;

        this.dispatchers = new ArrayList<Thread>(numberOfMessageDispatchers);
        initDispatcherThreads();
    }

    // initialize the dispatch workers
    private void initDispatcherThreads() {
        for (int i = 0; i < this.numberOfMessageDispatchers; i++) {
            // each thread will run forever and process incoming
            // message publication requests
            Thread dispatcher = MessageDispatchThreadFactory.newThread(new Runnable() {
                @Override
                public void run() {
                    Object message = null;
                    while (true) {
                        try {
                            message = MBassador.this.pendingMessages.take();
                            publishMessage(message);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable t) {
                            handlePublicationError(new PublicationError(t, "Error in asynchronous dispatch", message));
                        }
                    }
                }
            });
            dispatcher.setName("Message dispatcher");
            this.dispatchers.add(dispatcher);
            dispatcher.start();
        }
    }


    @Override
    public void publish(Object message) {
        try {
            publishMessage(message);
        } catch (Throwable e) {
            handlePublicationError(new PublicationError()
                    .setMessage("Error during publication of message")
                    .setCause(e)
                    .setPublishedObject(message));
        }
    }

    @Override
    public void publishAsync(Object message) {
        try {
            this.pendingMessages.put(message);
        } catch (InterruptedException e) {
            handlePublicationError(new PublicationError(e, "Error while adding an asynchronous message publication", message));
        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message) {
        try {
            this.pendingMessages.offer(message, timeout, unit);
        } catch (InterruptedException e) {
            handlePublicationError(new PublicationError(e, "Error while adding an asynchronous message publication", message));
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        shutdown();
    }

    @Override
    public void shutdown() {
        for (Thread dispatcher : this.dispatchers) {
            dispatcher.interrupt();
        }
    }

    @Override
    public boolean hasPendingMessages() {
        return this.pendingMessages.size() > 0;
    }
}