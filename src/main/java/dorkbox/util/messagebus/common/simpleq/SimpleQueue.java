package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.lmax.disruptor.MessageHolder;



public class SimpleQueue {


    private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();

    private final Lock publisherLock = new ReentrantLock();
    private final Condition publisherNotifyCondition = this.publisherLock.newCondition();

    private final Lock consumerLock = new ReentrantLock();
    private final Condition consumerNotifyCondition = this.consumerLock.newCondition();

    private final AtomicReference<MessageHolder> consumer = new AtomicReference<MessageHolder>();
    private final AtomicReference<MessageHolder> producer = new AtomicReference<MessageHolder>();

    private volatile boolean waitingProducer = false;
    private final AtomicInteger availableThreads = new AtomicInteger();


    public SimpleQueue(int numberOfThreads) {
        this.availableThreads.set(numberOfThreads);
        this.producer.set(new MessageHolder());
    }

    public void transfer(Object message1) throws InterruptedException {
        MessageHolder holder = null;

        if ((holder = this.producer.getAndSet(null)) == null) {
            this.publisherLock.lock();
            try {
                while ((holder = this.producer.getAndSet(null)) == null) {
                    this.waitingProducer = true;
                    this.publisherNotifyCondition.await();
                }
            } finally {
                this.publisherLock.unlock();
            }
        }

        holder.message1 = message1;
        this.consumer.set(holder);

        this.consumerLock.lock();
        try {
            this.consumerNotifyCondition.signalAll();
        } finally {
            this.consumerLock.unlock();
        }
    }

    public boolean hasPendingMessages() {
        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub

    }

    public MessageHolder poll() {
        return this.consumer.getAndSet(null);
    }

    public MessageHolder take() throws InterruptedException {
        MessageHolder holder = null;

        this.consumerLock.lock();
        try {
            while ((holder = this.consumer.getAndSet(null)) == null) {
                this.consumerNotifyCondition.await();
            }
        } finally {
            this.consumerLock.unlock();
        }


        return holder;
    }

    // release the event back to the publisher
    // notify publisher in case pub was waiting
    public void release(MessageHolder holder) {
        this.producer.set(holder);

        if (this.waitingProducer) {
            this.publisherLock.lock();
            try {
                if (this.waitingProducer) {
                    this.waitingProducer = false;
                    this.publisherNotifyCondition.signalAll();
                }
            } finally {
                this.publisherLock.unlock();
            }
        }
    }
}