package org.openjproxy.grpc.server;

import com.google.common.util.concurrent.SettableFuture;
import com.openjproxy.grpc.LobDataBlock;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class LobDataBlocksInputStream extends InputStream {
    @Getter
    private final String uuid;
    private final List<LobDataBlock> blocksReceived;
    private final AtomicBoolean atomicFinished;
    private byte[] currentBlock;
    private int currentIdx;
    private SettableFuture<Boolean> blockArrived;
    @Getter
    private final AtomicBoolean fullyConsumed;

    // Instance-specific lock, NOT static!
    private final ReentrantLock lock = new ReentrantLock();

    public LobDataBlocksInputStream(LobDataBlock firstBlock) {
        this.uuid = UUID.randomUUID().toString();
        this.fullyConsumed = new AtomicBoolean(false);
        this.blocksReceived = new ArrayList<>();
        this.currentBlock = firstBlock.getData().toByteArray();
        this.atomicFinished = new AtomicBoolean(false);
        this.blockArrived = SettableFuture.create();
        this.currentIdx = -1;
        this.blockArrived.set(true);
        log.info("{} lob created", this.uuid);
    }

    @SneakyThrows
    @Override
    public int read() {
        log.debug("Reading lob {}", this.uuid);

        // Fast path - still bytes left in current block
        if (this.currentIdx < this.currentBlock.length - 1) {
            int ret = this.currentBlock[++currentIdx];
            return ret & 0xFF;
        }

        while (true) {
            LobDataBlock nextBlock = null;

            // Atomic check AND remove under single lock
            lock.lock();
            try {
                if (!this.blocksReceived.isEmpty()) {
                    nextBlock = this.blocksReceived.remove(0);
                }
            } finally {
                lock.unlock();
            }

            if (nextBlock != null) {
                this.currentBlock = nextBlock.getData().toByteArray();
                this.currentIdx = -1;
                log.debug("Next block positioned for reading");

                int ret = this.currentBlock[++currentIdx];
                return ret & 0xFF;
            }

            // No blocks available
            log.debug("No new blocks received, will wait for block to arrive if stream not finished");

            // Check finished flag first
            if (this.atomicFinished.get()) {
                log.debug("All blocks exhausted, finishing byte stream. lob {}", this.uuid);
                this.fullyConsumed.set(true);
                return -1;
            }

            // Get current future reference
            SettableFuture<Boolean> futureToWait;
            lock.lock();
            try {
                futureToWait = this.blockArrived;
            } finally {
                lock.unlock();
            }

            try {
                // Wait with timeout
                futureToWait.get(5, TimeUnit.SECONDS);
                log.debug("New block arrived signal received");
            } catch (TimeoutException e) {
                // Double-check if finished while we were waiting
                if (this.atomicFinished.get()) {
                    log.debug("Timed out waiting for new block, stream finished, lob {}", this.uuid);
                    this.fullyConsumed.set(true);
                    return -1;
                }
                // Timeout but not finished - continue loop
                log.debug("Timeout waiting for block, but stream not finished, continuing");
                continue;
            }

            // Reset the future atomically with queue check to avoid race conditions
            lock.lock();
            try {
                // Double-check queue after getting signal (avoid race condition)
                if (!this.blocksReceived.isEmpty()) {
                    continue; // Go back to process the block
                }

                // No blocks yet, reset future for next signal
                if (this.blockArrived == futureToWait) {
                    this.blockArrived = SettableFuture.create();
                }
            } finally {
                lock.unlock();
            }

            // Go back and check queue again
        }
    }

    public void addBlock(LobDataBlock lobDataBlock) {
        lock.lock();
        try {
            this.blocksReceived.add(lobDataBlock);
            this.blockArrived.set(true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Indicate that it finished receiving blocks not that if finished being read.
     *
     * @param finished
     */
    public void finish(boolean finished) {
        log.debug("Finished receiving blocks");
        atomicFinished.set(finished);

        // Also signal any waiting readers
        lock.lock();
        try {
            this.blockArrived.set(true);
        } finally {
            lock.unlock();
        }
    }
}
