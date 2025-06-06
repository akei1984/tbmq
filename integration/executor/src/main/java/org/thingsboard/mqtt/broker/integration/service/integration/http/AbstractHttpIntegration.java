/**
 * Copyright © 2016-2025 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.integration.service.integration.http;

import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.broker.common.data.BasicCallback;
import org.thingsboard.mqtt.broker.common.data.util.CallbackUtil;
import org.thingsboard.mqtt.broker.gen.integration.PublishIntegrationMsgProto;
import org.thingsboard.mqtt.broker.integration.api.AbstractIntegration;
import org.thingsboard.mqtt.broker.integration.api.callback.IntegrationMsgCallback;

@Slf4j
public abstract class AbstractHttpIntegration extends AbstractIntegration {

    @Override
    public void process(PublishIntegrationMsgProto msg, IntegrationMsgCallback integrationMsgCallback) {
        var callback = createBasicCallback(integrationMsgCallback);
        try {
            doProcess(msg, callback);
        } catch (Exception e) {
            log.error("[{}][{}] Failure during msg processing", lifecycleMsg.getIntegrationId(), lifecycleMsg.getName(), e);
            callback.onFailure(e);
        }
    }

    private BasicCallback createBasicCallback(IntegrationMsgCallback callback) {
        return CallbackUtil.createCallback(
                () -> {
                    integrationStatistics.incMessagesProcessed();
                    callback.onSuccess();
                },
                throwable -> {
                    handleMsgProcessingFailure(throwable);
                    callback.onFailure(throwable);
                });
    }

    protected abstract void doProcess(PublishIntegrationMsgProto msg, BasicCallback callback) throws Exception;
}
