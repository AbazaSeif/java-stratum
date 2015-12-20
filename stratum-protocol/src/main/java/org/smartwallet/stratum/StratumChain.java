package org.smartwallet.stratum;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.bitcoinj.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.*;

/**
 * Created by devrandom on 2015-Nov-08.
 */
public class StratumChain extends AbstractExecutionThreadService {
    public static final int MAX_REORG = 16384;
    protected static Logger log = LoggerFactory.getLogger("StratumChain");
    private HeadersStore store;
    private BlockingQueue<StratumMessage> queue;
    private final NetworkParameters params;
    private final StratumClient client;
    private final CopyOnWriteArrayList<Listener> listeners;
    private long peerHeight;
    static ThreadFactory threadFactory =
            new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(Thread t, Throwable e) {
                            log.error("uncaught exception", e);
                        }
                    }).build();

    public void addChainListener(Listener listener) {
        listeners.add(listener);
    }

    public interface Listener {
        void onHeight(long height, Block block, boolean isSynced);
    }

    public StratumChain(NetworkParameters params, HeadersStore store, StratumClient client) {
        this.params = params;
        this.client = client;
        this.store = store;
        listeners = new CopyOnWriteArrayList<>();
    }

    public void close() {
        stopAsync();
        awaitTerminated();
    }

    public long getPeerHeight() {
        return peerHeight;
    }

    @Override
    protected void startUp() throws Exception {
        queue = client.getHeadersQueue();
        super.startUp();
    }

    @Override
    protected void shutDown() throws Exception {
        store.close();
        store = null;
    }

    @Override
    protected void triggerShutdown() {
        queue.offer(StratumMessage.SENTINEL);
    }

    @Override
    protected void run() throws Exception {
        store.verifyLast();
        client.subscribeToHeaders();

        while (true) {
            StratumMessage item = queue.take();
            if (item.isSentinel()) {
                log.info("sentinel on queue, exiting");
                return;
            }
            JsonNode result = item.result != null ? item.result : item.params.get(0);
            long height = result.get("block_height").longValue();
            peerHeight = height;
            Block block = makeBlock(result);
            log.info("block {} @{}", height, block.getTime());
            try {
                if (download(height - 1) && store.getHeight() == height - 1) {
                    add(block);
                }
                notifyHeight(true);
            } catch (CancellationException | ExecutionException e) {
                log.error("failed to download chain at height {}", height - 1);
                // Will retry on next time we get a message
            }
            log.info("store is at height {}", store.getHeight());
        }
    }

    private void notifyHeight(boolean isSynced) {
        for (Listener listener : listeners) {
            listener.onHeight(store.getHeight(), store.top(), isSynced);
        }
    }

    boolean add(Block block) throws ExecutionException, InterruptedException {
        if (!store.add(block)) {
            reorg();
            return false;
        }
        return true;
    }

    void reorg() throws ExecutionException, InterruptedException {
        long storeHeight = store.getHeight();

        // Find a spot in our local store where the block connects to the block we get from the server.
        // Exponential jumps (1,2,4,8...)
        for (int i = 1 ; i <= MAX_REORG && i <= storeHeight; i += i) {
            Block storePrev = store.get(storeHeight - i);
            log.info("reorg to height {} our prev {}", storeHeight - i + 1, storePrev.getHash());
            ListenableFuture<StratumMessage> future =
                    client.call("blockchain.block.get_header", storeHeight - i + 1);
            StratumMessage item = future.get();
            Block block = makeBlock(item.result);
            if (block.getPrevBlockHash().equals(storePrev.getHash())) {
                // Found the spot.  Truncate blocks beyond it, and add the block from the server.
                store.truncate(storeHeight - i);
                if (!store.add(block))
                    throw new IllegalStateException("could not add block during reorg");
                return;
            }
        }
        // TODO limit reorg to previous checkpoint
        throw new RuntimeException("could not find a reorg point within " + MAX_REORG + " blocks");
    }

    private boolean download(long height) throws InterruptedException, CancellationException, ExecutionException {
        while (height > store.getHeight() + 50) {
            long index = (store.getHeight() + 1) / NetworkParameters.INTERVAL;
            log.info("at chunk height {}", index * NetworkParameters.INTERVAL);
            ListenableFuture<StratumMessage> future = client.call("blockchain.block.get_chunk", index);
            StratumMessage item = future.get();
            byte[] data = Utils.HEX.decode(item.result.asText());
            int num = data.length / Block.HEADER_SIZE;
            log.info("chunk size {}", num);
            int start = (int) (store.getHeight() + 1) % NetworkParameters.INTERVAL;
            for (int i = start ; i < num ; i++) {
                Block block = new Block(params, Arrays.copyOfRange(data, i * Block.HEADER_SIZE, (i+1) * Block.HEADER_SIZE));
                if (!add(block))
                    break; // Had a reorg, add one by one at new height
            }
            notifyHeight(false);
        }
        while (height > store.getHeight()) {
            log.info("adding block, store height={}", store.getHeight());
            ListenableFuture<StratumMessage> future =
                    client.call("blockchain.block.get_header", store.getHeight() + 1);
            StratumMessage item = future.get();
            if (item.result == null) {
                log.warn("no block at height {}", store.getHeight() + 1);
                return false;
            }
            Block block = makeBlock(item.result);
            add(block);
            notifyHeight(false);
        }
        return true;
    }

    private Block makeBlock(JsonNode result) {
        long timestamp = result.get("timestamp").longValue();
        long nonce = result.get("nonce").longValue();
        long difficultyTarget = result.get("bits").longValue();
        long version = result.get("version").longValue();
        Sha256Hash merkle = Sha256Hash.wrap(result.get("merkle_root").asText());
        Sha256Hash prevHash = Sha256Hash.wrap(result.get("prev_block_hash").asText());
        return new Block(params, version, prevHash, merkle, timestamp, difficultyTarget, nonce, Lists.<Transaction>newArrayList()).cloneAsHeader();
    }

    @Override
    protected Executor executor() {
        return makeExecutor(serviceName());
    }

    private static Executor makeExecutor(final String name) {
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                Thread thread = threadFactory.newThread(command);
                try {
                    thread.setName(name);
                } catch (SecurityException e) {
                    // OK if we can't set the name in this environment.
                }
                thread.start();
            }
        };
    }
}
