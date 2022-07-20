/**
 * Copyright © 2016-2020 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.actors.client.service.handlers;

import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.broker.common.data.SessionInfo;
import org.thingsboard.mqtt.broker.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.service.mqtt.persistence.MsgPersistenceManager;
import org.thingsboard.mqtt.broker.session.ClientSessionCtx;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttPubRecHandlerTest {

    MsgPersistenceManager msgPersistenceManager;
    MqttMessageGenerator mqttMessageGenerator;
    MqttPubRecHandler mqttPubRecHandler;
    ClientSessionCtx ctx;

    @BeforeEach
    void setUp() {
        msgPersistenceManager = mock(MsgPersistenceManager.class);
        mqttMessageGenerator = mock(MqttMessageGenerator.class);
        mqttPubRecHandler = spy(new MqttPubRecHandler(msgPersistenceManager, mqttMessageGenerator));

        ctx = mock(ClientSessionCtx.class);
    }

    @Test
    void testProcessPersistent() {
        when(ctx.getSessionInfo()).thenReturn(getSessionInfo(true));
        mqttPubRecHandler.process(ctx, 1);
        verify(msgPersistenceManager, times(1)).processPubRec(1, ctx);
    }

    @Test
    void testProcessNonPersistent() {
        ChannelHandlerContext handlerContext = mock(ChannelHandlerContext.class);
        when(ctx.getChannel()).thenReturn(handlerContext);
        when(ctx.getSessionInfo()).thenReturn(getSessionInfo(false));

        mqttPubRecHandler.process(ctx, 1);
        verify(mqttMessageGenerator, times(1)).createPubRelMsg(1);
    }

    private SessionInfo getSessionInfo(boolean persistent) {
        return SessionInfo.builder().persistent(persistent).build();
    }
}