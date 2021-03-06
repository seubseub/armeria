/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;

final class DecodedHttpRequest extends DefaultHttpRequest {

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final boolean keepAlive;
    private final InboundTrafficController inboundTrafficController;
    private final long defaultMaxRequestLength;
    @Nullable
    private ServiceRequestContext ctx;
    private long transferredBytes;

    @Nullable
    private HttpResponse response;
    private boolean isResponseAborted;

    DecodedHttpRequest(EventLoop eventLoop, int id, int streamId, HttpHeaders headers, boolean keepAlive,
                       InboundTrafficController inboundTrafficController, long defaultMaxRequestLength) {

        super(headers);

        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
        this.inboundTrafficController = inboundTrafficController;
        this.defaultMaxRequestLength = defaultMaxRequestLength;
    }

    void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
        ctx.logBuilder().requestHeaders(headers());

        // For the server, request headers are processed well before ServiceRequestContext is created. It means
        // there is some delay between the actual channel read and this logging, but it's the best we can do for
        // now.
        ctx.logBuilder().requestFirstBytesTransferred();
    }

    int id() {
        return id;
    }

    int streamId() {
        return streamId;
    }

    /**
     * Returns whether to keep the connection alive after this request is handled.
     */
    boolean isKeepAlive() {
        return keepAlive;
    }

    long maxRequestLength() {
        return ctx != null ? ctx.maxRequestLength() : defaultMaxRequestLength;
    }

    long transferredBytes() {
        return transferredBytes;
    }

    void increaseTransferredBytes(long delta) {
        if (transferredBytes > Long.MAX_VALUE - delta) {
            transferredBytes = Long.MAX_VALUE;
        } else {
            transferredBytes += delta;
        }
    }

    @Override
    protected EventLoop defaultSubscriberExecutor() {
        return eventLoop;
    }

    @Override
    public boolean tryWrite(HttpObject obj) {
        final boolean published = super.tryWrite(obj);
        if (published && obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            inboundTrafficController.inc(length);
            assert ctx != null : "uninitialized DecodedHttpRequest must be aborted.";
            ctx.logBuilder().requestLength(transferredBytes);
        }
        return published;
    }

    @Override
    protected void onRemoval(HttpObject obj) {
        if (obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            inboundTrafficController.dec(length);
        }
    }

    /**
     * Sets the specified {@link HttpResponse} which responds to this request. This is always called
     * by the {@link HttpServerHandler} after the handler gets the {@link HttpResponse} from a {@link Service}.
     */
    void setResponse(HttpResponse response) {
        if (isResponseAborted) {
            // This means that we already tried to close the request, so abort the response immediately.
            if (!response.isComplete()) {
                response.abort();
            }
        } else {
            this.response = response;
        }
    }

    /**
     * Aborts the {@link HttpResponse} which responds to this request if it exists.
     *
     * @see Http2RequestDecoder#onRstStreamRead(ChannelHandlerContext, int, long)
     */
    void abortResponse(Throwable cause) {
        isResponseAborted = true;
        // Try to close the request first, then abort the response if it is already closed.
        if (!tryClose(cause) &&
            response != null && !response.isComplete()) {
            response.abort();
        }
    }
}
