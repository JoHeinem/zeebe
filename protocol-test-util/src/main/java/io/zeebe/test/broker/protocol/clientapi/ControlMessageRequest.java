/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.test.broker.protocol.clientapi;

import java.util.Map;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import io.zeebe.protocol.clientapi.ControlMessageRequestEncoder;
import io.zeebe.protocol.clientapi.ControlMessageType;
import io.zeebe.protocol.clientapi.MessageHeaderEncoder;
import io.zeebe.test.broker.protocol.MsgPackHelper;
import io.zeebe.transport.ClientOutput;
import io.zeebe.transport.ClientRequest;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.util.buffer.BufferWriter;

public class ControlMessageRequest implements BufferWriter
{
    protected final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    protected final ControlMessageRequestEncoder requestEncoder = new ControlMessageRequestEncoder();
    protected final MsgPackHelper msgPackHelper;
    protected final ClientOutput output;
    protected final RemoteAddress target;

    protected ControlMessageType messageType = ControlMessageType.NULL_VAL;
    protected byte[] encodedData;

    protected ClientRequest request;

    public ControlMessageRequest(ClientOutput output, RemoteAddress target, MsgPackHelper msgPackHelper)
    {
        this.output = output;
        this.target = target;
        this.msgPackHelper = msgPackHelper;
    }

    public ControlMessageRequest messageType(ControlMessageType messageType)
    {
        this.messageType = messageType;
        return this;
    }

    public ControlMessageRequest data(Map<String, Object> data)
    {
        this.encodedData = msgPackHelper.encodeAsMsgPack(data);
        return this;
    }

    public ControlMessageRequest send()
    {
        request = output.sendRequest(target, this);
        return this;
    }

    public ControlMessageResponse await()
    {
        final DirectBuffer responseBuffer = request.join();

        final ControlMessageResponse response = new ControlMessageResponse(msgPackHelper);
        response.wrap(responseBuffer, 0, responseBuffer.capacity());
        return response;
    }

    public ErrorResponse awaitError()
    {
        final DirectBuffer responseBuffer = request.join();

        final ErrorResponse errorResponse = new ErrorResponse(msgPackHelper);
        errorResponse.wrap(responseBuffer, 0, responseBuffer.capacity());
        return errorResponse;
    }

    @Override
    public int getLength()
    {
        return MessageHeaderEncoder.ENCODED_LENGTH +
                ControlMessageRequestEncoder.BLOCK_LENGTH +
                ControlMessageRequestEncoder.dataHeaderLength() +
                encodedData.length;
    }

    @Override
    public void write(MutableDirectBuffer buffer, int offset)
    {
        messageHeaderEncoder.wrap(buffer, offset)
            .schemaId(requestEncoder.sbeSchemaId())
            .templateId(requestEncoder.sbeTemplateId())
            .blockLength(requestEncoder.sbeBlockLength())
            .version(requestEncoder.sbeSchemaVersion());

        requestEncoder.wrap(buffer, offset + messageHeaderEncoder.encodedLength())
            .messageType(messageType)
            .putData(encodedData, 0, encodedData.length);
    }

}
