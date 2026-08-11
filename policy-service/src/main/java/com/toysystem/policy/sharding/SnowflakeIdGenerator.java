package com.toysystem.policy.sharding;

import org.springframework.stereotype.Component;

/**
 * 精简版雪花ID：不用ShardingSphere内置的SNOWFLAKE算法，自己实现，
 * 目的是把"这一行数据落在哪个分片"直接编码进id的bit位里——
 * 拿到id不用查任何表、不用带holder_name，纯位运算就能反解出分片号。
 *
 * 64位long从低位到高位：
 * - 12 bit 序列号（同一毫秒内自增，最多4096个，同一毫秒超量会自旋等下一毫秒）
 * - 2  bit 分片号（0~3，对应 policy_0~policy_3）
 * - 41 bit 时间戳（相对自定义纪元的毫秒数，够用到约2039年）
 *
 * 分片号在调用方传入，必须和 ShardKeyUtil.shardIndexFor(holderName) 算出来的值一致——
 * 由调用方（PolicyService）负责保证这一点，生成器本身不做任何校验。
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z，纯粹为了让时间戳部分数值小一点

    private static final int SEQUENCE_BITS = 12;
    private static final int SHARD_BITS = 2;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + SHARD_BITS;
    private static final int SHARD_SHIFT = SEQUENCE_BITS;

    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS); // 4095
    private static final long SHARD_MASK = ~(-1L << SHARD_BITS); // 0b11

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public synchronized long nextId(int shardIndex) {
        if (shardIndex < 0 || shardIndex > SHARD_MASK) {
            throw new IllegalArgumentException("shardIndex out of range: " + shardIndex);
        }

        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("system clock moved backwards, refusing to generate id");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 这一毫秒的4096个序列号用完了，忙等到下一毫秒
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | ((long) shardIndex << SHARD_SHIFT)
                | sequence;
    }

    public static int extractShard(long id) {
        return (int) ((id >> SHARD_SHIFT) & SHARD_MASK);
    }
}
