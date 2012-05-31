/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.marshalling;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.VoidEnum;

import java.io.ObjectStreamConstants;

import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.Unmarshaller;

/**
 * {@link ReplayingDecoder} which use an {@link Unmarshaller} to read the Object out of the {@link ChannelBuffer}.
 *
 * If you can you should use {@link MarshallingDecoder}.
 */
public class CompatibleMarshallingDecoder extends ReplayingDecoder<Object, VoidEnum> {
    protected final UnmarshallerProvider provider;
    protected final int maxObjectSize;

    /**
     * Create a new instance of {@link CompatibleMarshallingDecoder}.
     *
     * @param provider      the {@link UnmarshallerProvider} which is used to obtain the {@link Unmarshaller} for the {@link Channel}
     * @param maxObjectSize the maximal size (in bytes) of the {@link Object} to unmarshal. Once the size is exceeded
     *                      the {@link Channel} will get closed. Use a a maxObjectSize of {@link Integer#MAX_VALUE} to disable this.
     *                      You should only do this if you are sure that the received Objects will never be big and the
     *                      sending side are trusted, as this opens the possibility for a DOS-Attack due an {@link OutOfMemoryError}.
     *
     */
    public CompatibleMarshallingDecoder(UnmarshallerProvider provider, int maxObjectSize) {
        this.provider = provider;
        this.maxObjectSize = maxObjectSize;
    }

    @Override
    public Object decode(ChannelInboundHandlerContext<Byte> ctx, ChannelBuffer buffer) throws Exception {
        Unmarshaller unmarshaller = provider.getUnmarshaller(ctx);
        ByteInput input = new ChannelBufferByteInput(buffer);
        if (maxObjectSize != Integer.MAX_VALUE) {
            input = new LimitingByteInput(input, maxObjectSize);
        }
        try {
            unmarshaller.start(input);
            Object obj = unmarshaller.readObject();
            unmarshaller.finish();
            return obj;
        } catch (LimitingByteInput.TooBigObjectException e) {
            throw new TooLongFrameException("Object to big to unmarshal");
        } finally {
            // Call close in a finally block as the ReplayingDecoder will throw an Error if not enough bytes are
            // readable. This helps to be sure that we do not leak resource
            unmarshaller.close();
        }
    }

    @Override
    public Object decodeLast(ChannelInboundHandlerContext<Byte> ctx, ChannelBuffer buffer) throws Exception {
        switch (buffer.readableBytes()) {
        case 0:
            return null;
        case 1:
            // Ignore the last TC_RESET
            if (buffer.getByte(buffer.readerIndex()) == ObjectStreamConstants.TC_RESET) {
                buffer.skipBytes(1);
                return null;
            }
        }

        Object decoded = decode(ctx, buffer);
        return decoded;
    }

    @Override
    public void exceptionCaught(ChannelInboundHandlerContext<Byte> ctx, Throwable cause) throws Exception {
        if (cause instanceof TooLongFrameException) {
            ctx.close();
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }
}
