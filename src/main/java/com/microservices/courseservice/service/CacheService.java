package com.microservices.courseservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private final StringRedisTemplate redisTemplate;

    /**
     * Сохранить значение в кеш с TTL
     */
    public void set(String key, String value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
            log.debug("Cached value for key: {}", key);
        } catch (Exception e) {
            log.error("Error caching value for key: {}", key, e);
        }
    }

    /**
     * Получить значение из кеша
     */
    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Error getting cached value for key: {}", key, e);
            return null;
        }
    }

    /**
     * Удалить значение из кеша
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Deleted cache key: {}", key);
        } catch (Exception e) {
            log.error("Error deleting cache key: {}", key, e);
        }
    }

    /**
     * Проверить существование ключа
     */
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Error checking cache key: {}", key, e);
            return false;
        }
    }

    /**
     * Увеличить счетчик
     */
    public Long increment(String key) {
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.error("Error incrementing counter for key: {}", key, e);
            return null;
        }
    }

    /**
     * Увеличить счетчик с TTL
     */
    public Long increment(String key, long timeout, TimeUnit unit) {
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, timeout, unit);
            return value;
        } catch (Exception e) {
            log.error("Error incrementing counter for key: {}", key, e);
            return null;
        }
    }

    /**
     * Получить счетчик
     */
    public Long getCounter(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            log.error("Error getting counter for key: {}", key, e);
            return 0L;
        }
    }
}

