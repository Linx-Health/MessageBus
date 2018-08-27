/*
 * Copyright 2016 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messageBus.synchrony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.LockSupport;

import dorkbox.messageBus.dispatch.Dispatch;
import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.error.PublicationError;
import dorkbox.messageBus.synchrony.disruptor.MessageType;
import dorkbox.util.NamedThreadFactory;

/**
 * By default, it is the calling thread that has to get the subscriptions, which the sync/async logic then uses.
 *
 * The exception to this rule is when checking/calling DeadMessage publication.
 *
 * This is similar in behavior to the disruptor in that it does not generate garbage, however the downside of this implementation is it is
 * slow, but faster than other messagebus implementations.
 *
 * Basically, the disruptor is fast + noGC.
 *
 * @author dorkbox, llc Date: 2/3/16
 */
public final
class AsyncABQ_noGc implements Synchrony {

    private final ArrayBlockingQueue<MessageHolder> dispatchQueue;

    // have two queues to prevent garbage, So we pull off one queue to add to another queue and when done, we put it back
    private final ArrayBlockingQueue<MessageHolder> gcQueue;

    private final Collection<Thread> threads;
    private final Collection<Boolean> shutdown;
    private final ErrorHandler errorHandler;

    /**
     * Notifies the consumers during shutdown that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    public
    AsyncABQ_noGc(final int numberOfThreads, final ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;

        this.dispatchQueue = new ArrayBlockingQueue<MessageHolder>(1024);
        this.gcQueue = new ArrayBlockingQueue<MessageHolder>(1024);

        // this is how we prevent garbage
        for (int i = 0; i < 1024; i++) {
            gcQueue.add(new MessageHolder());
        }

        // each thread will run forever and process incoming message publication requests
        Runnable runnable = new Runnable() {
            @SuppressWarnings({"ConstantConditions", "UnnecessaryLocalVariable"})
            @Override
            public
            void run() {
                final ArrayBlockingQueue<MessageHolder> IN_QUEUE = AsyncABQ_noGc.this.dispatchQueue;
                final ArrayBlockingQueue<MessageHolder> OUT_QUEUE = AsyncABQ_noGc.this.gcQueue;

                final ErrorHandler errorHandler1 = errorHandler;

                while (!AsyncABQ_noGc.this.shuttingDown) {
                    process(IN_QUEUE, OUT_QUEUE, errorHandler1);
                }

                synchronized (shutdown) {
                    shutdown.add(Boolean.TRUE);
                }
            }
        };

        this.threads = new ArrayDeque<Thread>(numberOfThreads);
        this.shutdown = new ArrayList<Boolean>();

        final NamedThreadFactory threadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {
            Thread thread = threadFactory.newThread(runnable);
            this.threads.add(thread);
            thread.start();
        }
    }

    @SuppressWarnings("Duplicates")
    private
    void process(final ArrayBlockingQueue<MessageHolder> queue,
                 final ArrayBlockingQueue<MessageHolder> gcQueue, final ErrorHandler errorHandler) {

        MessageHolder event;

        int messageType = MessageType.ONE;
        Dispatch dispatch;
        Object message1 = null;
        Object message2 = null;
        Object message3 = null;

        try {
            event = queue.take();

            messageType = event.type;
            dispatch = event.dispatch;
            message1 = event.message1;
            message2 = event.message2;
            message3 = event.message3;

            gcQueue.put(event);

            switch (messageType) {
                case MessageType.ONE: {
                    dispatch.publish(message1);
                    return;
                }
                case MessageType.TWO: {
                    dispatch.publish(message1, message2);
                    return;
                }
                case MessageType.THREE: {
                    dispatch.publish(message1, message2, message3);
                    //noinspection UnnecessaryReturnStatement
                    return;
                }
            }
        } catch (InterruptedException e) {
            if (!this.shuttingDown) {
                switch (messageType) {
                    case MessageType.ONE: {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message1));
                        return;
                    }
                    case MessageType.TWO: {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message1, message2));
                        return;
                    }
                    case MessageType.THREE: {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message1, message2, message3));
                        //noinspection UnnecessaryReturnStatement
                        return;
                    }
                }
            }
        }
    }

    @Override
    public
    void publish(final Dispatch dispatch, final Object message1) {
        try {
            MessageHolder job = gcQueue.take();

            job.type = MessageType.ONE;
            job.dispatch = dispatch;

            job.message1 = message1;

            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1));
        }
    }

    @Override
    public
    void publish(final Dispatch dispatch, final Object message1, final Object message2) {
        try {
            MessageHolder job = gcQueue.take();

            job.type = MessageType.TWO;
            job.dispatch = dispatch;

            job.message1 = message1;
            job.message2 = message2;

            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }
    }

    @Override
    public
    void publish(final Dispatch dispatch, final Object message1, final Object message2, final Object message3) {
        try {
            MessageHolder job = gcQueue.take();

            job.type = MessageType.THREE;
            job.dispatch = dispatch;

            job.message1 = message1;
            job.message2 = message2;
            job.message3 = message3;

            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }

    @Override
    public
    boolean hasPendingMessages() {
        return !this.dispatchQueue.isEmpty();
    }

    @SuppressWarnings("Duplicates")
    @Override
    public
    void shutdown() {
        this.shuttingDown = true;

        for (Thread t : this.threads) {
            t.interrupt();
        }

        while (true) {
            synchronized (shutdown) {
                if (shutdown.size() == threads.size()) {
                    return;
                }
                LockSupport.parkNanos(100L); // wait 100ms for threads to quit
            }
        }
    }
}
