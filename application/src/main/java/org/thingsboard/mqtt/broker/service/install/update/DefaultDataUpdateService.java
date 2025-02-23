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
package org.thingsboard.mqtt.broker.service.install.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.thingsboard.mqtt.broker.common.data.AdminSettings;
import org.thingsboard.mqtt.broker.common.data.BrokerConstants;
import org.thingsboard.mqtt.broker.common.data.security.ClientCredentialsType;
import org.thingsboard.mqtt.broker.common.data.security.MqttClientCredentials;
import org.thingsboard.mqtt.broker.common.util.JacksonUtil;
import org.thingsboard.mqtt.broker.dao.client.MqttClientCredentialsService;
import org.thingsboard.mqtt.broker.dao.settings.AdminSettingsService;
import org.thingsboard.mqtt.broker.service.install.data.ConnectivitySettings;
import org.thingsboard.mqtt.broker.service.install.data.WebSocketClientSettings;

import java.util.List;

@Service
@Profile("install")
@Slf4j
@RequiredArgsConstructor
public class DefaultDataUpdateService implements DataUpdateService {

    private static final int DEFAULT_PAGE_SIZE = 1024;

    private final MqttClientCredentialsService mqttClientCredentialsService;
    private final AdminSettingsService adminSettingsService;

    @Override
    public void updateData(String fromVersion) throws Exception {
        switch (fromVersion) {
            case "1.3.0":
                log.info("Updating data from version 1.3.0 to 1.4.0 ...");
                updateSslMqttClientCredentials();
                createConnectivitySettings();
                break;
            case "1.4.0":
                log.info("Updating data from version 1.4.0 to 2.0.0 ...");
                createWsClientSettings();
                break;
            case "2.0.0":
                log.info("Updating data from version 2.0.0 to 2.0.1 ...");
                updateWsClientSettings();
                break;
            default:
                throw new RuntimeException("Unable to update data, unsupported fromVersion: " + fromVersion);
        }
    }

    private void updateSslMqttClientCredentials() {
        List<MqttClientCredentials> sslCredentials = mqttClientCredentialsService.findByCredentialsType(ClientCredentialsType.SSL);
        log.info("Found {} client credentials with type {}", sslCredentials.size(), ClientCredentialsType.SSL);
        if (!CollectionUtils.isEmpty(sslCredentials)) {
            for (MqttClientCredentials credentials : sslCredentials) {
                String newCredentialsValueStr = convertSslMqttClientCredentialsForVersion140(credentials.getCredentialsValue());

                if (newCredentialsValueStr == null) {
                    continue;
                }

                credentials.setCredentialsValue(newCredentialsValueStr);
                MqttClientCredentials savedMqttClientCredentials = mqttClientCredentialsService.saveCredentials(credentials);
                log.info("Updated client credentials {}", savedMqttClientCredentials);
            }
        }
    }

    String convertSslMqttClientCredentialsForVersion140(String oldCredentialsValueStr) {
        ObjectNode newCredentialsValue = JacksonUtil.newObjectNode();
        JsonNode oldCredentialsValue = JacksonUtil.toJsonNode(oldCredentialsValueStr);

        if (oldCredentialsValue.has("certCommonName")) {
            String certCommonName = oldCredentialsValue.get("certCommonName").asText();
            newCredentialsValue.put("certCnPattern", certCommonName);
            newCredentialsValue.put("certCnIsRegex", false);

            if (oldCredentialsValue.has("authRulesMapping")) {
                JsonNode authRulesMapping = oldCredentialsValue.get("authRulesMapping");
                newCredentialsValue.set("authRulesMapping", authRulesMapping);
            }
            return JacksonUtil.toString(newCredentialsValue);
        } else {
            log.info("Skipping client credentials transform with value {}", oldCredentialsValueStr);
        }
        return null;
    }

    private void createConnectivitySettings() {
        if (getAdminSettingsByKey(BrokerConstants.CONNECTIVITY_KEY) == null) {
            log.info("Creating connectivity settings ...");
            adminSettingsService.saveAdminSettings(ConnectivitySettings.createConnectivitySettings());
            log.info("Connectivity settings created!");
        }
    }

    private void createWsClientSettings() {
        if (getAdminSettingsByKey(BrokerConstants.WEBSOCKET_KEY) == null) {
            saveWsClientSettings();
        }
    }

    private void saveWsClientSettings() {
        log.info("Creating WebSocket client settings ...");
        adminSettingsService.saveAdminSettings(WebSocketClientSettings.createWsClientSettings());
        log.info("WebSocket client settings created!");
    }

    private void updateWsClientSettings() {
        AdminSettings wsSettings = getAdminSettingsByKey(BrokerConstants.WEBSOCKET_KEY);
        if (wsSettings == null) {
            saveWsClientSettings();
            return;
        }
        JsonNode jsonValue = wsSettings.getJsonValue();
        if (jsonValue == null) {
            log.info("Creating correct JSON value for WebSocket client settings ...");
            wsSettings.setJsonValue(WebSocketClientSettings.createWsClientJsonValue());
            adminSettingsService.saveAdminSettings(wsSettings);
            log.info("WebSocket client settings updated with correct JSON value!");
            return;
        }
        JsonNode maxMessages = jsonValue.get("maxMessages");
        if (maxMessages == null || maxMessages.isNull()) {
            log.info("Setting 'maxMessages' value for WebSocket client settings ...");

            ObjectNode objectNode = (ObjectNode) jsonValue;
            objectNode.put("maxMessages", 1000);

            wsSettings.setJsonValue(objectNode);
            adminSettingsService.saveAdminSettings(wsSettings);
            log.info("WebSocket client settings updated with 'maxMessages' value!");
        }
    }

    private AdminSettings getAdminSettingsByKey(String key) {
        return adminSettingsService.findAdminSettingsByKey(key);
    }
}
