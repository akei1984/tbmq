/**
 * Copyright © 2016-2024 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.dao.messages;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.jedis.JedisClusterConnection;
import org.springframework.data.redis.connection.jedis.JedisConnection;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.broker.common.data.DevicePublishMsg;
import org.thingsboard.mqtt.broker.common.util.JacksonUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.util.JedisClusterCRC16;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeviceMsgServiceImpl implements DeviceMsgService {

    private static final JedisPool MOCK_POOL = new JedisPool(); //non-null pool required for JedisConnection to trigger closing jedis connection

    private static final RedisSerializer<String> stringSerializer = StringRedisSerializer.UTF_8;

    private static final CSVFormat IMPORT_CSV_FORMAT = CSVFormat.Builder.create()
            .setHeader().setSkipHeaderRecord(true).build();

    private static final byte[] ADD_MESSAGES_SCRIPT_SHA = stringSerializer.serialize("cea4fbc467e6f4749bb3170f45b4853e89956a31");
    private static final byte[] GET_MESSAGES_SCRIPT_SHA = stringSerializer.serialize("e083e5645a5f268448aca2ec1d3150ee6de510ef");
    private static final byte[] REMOVE_MESSAGES_SCRIPT_SHA = stringSerializer.serialize("a619f42eb693ea732763d878dd59dff513a295c7");
    private static final byte[] REMOVE_MESSAGE_SCRIPT_SHA = stringSerializer.serialize("038e09c6e313eab0d5be4f31361250f4179bc38c");
    private static final byte[] UPDATE_PACKET_TYPE_SCRIPT_SHA = stringSerializer.serialize("958139aa4015911c82ddd423ff408b6638805081");
    private static final byte[] MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_CLUSTER_SHA = stringSerializer.serialize("844fe5707cd0b3ed8397108ea9e1ab27ab0917be");
    private static final byte[] MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_STANDALONE_SHA = stringSerializer.serialize("bade718d27865f5520fc988898bad4268eb8180c");

    private static final byte[] ADD_MESSAGES_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local lastPacketIdKey = KEYS[2]
            local maxMessagesSize = tonumber(ARGV[1])
            local messages = cjson.decode(ARGV[2])
            -- Fetch the last packetId from the key-value store
            local lastPacketId = tonumber(redis.call('GET', lastPacketIdKey)) or 0
            -- Initialize the score with the last packet ID value
            local score = lastPacketId
            -- Track the first packet ID
            local previousPacketId = lastPacketId
            -- Add each message to the sorted set and as a separate key
            for _, msg in ipairs(messages) do
                lastPacketId = lastPacketId + 1
                if lastPacketId > 0xffff then
                    lastPacketId = 1
                end
                msg.packetId = lastPacketId
                score = score + 1
                local msgKey = messagesKey .. "_" .. lastPacketId
                local msgJson = cjson.encode(msg)
                -- Store the message as a separate key with TTL
                redis.call('SET', msgKey, msgJson, 'EX', msg.msgExpiryInterval)
                -- Add the key to the sorted set using packetId as the score
                redis.call('ZADD', messagesKey, score, msgKey)
            end
            -- Update the last packetId in the key-value store
            redis.call('SET', lastPacketIdKey, lastPacketId)
            -- Get the elements to be trimmed
            local numElementsToRemove = redis.call('ZCARD', messagesKey) - maxMessagesSize
            if numElementsToRemove > 0 then
                local trimmedElements = redis.call('ZRANGE', messagesKey, 0, numElementsToRemove - 1)
                for _, key in ipairs(trimmedElements) do
                    redis.call('DEL', key)
                    redis.call('ZREM', messagesKey, key)
                end
            end
            return previousPacketId
            """);
    private static final byte[] GET_MESSAGES_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local maxMessagesSize = tonumber(ARGV[1])
            -- Get the range of elements from the sorted set
            local elements = redis.call('ZRANGE', messagesKey, 0, -1)
            local messages = {}
            for _, key in ipairs(elements) do
                -- Check if the key still exists
                if redis.call('EXISTS', key) == 1 then
                    local msgJson = redis.call('GET', key)
                    table.insert(messages, msgJson)
                else
                    -- If the key does not exist, remove it from the sorted set
                    redis.call('ZREM', messagesKey, key)
                end
            end
            return messages
            """);
    private static final byte[] REMOVE_MESSAGES_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local lastPacketIdKey = KEYS[2]
            -- Get all elements from the sorted set
            local elements = redis.call('ZRANGE', messagesKey, 0, -1)
            -- Delete each associated key entry
            for _, key in ipairs(elements) do
                redis.call('DEL', key)
            end
            -- Delete the sorted set
            redis.call('DEL', messagesKey)
            -- Delete the last packet id key
            redis.call('DEL', lastPacketIdKey)
            return nil
            """);
    private static final byte[] REMOVE_MESSAGE_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local packetId = ARGV[1]
            -- Construct the message key
            local msgKey = messagesKey .. "_" .. packetId
            -- Remove the message from the sorted set
            redis.call('ZREM', messagesKey, msgKey)
            -- Delete the message key
            redis.call('DEL', msgKey)
            return nil
            """);
    private static final byte[] UPDATE_PACKET_TYPE_SCRIPT = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local packetId = ARGV[1]
            -- Construct the message key
            local msgKey = messagesKey .. "_" .. packetId
            -- Fetch the message from the key-value store
            local msgJson = redis.call('GET', msgKey)
            if not msgJson then
                return nil -- Message not found
            end
            -- Decode the JSON message
            local msg = cjson.decode(msgJson)
            -- Update the packet type
            msg.packetType = "PUBREL"
            -- Encode the updated message back to JSON
            local updatedMsgJson = cjson.encode(msg)
            -- Save the updated message back to the key-value store
            redis.call('SET', msgKey, updatedMsgJson)
            return nil
            """);
    private static final byte[] MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_CLUSTER = stringSerializer.serialize("""
            local messagesKey = KEYS[1]
            local lastPacketIdKey = KEYS[2]
            local messages = cjson.decode(ARGV[1])
            local lastPacketId = 0
            
            -- Add each message to the sorted set using packetId as the score
            for _, msg in ipairs(messages) do
                local msgKey = messagesKey .. "_" .. msg.packetId
                local msgJson = cjson.encode(msg)
                -- Store the message as a separate key with TTL
                redis.call('SET', msgKey, msgJson, 'EX', msg.msgExpiryInterval)
                -- Use msg.packetId as the score
                redis.call('ZADD', messagesKey, msg.packetId, msgKey)
                -- Update lastPacketId with the current packetId
                lastPacketId = msg.packetId
            end

            -- Update the last packetId in the key-value store
            redis.call('SET', lastPacketIdKey, lastPacketId)
            
            return nil
            """);
    private static final byte[] MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_STANDALONE = stringSerializer.serialize("""
            local messages = cjson.decode(ARGV[1])
            local cachePrefix = ARGV[2]
            local lastPacketIdMap = {}
            
            -- Helper function to generate Redis keys based on clientId and cachePrefix
            local function generateKeys(clientId)
                local messagesKey = "{" .. clientId .. "}_messages"
                local lastPacketIdKey = "{" .. clientId .. "}_last_packet_id"

                -- If cachePrefix is provided, prepend it to the base keys
                if cachePrefix and cachePrefix ~= "" then
                    messagesKey = cachePrefix .. messagesKey
                    lastPacketIdKey = cachePrefix .. lastPacketIdKey
                end

                return messagesKey, lastPacketIdKey
            end
            
            -- Add each message to the sorted set using packetId as the score
            for _, msg in ipairs(messages) do
                local clientId = msg.clientId
                local messagesKey, lastPacketIdKey = generateKeys(clientId)
                local msgKey = messagesKey .. "_" .. msg.packetId
                local msgJson = cjson.encode(msg)

                -- Store the message as a separate key with TTL
                redis.call('SET', msgKey, msgJson, 'EX', msg.msgExpiryInterval)
                -- Use msg.packetId as the score
                redis.call('ZADD', messagesKey, msg.packetId, msgKey)
                -- Update the lastPacketId in the map
                lastPacketIdMap[lastPacketIdKey] = msg.packetId
            end
            
            -- After processing all messages, update the last packetId for each clientId
            for lastPacketIdKey, lastPacketId in pairs(lastPacketIdMap) do
                redis.call('SET', lastPacketIdKey, lastPacketId)
            end
            
            return nil
            """);

    @Value("${mqtt.persistent-session.device.persisted-messages.ttl}")
    private int defaultTtl;

    @Value("${mqtt.persistent-session.device.persisted-messages.limit}")
    private int messagesLimit;

    @Value("${cache.cache-prefix:}")
    protected String cachePrefix;

    private byte[] messagesLimitBytes;

    private final JedisConnectionFactory connectionFactory;
    private final boolean installProfileActive;

    public DeviceMsgServiceImpl(RedisConnectionFactory redisConnectionFactory, Environment environment) {
        this.connectionFactory = (JedisConnectionFactory) redisConnectionFactory;
        this.installProfileActive = Arrays.asList(environment.getActiveProfiles()).contains("install");
    }

    @PostConstruct
    public void init() {
        if (messagesLimit > 0xffff) {
            throw new IllegalArgumentException("Persisted messages limit can't be greater than 65535!");
        }
        messagesLimitBytes = intToBytes(messagesLimit);
        try (var connection = getNonClusterAwareConnection()) {
            loadScript(connection, ADD_MESSAGES_SCRIPT_SHA, ADD_MESSAGES_SCRIPT);
            loadScript(connection, GET_MESSAGES_SCRIPT_SHA, GET_MESSAGES_SCRIPT);
            loadScript(connection, REMOVE_MESSAGES_SCRIPT_SHA, REMOVE_MESSAGES_SCRIPT);
            loadScript(connection, REMOVE_MESSAGE_SCRIPT_SHA, REMOVE_MESSAGE_SCRIPT);
            loadScript(connection, UPDATE_PACKET_TYPE_SCRIPT_SHA, UPDATE_PACKET_TYPE_SCRIPT);
            if (!installProfileActive) {
                return;
            }
            if (connectionFactory.isRedisClusterAware()) {
                loadScript(connection, MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_CLUSTER_SHA,
                        MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_CLUSTER);
                return;
            }
            loadScript(connection, MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_STANDALONE_SHA,
                    MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_STANDALONE);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to init persisted device messages cache service!", t);
        }
    }

    @Override
    public int saveAndReturnPreviousPacketId(String clientId, List<DevicePublishMsg> devicePublishMessages, boolean failOnConflict) {
        if (log.isTraceEnabled()) {
            log.trace("Save persisted messages, clientId - {}, devicePublishMessages size - {}", clientId, devicePublishMessages.size());
        }
        var messages = devicePublishMessages.stream()
                .map(devicePublishMsg -> new DevicePublishMsgEntity(devicePublishMsg, defaultTtl))
                .collect(Collectors.toCollection(ArrayList::new));
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] messagesBytes = JacksonUtil.writeValueAsBytes(messages);
        try (var connection = getConnection(rawMessagesKey)) {
            Long prevPacketId;
            try {
                prevPacketId = connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(ADD_MESSAGES_SCRIPT_SHA),
                        ReturnType.INTEGER,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey,
                        messagesLimitBytes,
                        messagesBytes
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                prevPacketId = connection.scriptingCommands().eval(
                        Objects.requireNonNull(ADD_MESSAGES_SCRIPT),
                        ReturnType.INTEGER,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey,
                        messagesLimitBytes,
                        messagesBytes
                );
            }
            return prevPacketId != null ? prevPacketId.intValue() : 0;
        }
    }

    @Override
    public List<DevicePublishMsg> findPersistedMessages(String clientId) {
        if (log.isTraceEnabled()) {
            log.trace("Find persisted messages, clientId - {}", clientId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        try (var connection = getConnection(rawMessagesKey)) {
            List<byte[]> messagesBytes;
            try {
                messagesBytes = connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(GET_MESSAGES_SCRIPT_SHA),
                        ReturnType.MULTI,
                        1,
                        rawMessagesKey,
                        messagesLimitBytes
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                messagesBytes = connection.scriptingCommands().eval(
                        Objects.requireNonNull(GET_MESSAGES_SCRIPT),
                        ReturnType.MULTI,
                        1,
                        rawMessagesKey,
                        messagesLimitBytes
                );
            }
            return Objects.requireNonNull(messagesBytes)
                    .stream().map(DevicePublishMsgEntity::fromBytes)
                    .toList();
        }
    }

    @Override
    public void removePersistedMessages(String clientId) {
        if (log.isTraceEnabled()) {
            log.trace("Removing persisted messages, clientId - {}", clientId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT_SHA),
                        ReturnType.VALUE,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(REMOVE_MESSAGES_SCRIPT),
                        ReturnType.VALUE,
                        2,
                        rawMessagesKey,
                        rawLastPacketIdKey
                );
            }
        } catch (Exception e) {
            log.error("Failed to remove persisted messages, clientId - {}", clientId, e);
        }
    }

    @Override
    public void removePersistedMessage(String clientId, int packetId) {
        if (log.isTraceEnabled()) {
            log.trace("Removing persisted message, clientId - {}, packetId - {}", clientId, packetId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] packetIdBytes = intToBytes(packetId);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT_SHA),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(REMOVE_MESSAGE_SCRIPT),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove persisted message, clientId - " + clientId + " packetId - " + packetId, e);
        }
    }

    @Override
    public void updatePacketReceived(String clientId, int packetId) {
        if (log.isTraceEnabled()) {
            log.trace("Update packet received, clientId - {}, packetId - {}", clientId, packetId);
        }
        byte[] rawMessagesKey = toMessagesCacheKey(clientId);
        byte[] packetIdBytes = intToBytes(packetId);
        try (var connection = getConnection(rawMessagesKey)) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(UPDATE_PACKET_TYPE_SCRIPT_SHA),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            } catch (InvalidDataAccessApiUsageException e) {
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(UPDATE_PACKET_TYPE_SCRIPT),
                        ReturnType.VALUE,
                        1,
                        rawMessagesKey,
                        packetIdBytes
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update packet type, clientId - " + clientId + " packetId - " + packetId, e);
        }
    }

    @Override
    public int getLastPacketId(String clientId) {
        if (log.isTraceEnabled()) {
            log.trace("Get last packet id, clientId - {}", clientId);
        }
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        try (var connection = getConnection(rawLastPacketIdKey)) {
            byte[] rawValue = connection.stringCommands().get(rawLastPacketIdKey);
            if (rawValue == null) {
                return 0;
            }
            return Integer.parseInt(Objects.requireNonNull(stringSerializer.deserialize(rawValue)));
        }
    }

    @Override
    public void removeLastPacketId(String clientId) {
        if (log.isTraceEnabled()) {
            log.trace("Remove last packet id, clientId - {}", clientId);
        }
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        try (var connection = getConnection(rawLastPacketIdKey)) {
            connection.keyCommands().del(rawLastPacketIdKey);
        }
    }

    @Override
    public void saveLastPacketId(String clientId, int lastPacketId) {
        if (log.isTraceEnabled()) {
            log.trace("Save last packet id, clientId - {}", clientId);
        }
        byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
        byte[] rawLastPacketIdValue = intToBytes(lastPacketId);
        try (var connection = getConnection(rawLastPacketIdKey)) {
            connection.stringCommands().set(rawLastPacketIdKey, rawLastPacketIdValue);
        }
    }

    @Override
    public void importFromCsvFile(Path filePath) {
        if (!installProfileActive) {
            throw new RuntimeException("Import from CSV file can be executed during upgrade only!");
        }
        if (log.isTraceEnabled()) {
            log.trace("Import from csv file: {}", filePath);
        }
        if (connectionFactory.isRedisClusterAware()) {
            migrateMessagesToRedisCluster(filePath);
            return;
        }
        migrateMessagesToRedisStandalone(filePath);
    }

    private void migrateMessagesToRedisCluster(Path filePath) {
        var clientIdToMsgMap = new HashMap<String, List<DevicePublishMsgEntity>>();
        consumeCsvRecords(filePath, record -> {
            String clientId = record.get("client_id");
            try {
                clientIdToMsgMap.computeIfAbsent(clientId, k -> new ArrayList<>())
                        .add(DevicePublishMsgEntity.fromCsvRecord(record, defaultTtl));
            } catch (DecoderException e) {
                throw new RuntimeException("Failed to deserialize message for client: " + clientId, e);
            }
        });
        if (clientIdToMsgMap.isEmpty()) {
            return;
        }
        clientIdToMsgMap.forEach((clientId, messages) -> {
            byte[] rawMessagesKey = toMessagesCacheKey(clientId);
            byte[] rawLastPacketIdKey = toLastPacketIdKey(clientId);
            byte[] messagesBytes = JacksonUtil.writeValueAsBytes(messages);
            try (var connection = getConnection(rawMessagesKey)) {
                try {
                    connection.scriptingCommands().evalSha(
                            Objects.requireNonNull(MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_CLUSTER_SHA),
                            ReturnType.VALUE,
                            2,
                            rawMessagesKey,
                            rawLastPacketIdKey,
                            messagesBytes
                    );
                } catch (InvalidDataAccessApiUsageException e) {
                    log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                    connection.scriptingCommands().eval(
                            Objects.requireNonNull(MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_CLUSTER),
                            ReturnType.VALUE,
                            2,
                            rawMessagesKey,
                            rawLastPacketIdKey,
                            messagesBytes
                    );
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to migrate messages to Redis for client: " + clientId, e);
            }
        });
    }

    private void migrateMessagesToRedisStandalone(Path filePath) {
        var messages = new ArrayList<DevicePublishMsgEntity>();
        consumeCsvRecords(filePath, record -> {
            String clientId = record.get("client_id");
            try {
                messages.add(DevicePublishMsgEntity.fromCsvRecord(record, defaultTtl));
            } catch (DecoderException e) {
                throw new RuntimeException("Failed to deserialize message for client: " + clientId, e);
            }
        });
        if (messages.isEmpty()) {
            return;
        }
        byte[] messagesBytes = JacksonUtil.writeValueAsBytes(messages);
        byte[] rawCachePrefix = stringSerializer.serialize(cachePrefix);
        try (var connection = getNonClusterAwareConnection()) {
            try {
                connection.scriptingCommands().evalSha(
                        Objects.requireNonNull(MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_STANDALONE_SHA),
                        ReturnType.VALUE,
                        0,
                        messagesBytes,
                        rawCachePrefix
                );
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("Slowly executing eval instead of fast evalSha [{}] due to exception throwing on sha evaluation: ", connection, e);
                connection.scriptingCommands().eval(
                        Objects.requireNonNull(MIGRATE_FROM_POSTGRES_TO_REDIS_SCRIPT_STANDALONE),
                        ReturnType.VALUE,
                        0,
                        messagesBytes,
                        rawCachePrefix
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to migrate messages to Redis: ", e);
        }
    }

    private void consumeCsvRecords(Path filePath, Consumer<CSVRecord> csvRecordConsumer) {
        try (CSVParser csvParser = new CSVParser(Files.newBufferedReader(filePath), IMPORT_CSV_FORMAT)) {
            for (CSVRecord record : csvParser) {
                csvRecordConsumer.accept(record);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV file: " + filePath, e);
        }
    }

    private byte[] toMessagesCacheKey(String clientId) {
        ClientIdMessagesCacheKey clientIdMessagesCacheKey = new ClientIdMessagesCacheKey(clientId, cachePrefix);
        String stringValue = clientIdMessagesCacheKey.toString();
        byte[] rawKey;
        try {
            rawKey = stringSerializer.serialize(stringValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (rawKey == null) {
            throw new IllegalArgumentException("Failed to serialize the messages cache key, clientId - " + clientId);
        }
        return rawKey;
    }

    private byte[] toLastPacketIdKey(String clientId) {
        ClientIdLastPacketIdCacheKey clientIdLastPacketIdCacheKey = new ClientIdLastPacketIdCacheKey(clientId, cachePrefix);
        String stringValue = clientIdLastPacketIdCacheKey.toString();
        byte[] rawKey;
        try {
            rawKey = stringSerializer.serialize(stringValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (rawKey == null) {
            throw new IllegalArgumentException("Failed to serialize the last packet id cache key, clientId - " + clientId);
        }
        return rawKey;
    }

    private byte[] intToBytes(int value) {
        return stringSerializer.serialize(Integer.toString(value));
    }

    private RedisConnection getConnection(byte[] rawKey) {
        if (!connectionFactory.isRedisClusterAware()) {
            return connectionFactory.getConnection();
        }
        RedisConnection connection = connectionFactory.getClusterConnection();

        int slotNum = JedisClusterCRC16.getSlot(rawKey);
        Jedis jedis = new Jedis((((JedisClusterConnection) connection).getNativeConnection().getConnectionFromSlot(slotNum)));

        JedisConnection jedisConnection = new JedisConnection(jedis, MOCK_POOL, jedis.getDB());
        jedisConnection.setConvertPipelineAndTxResults(connectionFactory.getConvertPipelineAndTxResults());

        return jedisConnection;
    }

    private RedisConnection getNonClusterAwareConnection() {
        return connectionFactory.getConnection();
    }

    private void loadScript(RedisConnection connection, byte[] scriptSha, byte[] script) {
        String scriptShaStr = new String(Objects.requireNonNull(scriptSha));
        log.debug("Loading LUA with expected SHA [{}], connection [{}]", scriptShaStr, connection.getNativeConnection());
        String actualScriptSha = connection.scriptingCommands().scriptLoad(Objects.requireNonNull(script));
        validateSha(scriptSha, scriptShaStr, actualScriptSha, connection);
    }

    private void validateSha(byte[] expectedSha, String expectedShaStr, String actualAddMessagesScriptSha, RedisConnection connection) {
        if (!Arrays.equals(expectedSha, stringSerializer.serialize(actualAddMessagesScriptSha))) {
            log.error("SHA for script is wrong! Expected [{}] actual [{}] connection [{}]", expectedShaStr, actualAddMessagesScriptSha, connection.getNativeConnection());
        }
    }

}
