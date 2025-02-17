/*
 * Copyright (c) 2012-2018 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.broker;

import io.moquette.broker.subscriptions.Topic;
import io.moquette.broker.security.IAuthenticator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.*;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.channel.ChannelFutureListener.CLOSE_ON_FAILURE;
import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.*;
import static io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader.from;
import static io.netty.handler.codec.mqtt.MqttQoS.*;
import io.netty.util.ReferenceCountUtil;

final class MQTTConnection {

    private static final Logger LOG = LoggerFactory.getLogger(MQTTConnection.class);

    final Channel channel;
    private final BrokerConfiguration brokerConfig;
    private final IAuthenticator authenticator;
    private final SessionRegistry sessionRegistry;
    private final PostOffice postOffice;
    private volatile boolean connected;
    private final AtomicInteger lastPacketId = new AtomicInteger(0);
    private Session bindedSession;

    MQTTConnection(Channel channel, BrokerConfiguration brokerConfig, IAuthenticator authenticator,
                   SessionRegistry sessionRegistry, PostOffice postOffice) {
        this.channel = channel;
        this.brokerConfig = brokerConfig;
        this.authenticator = authenticator;
        this.sessionRegistry = sessionRegistry;
        this.postOffice = postOffice;
        this.connected = false;
    }

    void handleMessage(MqttMessage msg) {
        MqttMessageType messageType = msg.fixedHeader().messageType();
        LOG.debug("Received MQTT message, type: {}", messageType);
        switch (messageType) {
            case CONNECT:
                processConnect((MqttConnectMessage) msg);
                break;
            case SUBSCRIBE:
                processSubscribe((MqttSubscribeMessage) msg);
                break;
            case UNSUBSCRIBE:
                processUnsubscribe((MqttUnsubscribeMessage) msg);
                break;
            case PUBLISH:
                processPublish((MqttPublishMessage) msg);
                break;
            case PUBREC:
                processPubRec(msg);
                break;
            case PUBCOMP:
                processPubComp(msg);
                break;
            case PUBREL:
                processPubRel(msg);
                break;
            case DISCONNECT:
                processDisconnect(msg);
                break;
            case PUBACK:
                processPubAck(msg);
                break;
            case PINGREQ:
                MqttFixedHeader pingHeader = new MqttFixedHeader(MqttMessageType.PINGRESP, false, AT_MOST_ONCE,
                                                                false, 0);
                MqttMessage pingResp = new MqttMessage(pingHeader);
                channel.writeAndFlush(pingResp).addListener(CLOSE_ON_FAILURE);
                break;
            default:
                LOG.error("Unknown MessageType: {}", messageType);
                break;
        }
    }

    private void processPubComp(MqttMessage msg) {
        final int messageID = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        bindedSession.processPubComp(messageID);
    }

    private void processPubRec(MqttMessage msg) {
        final int messageID = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        bindedSession.processPubRec(messageID);
    }

    static MqttMessage pubrel(int messageID) {
        MqttFixedHeader pubRelHeader = new MqttFixedHeader(MqttMessageType.PUBREL, false, AT_LEAST_ONCE, false, 0);
        return new MqttMessage(pubRelHeader, from(messageID));
    }

    private void processPubAck(MqttMessage msg) {
        final int messageID = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        bindedSession.pubAckReceived(messageID);
    }

    void processConnect(MqttConnectMessage msg) {
        MqttConnectPayload payload = msg.payload();
        String clientId = payload.clientIdentifier();
        final String username = payload.userName();
        LOG.trace("Processing CONNECT message. CId: {} username: {}", clientId, username);

        if (isNotProtocolVersion(msg, MqttVersion.MQTT_3_1) && isNotProtocolVersion(msg, MqttVersion.MQTT_3_1_1)&& isNotProtocolVersion(msg, MqttVersion.MQTT_5)) {
            LOG.warn("MQTT protocol version is not valid. CId: {}", clientId);
            abortConnection(CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION);
            return;
        }
        final boolean cleanSession = msg.variableHeader().isCleanSession();
        if (clientId == null || clientId.length() == 0) {
            if (!brokerConfig.isAllowZeroByteClientId()) {
                LOG.info("Broker doesn't permit MQTT empty client ID. Username: {}", username);
                abortConnection(CONNECTION_REFUSED_IDENTIFIER_REJECTED);
                return;
            }

            if (!cleanSession) {
                LOG.info("MQTT client ID cannot be empty for persistent session. Username: {}", username);
                abortConnection(CONNECTION_REFUSED_IDENTIFIER_REJECTED);
                return;
            }

            // Generating client id.
            clientId = UUID.randomUUID().toString().replace("-", "");
            LOG.debug("Client has connected with integration generated id: {}, username: {}", clientId, username);
        }

        if (!login(msg, clientId)) {
            abortConnection(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD);
            channel.close().addListener(CLOSE_ON_FAILURE);
            return;
        }

        final SessionRegistry.SessionCreationResult result;
        try {
            LOG.trace("Binding MQTTConnection to session");
            result = sessionRegistry.createOrReopenSession(msg, clientId, this.getUsername());
            result.session.bind(this);
            bindedSession = result.session;
        } catch (SessionCorruptedException scex) {
            LOG.warn("MQTT session for client ID {} cannot be created", clientId);
            abortConnection(CONNECTION_REFUSED_SERVER_UNAVAILABLE);
            return;
        }

        final boolean msgCleanSessionFlag = msg.variableHeader().isCleanSession();
        boolean isSessionAlreadyPresent = !msgCleanSessionFlag && result.alreadyStored;
        final String clientIdUsed = clientId;
        final MqttConnAckMessage ackMessage = MqttMessageBuilders.connAck()
            .returnCode(CONNECTION_ACCEPTED)
            .sessionPresent(isSessionAlreadyPresent).build();
        channel.writeAndFlush(ackMessage).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    LOG.trace("CONNACK sent, channel: {}", channel);
                    if (!result.session.completeConnection()) {
                        // send DISCONNECT and close the channel
                        final MqttMessage disconnectMsg = MqttMessageBuilders.disconnect().build();
                        channel.writeAndFlush(disconnectMsg).addListener(CLOSE);
                        LOG.warn("CONNACK is sent but the session created can't transition in CONNECTED state");
                    } else {
                        NettyUtils.clientID(channel, clientIdUsed);
                        connected = true;
                        // OK continue with sending queued messages and normal flow

                        if (result.mode == SessionRegistry.CreationModeEnum.REOPEN_EXISTING) {
                            result.session.sendQueuedMessagesWhileOffline();
                        }

                        initializeKeepAliveTimeout(channel, msg, clientIdUsed);
                        setupInflightResender(channel);

                        postOffice.dispatchConnection(msg);
                        LOG.trace("dispatch connection: {}", msg.toString());
                    }
                } else {
                    bindedSession.disconnect();
                    sessionRegistry.remove(bindedSession);
                    LOG.error("CONNACK send failed, cleanup session and close the connection", future.cause());
                    channel.close();
                }

            }
        });
    }

    private void setupInflightResender(Channel channel) {
        channel.pipeline()
            .addFirst("inflightResender", new InflightResender(5_000, TimeUnit.MILLISECONDS));
    }

    private void initializeKeepAliveTimeout(Channel channel, MqttConnectMessage msg, String clientId) {
        int keepAlive = msg.variableHeader().keepAliveTimeSeconds();
        NettyUtils.keepAlive(channel, keepAlive);
        NettyUtils.cleanSession(channel, msg.variableHeader().isCleanSession());
        NettyUtils.clientID(channel, clientId);
        int idleTime = Math.round(keepAlive * 1.5f);
        setIdleTime(channel.pipeline(), idleTime);

        LOG.debug("Connection has been configured CId={}, keepAlive={}, removeTemporaryQoS2={}, idleTime={}",
            clientId, keepAlive, msg.variableHeader().isCleanSession(), idleTime);
    }

    private void setIdleTime(ChannelPipeline pipeline, int idleTime) {
        if (pipeline.names().contains("idleStateHandler")) {
            pipeline.remove("idleStateHandler");
        }
        pipeline.addFirst("idleStateHandler", new IdleStateHandler(idleTime, 0, 0));
    }

    private boolean isNotProtocolVersion(MqttConnectMessage msg, MqttVersion version) {
        return msg.variableHeader().version() != version.protocolLevel();
    }

    private void abortConnection(MqttConnectReturnCode returnCode) {
        MqttConnAckMessage badProto = MqttMessageBuilders.connAck()
            .returnCode(returnCode)
            .sessionPresent(false).build();
        channel.writeAndFlush(badProto).addListener(FIRE_EXCEPTION_ON_FAILURE);
        channel.close().addListener(CLOSE_ON_FAILURE);
    }

    private boolean login(MqttConnectMessage msg, final String clientId) {
        // handle user authentication
        if (msg.variableHeader().hasUserName()) {
            byte[] pwd = null;
            if (msg.variableHeader().hasPassword()) {
                pwd = msg.payload().passwordInBytes();
            } else if (!brokerConfig.isAllowAnonymous()) {
                LOG.info("Client didn't supply any password and MQTT anonymous mode is disabled CId={}", clientId);
                return false;
            }
            final String login = msg.payload().userName();
            if (!authenticator.checkValid(clientId, login, pwd)) {
                LOG.info("Authenticator has rejected the MQTT credentials CId={}, username={}", clientId, login);
                return false;
            }
            NettyUtils.userName(channel, login);
        } else if (!brokerConfig.isAllowAnonymous()) {
            LOG.info("Client didn't supply any credentials and MQTT anonymous mode is disabled. CId={}", clientId);
            return false;
        }
        return true;
    }

    void handleConnectionLost() {
        String clientID = NettyUtils.clientID(channel);
        if (clientID == null || clientID.isEmpty()) {
            return;
        }
        LOG.info("Notifying connection lost event");
        if (bindedSession.hasWill()) {
            postOffice.fireWill(bindedSession.getWill());
        }
        if (bindedSession.isClean()) {
            LOG.debug("Remove session for client");
            sessionRegistry.remove(bindedSession);
        } else {
            bindedSession.disconnect();
        }
        connected = false;
        //dispatch connection lost to intercept.
        String userName = NettyUtils.userName(channel);
        postOffice.dispatchConnectionLost(clientID,userName);
        LOG.trace("dispatch disconnection: userName={}", userName);
    }

    boolean isConnected() {
        return connected;
    }

    void dropConnection() {
        channel.close().addListener(FIRE_EXCEPTION_ON_FAILURE);
    }

    void processDisconnect(MqttMessage msg) {
        final String clientID = NettyUtils.clientID(channel);
        LOG.trace("Start DISCONNECT");
        if (!connected) {
            LOG.info("DISCONNECT received on already closed connection");
            return;
        }
        bindedSession.disconnect();
        connected = false;
        channel.close().addListener(FIRE_EXCEPTION_ON_FAILURE);
        LOG.trace("Processed DISCONNECT");
        String userName = NettyUtils.userName(channel);
        postOffice.dispatchDisconnection(clientID, userName);
        LOG.trace("dispatch disconnection userName={}", userName);
    }

    void processSubscribe(MqttSubscribeMessage msg) {
        final String clientID = NettyUtils.clientID(channel);
        if (!connected) {
            LOG.warn("SUBSCRIBE received on already closed connection");
            dropConnection();
            return;
        }
        postOffice.subscribeClientToTopics(msg, clientID, NettyUtils.userName(channel), this);
    }

    void sendSubAckMessage(int messageID, MqttSubAckMessage ackMessage) {
        LOG.trace("Sending SUBACK response messageId: {}", messageID);
        channel.writeAndFlush(ackMessage).addListener(FIRE_EXCEPTION_ON_FAILURE);
    }

    private void processUnsubscribe(MqttUnsubscribeMessage msg) {
        List<String> topics = msg.payload().topics();
        String clientID = NettyUtils.clientID(channel);

        LOG.trace("Processing UNSUBSCRIBE message. topics: {}", topics);
        postOffice.unsubscribe(topics, this, msg.variableHeader().messageId());
    }

    void sendUnsubAckMessage(List<String> topics, String clientID, int messageID) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.UNSUBACK, false, AT_MOST_ONCE,
            false, 0);
        MqttUnsubAckMessage ackMessage = new MqttUnsubAckMessage(fixedHeader, from(messageID));

        LOG.trace("Sending UNSUBACK message. messageId: {}, topics: {}", messageID, topics);
        channel.writeAndFlush(ackMessage).addListener(FIRE_EXCEPTION_ON_FAILURE);
        LOG.trace("Client unsubscribed from topics <{}>", topics);
    }

    void processPublish(MqttPublishMessage msg) {
        final MqttQoS qos = msg.fixedHeader().qosLevel();
        final String username = NettyUtils.userName(channel);
        final String topicName = msg.variableHeader().topicName();
        final String clientId = getClientId();
        final int messageID = msg.variableHeader().packetId();
        LOG.trace("Processing PUBLISH message, topic: {}, messageId: {}, qos: {}", topicName, messageID, qos);
        final Topic topic = new Topic(topicName);
        if (!topic.isValid()) {
            LOG.debug("Drop connection because of invalid topic format");
            dropConnection();
        }
        switch (qos) {
            case AT_MOST_ONCE:
                postOffice.receivedPublishQos0(topic, username, clientId, msg);
                break;
            case AT_LEAST_ONCE: {
                postOffice.receivedPublishQos1(this, topic, username, messageID, msg);
                break;
            }
            case EXACTLY_ONCE: {
                bindedSession.receivedPublishQos2(messageID, msg);
                postOffice.receivedPublishQos2(this, msg, username);
                break;
            }
            default:
                LOG.error("Unknown QoS-Type:{}", qos);
                break;
        }
    }

    void sendPublishReceived(int messageID) {
        LOG.trace("sendPubRec invoked, messageID: {}", messageID);
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBREC, false, AT_MOST_ONCE,
            false, 0);
        MqttPubAckMessage pubRecMessage = new MqttPubAckMessage(fixedHeader, from(messageID));
        sendIfWritableElseDrop(pubRecMessage);
    }

    private void processPubRel(MqttMessage msg) {
        final int messageID = ((MqttMessageIdVariableHeader) msg.variableHeader()).messageId();
        bindedSession.receivedPubRelQos2(messageID);
        sendPubCompMessage(messageID);
    }

    void sendPublish(MqttPublishMessage publishMsg) {
        final int packetId = publishMsg.variableHeader().packetId();
        final String topicName = publishMsg.variableHeader().topicName();
        final String clientId = getClientId();
        MqttQoS qos = publishMsg.fixedHeader().qosLevel();
        if (LOG.isTraceEnabled()) {
            LOG.trace("Sending PUBLISH({}) message. MessageId={}, topic={}, payload={}", qos, packetId, topicName,
                DebugUtils.payload2Str(publishMsg.payload()));
        } else {
            LOG.debug("Sending PUBLISH({}) message. MessageId={}, topic={}", qos, packetId, topicName);
        }
        sendIfWritableElseDrop(publishMsg);
    }

    void sendIfWritableElseDrop(MqttMessage msg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("OUT {}", msg.fixedHeader().messageType());
        }
        if (channel.isWritable()) {

            // Sending to external, retain a duplicate. Just retain is not
            // enough, since the receiver must have full control.
            Object retainedDup = msg;
            if (msg instanceof ByteBufHolder) {
                retainedDup = ((ByteBufHolder) msg).retainedDuplicate();
            }

            ChannelFuture channelFuture;
            if (brokerConfig.isImmediateBufferFlush()) {
                channelFuture = channel.writeAndFlush(retainedDup);
            } else {
                channelFuture = channel.write(retainedDup);
            }
            channelFuture.addListener(FIRE_EXCEPTION_ON_FAILURE);
        }
    }

    public void writabilityChanged() {
        if (channel.isWritable()) {
            LOG.debug("Channel is again writable");
            bindedSession.writabilityChanged();
        }
    }

    void sendPubAck(int messageID) {
        LOG.trace("sendPubAck for messageID: {}", messageID);
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBACK, false, AT_MOST_ONCE,
                                                  false, 0);
        MqttPubAckMessage pubAckMessage = new MqttPubAckMessage(fixedHeader, from(messageID));
        sendIfWritableElseDrop(pubAckMessage);
    }

    private void sendPubCompMessage(int messageID) {
        LOG.trace("Sending PUBCOMP message messageId: {}", messageID);
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBCOMP, false, AT_MOST_ONCE, false, 0);
        MqttMessage pubCompMessage = new MqttMessage(fixedHeader, from(messageID));
        sendIfWritableElseDrop(pubCompMessage);
    }

    String getClientId() {
        return NettyUtils.clientID(channel);
    }

    String getUsername() {
        return NettyUtils.userName(channel);
    }

    public void sendPublishRetainedQos0(Topic topic, MqttQoS qos, ByteBuf payload) {
        MqttPublishMessage publishMsg = retainedPublish(topic.toString(), qos, payload);
        sendPublish(publishMsg);
    }

    public void sendPublishRetainedWithPacketId(Topic topic, MqttQoS qos, ByteBuf payload) {
        final int packetId = nextPacketId();
        MqttPublishMessage publishMsg = retainedPublishWithMessageId(topic.toString(), qos, payload, packetId);
        sendPublish(publishMsg);
    }

    private static MqttPublishMessage retainedPublish(String topic, MqttQoS qos, ByteBuf message) {
        return retainedPublishWithMessageId(topic, qos, message, 0);
    }

    private static MqttPublishMessage retainedPublishWithMessageId(String topic, MqttQoS qos, ByteBuf message,
                                                                   int messageId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, qos, true, 0);
        MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(topic, messageId);
        return new MqttPublishMessage(fixedHeader, varHeader, message);
    }

    // TODO move this method in Session
    void sendPublishNotRetainedQos0(Topic topic, MqttQoS qos, ByteBuf payload) {
        MqttPublishMessage publishMsg = notRetainedPublish(topic.toString(), qos, payload);
        sendPublish(publishMsg);
    }

    static MqttPublishMessage notRetainedPublish(String topic, MqttQoS qos, ByteBuf message) {
        return notRetainedPublishWithMessageId(topic, qos, message, 0);
    }

    static MqttPublishMessage notRetainedPublishWithMessageId(String topic, MqttQoS qos, ByteBuf message,
                                                              int messageId) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, qos, false, 0);
        MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(topic, messageId);
        return new MqttPublishMessage(fixedHeader, varHeader, message);
    }

    public void resendNotAckedPublishes() {
        bindedSession.resendInflightNotAcked();
    }

    int nextPacketId() {
        return lastPacketId.updateAndGet(v -> v == 65535 ? 1 : v + 1);
    }

    @Override
    public String toString() {
        return "MQTTConnection{channel=" + channel + ", connected=" + connected + '}';
    }

    InetSocketAddress remoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    public void readCompleted() {
        LOG.debug("readCompleted client CId: {}", getClientId());
        if (getClientId() != null) {
            // TODO drain all messages in target's session in-flight message queue
            bindedSession.flushAllQueuedMessages();
        }
    }

    public void flush() {
        channel.flush();
    }
}
