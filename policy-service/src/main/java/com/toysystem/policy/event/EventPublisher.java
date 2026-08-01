package com.toysystem.policy.event;

/**
 * 谁来触发发送事件（目前是业务代码在CRUD时手动调用）后续P9阶段会换成
 * Canal监听MySQL binlog触发；这个接口和下游消费者都不用跟着变。
 */
public interface EventPublisher {
    void publish(PolicyEvent event);
}
