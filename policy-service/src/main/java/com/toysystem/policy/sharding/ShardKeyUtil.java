package com.toysystem.policy.sharding;

/**
 * 分片号计算——必须和 shardingsphere-config.yaml 里INLINE算法的表达式
 * （policy_${Math.abs(holder_name.hashCode()) % 4}）算的是同一个公式。
 * 两处分开维护：这边是Java代码算、ShardingSphere那边是Groovy表达式算，
 * 输入同一个holder_name值，理论上必然算出同一个结果（Groovy的.hashCode()
 * 就是调用String.hashCode()，跟Java这边完全一致），但这是两份独立代码，
 * 改分片数或者算法时两边都要一起改，不然ID里编码的分片号会跟实际路由结果对不上。
 */
public final class ShardKeyUtil {

    public static final int SHARD_COUNT = 4;

    private ShardKeyUtil() {
    }

    public static int shardIndexFor(String holderName) {
        return Math.abs(holderName.hashCode()) % SHARD_COUNT;
    }
}
