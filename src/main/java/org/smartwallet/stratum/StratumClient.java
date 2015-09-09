package org.smartwallet.stratum;

import org.bitcoinj.core.Address;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.*;
import com.google.common.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by devrandom on 2015-Aug-25.
 */
public class StratumClient extends AbstractExecutionThreadService {
    public static final int SUBSCRIPTION_QUEUE_CAPACITY = 10;
    protected static Logger logger = LoggerFactory.getLogger("StratumClient");
    private static CycleDetectingLockFactory lockFactory = CycleDetectingLockFactory.newInstance(CycleDetectingLockFactory.Policies.DISABLED);
    protected final ObjectMapper mapper;
    private final ConcurrentMap<Long, SettableFuture<StratumMessage>> calls;
    private final ReentrantLock lock;
    private final ConcurrentMap<String, BlockingQueue<StratumMessage>> subscriptions;

    protected List<InetSocketAddress> serverAddresses;
    protected Socket socket;
    protected OutputStream outputStream;
    protected BufferedReader reader;
    private boolean isTls;
    private AtomicLong currentId;
    private Map<Address, Long> subscribedAddresses;
    private long subscribedHeaders = 0;

    public StratumClient(InetSocketAddress address, boolean isTls) {
        serverAddresses = Lists.newArrayList(address);
        mapper = new ObjectMapper();
        mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        currentId = new AtomicLong(1000);
        calls = Maps.newConcurrentMap();
        subscriptions = Maps.newConcurrentMap();
        lock = lockFactory.newReentrantLock("StratumClient-stream");
        this.isTls = isTls;
        subscribedAddresses = Maps.newConcurrentMap();
    }

