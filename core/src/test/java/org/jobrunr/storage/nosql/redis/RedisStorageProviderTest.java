package org.jobrunr.storage.nosql.redis;

import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.StorageProviderTest;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.jobrunr.utils.resilience.RateLimiter.Builder.rateLimit;

@Testcontainers
public class RedisStorageProviderTest extends StorageProviderTest {

    @Container
    private static GenericContainer redisContainer = new GenericContainer("redis").withExposedPorts(6379);

    @Override
    protected void cleanup() {
        try (Jedis jedis = getJedisPool().getResource()) {
            jedis.flushDB();
        }
    }

    @Override
    protected StorageProvider getStorageProvider() {
        final RedisStorageProvider redisStorageProvider = new RedisStorageProvider(getJedisPool(), rateLimit().withoutLimits());
        redisStorageProvider.setJobMapper(new JobMapper(new JacksonJsonMapper()));
        return redisStorageProvider;
    }

    private JedisPool getJedisPool() {
        return new JedisPool(redisContainer.getContainerIpAddress(), redisContainer.getMappedPort(6379));
    }
}
