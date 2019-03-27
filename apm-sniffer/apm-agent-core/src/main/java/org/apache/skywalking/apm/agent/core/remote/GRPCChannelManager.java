/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.agent.core.remote;

import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;

/**
 * @author wusheng, zhang xin
 */
@DefaultImplementor
public class GRPCChannelManager implements BootService, Runnable {
    private static final ILog logger = LogManager.getLogger(GRPCChannelManager.class);

    private volatile GRPCChannel managedChannel = null;
    private volatile ScheduledFuture<?> connectCheckFuture;
    private volatile boolean reconnect = true;
    private Random random = new Random();
    private List<GRPCChannelListener> listeners = Collections.synchronizedList(new LinkedList<GRPCChannelListener>());
    private volatile int selectedIdx = -1;

    @Override
    public void prepare() throws Throwable {

    }

    @Override
    public void boot() throws Throwable {
        connectCheckFuture = Executors
            .newSingleThreadScheduledExecutor(new DefaultNamedThreadFactory("GRPCChannelManager"))
            .scheduleAtFixedRate(new RunnableWithExceptionProtection(this, new RunnableWithExceptionProtection.CallbackWhenException() {
                @Override
                public void handle(Throwable t) {
                    logger.error("unexpected exception.", t);
                }
            }), 0, Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    @Override
    public void onComplete() throws Throwable {

    }

    @Override
    public void shutdown() throws Throwable {
        connectCheckFuture.cancel(true);
        if (managedChannel != null) {
            managedChannel.shutdownNow();
        }
        logger.debug("Selected collector grpc service shutdown.");
    }

    @Override
    public void run() {
        logger.debug("Selected collector grpc service running, reconnect:{}.", reconnect);
        if (reconnect) {
            if (RemoteDownstreamConfig.Collector.GRPC_SERVERS.size() > 0) {
                String server = "";
                try {
                    int index = Math.abs(random.nextInt()) % RemoteDownstreamConfig.Collector.GRPC_SERVERS.size();
                    if (index != selectedIdx) {
                        selectedIdx = index;

                        server = RemoteDownstreamConfig.Collector.GRPC_SERVERS.get(index);
                        String[] ipAndPort = server.split(":");

                        if (managedChannel != null) {
                            managedChannel.shutdownNow();
                        }

                        managedChannel = GRPCChannel.newBuilder(ipAndPort[0], Integer.parseInt(ipAndPort[1]))
                                .addManagedChannelBuilder(new StandardChannelBuilder())
                                .addManagedChannelBuilder(new TLSChannelBuilder())
                                .addChannelDecorator(new AuthenticationDecorator())
                                .build();

                        notify(GRPCChannelStatus.CONNECTED);
                    }

                    reconnect = false;
                    return;
                } catch (Throwable t) {
                    logger.error(t, "Create channel to {} fail.", server);
                }
            }

            logger.debug("Selected collector grpc service is not available. Wait {} seconds to retry", Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL);
        }
    }

    public void addChannelListener(GRPCChannelListener listener) {
        listeners.add(listener);
    }

    public Channel getChannel() {
        return managedChannel.getChannel();
    }

    /**
     * If the given expcetion is triggered by network problem, connect in background.
     *
     * @param throwable
     */
    public void reportError(Throwable throwable) {
        if (isNetworkError(throwable)) {
            reconnect = true;
        }
    }

    private void notify(GRPCChannelStatus status) {
        for (GRPCChannelListener listener : listeners) {
            try {
                listener.statusChanged(status);
            } catch (Throwable t) {
                logger.error(t, "Fail to notify {} about channel connected.", listener.getClass().getName());
            }
        }
    }

    private boolean isNetworkError(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException) {
            StatusRuntimeException statusRuntimeException = (StatusRuntimeException)throwable;
            return statusEquals(statusRuntimeException.getStatus(),
                    Status.UNAVAILABLE,
                    Status.PERMISSION_DENIED,
                    Status.UNAUTHENTICATED,
                    Status.RESOURCE_EXHAUSTED,
                    Status.UNKNOWN
            );
        }
        return false;
    }

    private boolean statusEquals(Status sourceStatus, Status... potentialStatus) {
        for (Status status : potentialStatus) {
            if (sourceStatus.getCode() == status.getCode()) {
                return true;
            }
        }
        return false;
    }
}
