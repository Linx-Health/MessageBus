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
package dorkbox.util.messagebus.synchrony;

import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.Sequencer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkProcessor;
import dorkbox.util.messagebus.common.NamedThreadFactory;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.publication.Publisher;
import dorkbox.util.messagebus.synchrony.disruptor.EventBusFactory;
import dorkbox.util.messagebus.synchrony.disruptor.MessageHandler;
import dorkbox.util.messagebus.synchrony.disruptor.MessageType;
import dorkbox.util.messagebus.synchrony.disruptor.PublicationExceptionHandler;
import dorkbox.util.messagebus.subscription.Subscription;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * @author dorkbox, llc Date: 2/3/16
 */
public
class AsyncDisruptor implements Synchrony {

    private final ErrorHandlingSupport errorHandler;
    private WorkProcessor[] workProcessors;
    private MessageHandler[] handlers;
    private RingBuffer<MessageHolder> ringBuffer;
    private Sequence workSequence;

    public
    AsyncDisruptor(final int numberOfThreads, final ErrorHandlingSupport errorHandler, final Publisher publisher, final Synchrony syncPublication) {
        this.errorHandler = errorHandler;
        // Now we setup the disruptor and work handlers

        ExecutorService executor = new ThreadPoolExecutor(numberOfThreads, numberOfThreads,
                                                          0, TimeUnit.NANOSECONDS, // handlers are never idle, so this doesn't matter
                                                          new java.util.concurrent.LinkedTransferQueue<Runnable>(),
                                                          new NamedThreadFactory("MessageBus"));

        final PublicationExceptionHandler<MessageHolder> exceptionHandler = new PublicationExceptionHandler<MessageHolder>(errorHandler);
        EventBusFactory factory = new EventBusFactory();

        // setup the work handlers
        handlers = new MessageHandler[numberOfThreads];
        for (int i = 0; i < handlers.length; i++) {
            handlers[i] = new MessageHandler(publisher, syncPublication);  // exactly one per thread is used
        }


//        final int BUFFER_SIZE = ringBufferSize * 64;
//        final int BUFFER_SIZE = 1024 * 64;
//        final int BUFFER_SIZE = 1024;
        final int BUFFER_SIZE = 32;
//        final int BUFFER_SIZE = 16;
//        final int BUFFER_SIZE = 8;
//        final int BUFFER_SIZE = 4;


        WaitStrategy consumerWaitStrategy;
//        consumerWaitStrategy = new LiteBlockingWaitStrategy(); // good one
//        consumerWaitStrategy = new BlockingWaitStrategy();
//        consumerWaitStrategy = new YieldingWaitStrategy();
//        consumerWaitStrategy = new BusySpinWaitStrategy();
//        consumerWaitStrategy = new SleepingWaitStrategy();
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new SleepingWaitStrategy(0));
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new BlockingWaitStrategy());
        consumerWaitStrategy = new PhasedBackoffWaitStrategy(2, 5, TimeUnit.MILLISECONDS, new LiteBlockingWaitStrategy());


        ringBuffer = RingBuffer.createMultiProducer(factory, BUFFER_SIZE, consumerWaitStrategy);
        SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();


        // setup the WorkProcessors (these consume from the ring buffer -- one at a time) and tell the "handler" to execute the item
        workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

        final int numWorkers = handlers.length;
        workProcessors = new WorkProcessor[numWorkers];

        for (int i = 0; i < numWorkers; i++) {
            workProcessors[i] = new WorkProcessor<MessageHolder>(ringBuffer,
                                                                 sequenceBarrier,
                                                                 handlers[i],
                                                                 exceptionHandler, workSequence);
        }

        // setup the WorkProcessor sequences (control what is consumed from the ring buffer)
        final Sequence[] sequences = getSequences();
        ringBuffer.addGatingSequences(sequences);


        // configure the start position for the WorkProcessors, and start them
        final long cursor = ringBuffer.getCursor();
        workSequence.set(cursor);

        for (WorkProcessor<?> processor : workProcessors) {
            processor.getSequence()
                     .set(cursor);
            executor.execute(processor);
        }
    }


    public
    void publish(final Subscription[] subscriptions, final Object message1) throws Throwable {
        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);
        job.type = MessageType.ONE;
        job.subscriptions = subscriptions;
        job.message1 = message1;

        ringBuffer.publish(seq);
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2) throws Throwable  {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2, final Object message3) throws Throwable  {

    }

    // gets the sequences used for processing work
    private
    Sequence[] getSequences() {
        final Sequence[] sequences = new Sequence[workProcessors.length + 1];
        for (int i = 0, size = workProcessors.length; i < size; i++) {
            sequences[i] = workProcessors[i].getSequence();
        }
        sequences[sequences.length - 1] = workSequence;   // always add the work sequence
        return sequences;
    }


    public
    void start() {
    }

    public
    void shutdown() {
        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }

        for (MessageHandler handler : handlers) {
            while (!handler.isShutdown()) {
                LockSupport.parkNanos(100L); // wait 100ms for handlers to quit
            }
        }
    }

    public
    boolean hasPendingMessages() {
        // from workerPool.drainAndHalt()
        Sequence[] workerSequences = getSequences();
        final long cursor = ringBuffer.getCursor();
        for (Sequence s : workerSequences) {
            if (cursor > s.get()) {
                return true;
            }
        }

        return false;
    }
}