    @Override
    protected Executor executor() {
        final ThreadFactory factory =
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                            @Override
                            public void uncaughtException(Thread t, Throwable e) {
                                logger.error("uncaught exception", e);
                            }
                        }).build();
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                Thread thread = factory.newThread(command);
                try {
                    thread.setName(serviceName());
                } catch (SecurityException e) {
                    // OK if we can't set the name in this environment.
                }
                thread.start();
            }
        };

    }

    static class TrustAllX509TrustManager implements X509TrustManager {
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(java.security.cert.X509Certificate[] certs,
                                       String authType) {
        }

        public void checkServerTrusted(java.security.cert.X509Certificate[] certs,
                                       String authType) {
        }
    }

    protected void createSocket() throws IOException {
        // TODO use random, exponentially backoff from failed connections
        InetSocketAddress address = serverAddresses.get(0);
        logger.info("Opening a socket to " + address.getHostName() + ":" + address.getPort());
        if (isTls) {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{new TrustAllX509TrustManager()}, new SecureRandom());
                SocketFactory factory = sc.getSocketFactory();
                socket = factory.createSocket();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                Throwables.propagate(e);
            }
        } else {
            socket = new Socket();
        }
        socket.connect(address); // TODO timeout
    }

    @Override
    protected void startUp() throws Exception {
        createSocket();
        outputStream = socket.getOutputStream();
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    @Override
    protected void triggerShutdown() {
        logger.info("trigger shutdown");
        try {
            socket.close();
        } catch (IOException e) {
            logger.error("failed to close socket", e);
        }
    }

    @Override
    protected void shutDown() {
        logger.info("shutdown");
        try {
            lock.lock();
            Exception e = new EOFException("shutting down");
            for (SettableFuture<StratumMessage> value : calls.values()) {
                value.setException(e);
            }
            for (BlockingQueue<StratumMessage> queue : subscriptions.values()) {
                try {
                    queue.put(StratumMessage.SENTINEL);
                } catch (InterruptedException e1) {
                    logger.warn("interrupted while trying to queue sentinel");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected void run() {
        lock.lock();

        Futures.addCallback(call("server.version", "StratumClient 0.1"), new FutureCallback<StratumMessage>() {
            @Override
            public void onSuccess(StratumMessage result) {
                logger.info("server version {}", result.result);
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("could not get server version");
            }
        });
        try {
            for (Map.Entry<Address, Long> entry : subscribedAddresses.entrySet()) {
                writeMessage("blockchain.address.subscribe", entry.getKey().toString(), entry.getValue());
            }

            if (subscribedHeaders > 0) {
                writeMessage("blockchain.address.subscribe", null, subscribedHeaders);
            }
        } finally {
            lock.unlock();
        }

        while (true) {
            String line;
            try {
                line = reader.readLine();
                logger.debug("< {}", line);
            } catch (IOException e) {
                handleFatal(e);
                return;
            }
            if (line == null) {
                handleFatal(new EOFException());
                return;
            }
            StratumMessage message;
            try {
                message = mapper.readValue(line, StratumMessage.class);
            } catch (IOException e) {
                handleFatal(e);
                return;
            }
            if (message.isResult())
                handleResult(message);
            else if (message.isMessage())
                handleMessage(message);
            else if (message.isError())
                handleError(message);
            else {
                logger.warn("unknown message type");
            }
        }
    }

    public ListenableFuture<StratumMessage> call(String method, String param) {
        return call(method, Lists.<Object>newArrayList(param));
    }
    
    public ListenableFuture<StratumMessage> call(String method, List<Object> params) {
        StratumMessage message = new StratumMessage(currentId.getAndIncrement(), method, params, mapper);
        try {
            lock.lock();
            if (!isRunning())
                return null;
            SettableFuture<StratumMessage> future = SettableFuture.create();
            calls.put(message.id, future);
            logger.debug("> {}", mapper.writeValueAsString(message));
            mapper.writeValue(outputStream, message);
            outputStream.write('\n');
            return future;
        } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
        } finally {
            lock.unlock();
        }
    }

    public StratumSubscription subscribe(Address address) {
        long id = currentId.getAndIncrement();
        subscribedAddresses.put(address, id);
        return subscribe("blockchain.address.subscribe", address.toString(), id);
    }

    public StratumSubscription subscribeToHeaders() {
        long id = currentId.getAndIncrement();
        subscribedHeaders = id;
        return subscribe("blockchain.headers.subscribe", null, id);
    }

    protected StratumSubscription subscribe(String method, String param, long id) {
        try {
            lock.lock();
            if (!subscriptions.containsKey(method)) {
                subscriptions.put(method, makeSubscriptionQueue());
            }
            SettableFuture<StratumMessage> future = SettableFuture.create();
            calls.put(id, future);
            if (isRunning())
                writeMessage(method, param, id);
            return new StratumSubscription(future, subscriptions.get(method));
        } finally {
            lock.unlock();
        }
    }

    private void writeMessage(String method, String param, long id) {
        ArrayList<Object> params = (param != null) ? Lists.<Object>newArrayList(param) : Lists.<Object>newArrayList();
        StratumMessage message = new StratumMessage(id, method, params, mapper);
        try {
            logger.info("> {}", mapper.writeValueAsString(message));
            mapper.writeValue(outputStream, message);
            outputStream.write('\n');
        } catch (IOException e) {
            logger.error("failed to write");
            // TODO close
        }
    }

    private ArrayBlockingQueue<StratumMessage> makeSubscriptionQueue() {
        return Queues.newArrayBlockingQueue(SUBSCRIPTION_QUEUE_CAPACITY);
    }

    protected void handleResult(StratumMessage message) {
        SettableFuture<StratumMessage> future = calls.remove(message.id);
        if (future == null) {
            logger.warn("reply for unknown id {}", message.id);
            return;
        }
        future.set(message);
    }

    protected void handleMessage(StratumMessage message) {
        if (!subscriptions.containsKey(message.method)) {
            logger.warn("message for unknown subscription {}", message.method);
            return;
        }
        try {
            subscriptions.get(message.method).put(message);
        } catch (InterruptedException e) {
            logger.warn("interrupted while handling message {}", message.method);
        }
    }

    private void handleError(StratumMessage message) {
        SettableFuture<StratumMessage> future = calls.remove(message.id);
        if (future == null) {
            logger.warn("reply for unknown id {}", message.id);
            return;
        }
        future.setException(new StratumException(message.error));
    }

    protected void handleFatal(Exception e) {
        logger.error("exception while connected", e);
        triggerShutdown();
    }
}
