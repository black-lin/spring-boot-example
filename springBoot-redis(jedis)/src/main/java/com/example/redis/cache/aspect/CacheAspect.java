package com.example.redis.cache.aspect;

import com.example.redis.cache.CacheContext;
import com.example.redis.cache.SerializableUtil;
import com.example.redis.cache.annotation.CacheData;
import com.example.redis.cache.annotation.IgnoreCache;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;


@Aspect
@Component
public class CacheAspect {

    private static final Log log = LogFactory.getLog(CacheAspect.class);
    private static final String EMPTY = "";
    private static final String POINT = ".";

    private ReentrantLock lock = new ReentrantLock();
    @Resource
    private JedisPool jedisPool;


    /**
     * 拦截添加缓存注解的方法
     *
     * @param pjpParam
     * @return
     * @throws Throwable
     */
    @Around("@annotation(com.example.redis.cache.annotation.CacheData)")
    public Object doCache(ProceedingJoinPoint pjpParam) throws Throwable {
        Signature sig = pjpParam.getSignature();
        if (!(sig instanceof MethodSignature)) {
            return pjpParam.proceed(pjpParam.getArgs());
        }
        //方法实例
        Method method = ((MethodSignature) pjpParam.getSignature()).getMethod();
        //获取到方法上的注解
        CacheData cacheData = method.getAnnotation(CacheData.class);
        //注解为空
        if (null == cacheData) {
            return pjpParam.proceed(pjpParam.getArgs());
        }
        //方法类名
        String className = pjpParam.getTarget().getClass().getCanonicalName();
        //方法名
        String methodName = method.getName();
        //缓存key
        byte[] cacheKey = this.getCacheKey(cacheData, className, methodName, pjpParam).getBytes();

        //已设置不读取缓存
        if (null != CacheContext.refreshFlag.get() && CacheContext.refreshFlag.get()) {
            return pjpParam.proceed(pjpParam.getArgs());
        }

        //获取jedis客户端连接
        Jedis jedis = jedisPool.getResource();
        Object value = null;
        //缓存中读取
        byte[] cacheByteValue = jedis.get(cacheKey);
        if (null != cacheByteValue && cacheByteValue.length > 0) {
            //反序列化对象
            value = SerializableUtil.deserialize(cacheByteValue);
        }
        //缓存中存在直接返回
        if (!ObjectUtils.isEmpty(value) && cacheKey.equals(value)) {
            log.info("[ CacheAspect ] >> cacheKey:" + new String(cacheKey) + ",class:" + className + ",method:" + methodName + " first get data from cache ");
            return value;
        }
        //如果设置了存储null值，并且key存在 > 则直接返回 null
        if (cacheData.storageNullFlag() && jedis.exists(cacheKey)) {
            log.info("[ CacheAspect ] >> cacheKey:" + new String(cacheKey) + ",class:" + className + ",method:" + methodName + " first get data from cache  is null ");
            return null;
        }

        //若缓存中不存在
        //加锁防止大量穿透
        lock.lock();
        try {
            //二次尝试从缓存中读取
            //byte[] cacheByteValueSecond = this.getFromRedis(cacheKey);
            byte[] cacheByteValueSecond = jedis.get(cacheKey);
            if (null != cacheByteValueSecond && cacheByteValueSecond.length > 0) {
                //反序列化对象
                value = SerializableUtil.deserialize(cacheByteValueSecond);
            }
            //缓存中存在直接返回
            if (!ObjectUtils.isEmpty(value) && cacheKey.equals(value)) {
                log.info("[ CacheAspect ] >> cacheKey:" + new String(cacheKey) + ",class:" + className + ",method:" + methodName + " second get data from cache ");
                return value;
            }
            //如果设置了允许存储null值，并且key存在 > 则直接返回 null
            if (cacheData.storageNullFlag() && jedis.exists(cacheKey)) {
                log.info("[ CacheAspect ] >> cacheKey:" + new String(cacheKey) + ",class:" + className + ",method:" + methodName + " second get data from cache  is null ");
                return null;
            }

            //执行方法-并获得返回值
            value = pjpParam.proceed(pjpParam.getArgs());

            //返回值不为空-存入缓存并返回
            if (null != value) {
                //setToRedis(cacheKey, value, getExpireTime(cacheData));
                jedis.setex(cacheKey, getExpireTime(cacheData), SerializableUtil.serialize(value));

                return value;
            }

            //返回值不为空-是否需要存储空对象
            if (cacheData.storageNullFlag()) {
                //存入缓存,value-也存储key
                //setToRedis(cacheKey, cacheKey, getExpireTime(cacheData));
                jedis.setex(cacheKey, getExpireTime(cacheData), SerializableUtil.serialize(value));
                return null;
            }
        } finally {
            //解锁
            lock.unlock();
            //关闭
            if (jedis.isConnected()) {
                jedis.close();
                log.info("[ CacheAspect ] >> cacheKey:" + new String(cacheKey) + ",class:" + className + ",method:" + methodName + " close jedis client ");
            }
        }
        return null;
    }

    /**
     * 生成缓存key
     *
     * @param cacheData
     * @param className
     * @param methodName
     * @param pjpParam
     * @return
     */
    private String getCacheKey(CacheData cacheData, String className, String methodName, ProceedingJoinPoint pjpParam) {
        //缓存key前缀
        String keyPrefix = cacheData.keyPrefix();
        if (EMPTY.equals(keyPrefix)) {
            keyPrefix = methodName;
        }
        //方法全路径（类名+方法名）
        String methodPath = className + POINT + methodName;
        if (pjpParam.getArgs() == null || pjpParam.getArgs().length == 0) {
            return keyPrefix + POINT + DigestUtils.md5Hex(methodPath);
        }

        Annotation[][] annotations = ((MethodSignature) pjpParam.getSignature()).getMethod().getParameterAnnotations();
        List<Integer> ignoreList = new ArrayList<>();
        for (int i = 0; i < annotations.length; i++) {
            if (annotations[i].length <= 0) {
                continue;
            }
            for (int j = 0; j < annotations[i].length; j++) {
                if (annotations[i][j].annotationType().equals(IgnoreCache.class)) {
                    ignoreList.add(i);
                }
            }
        }
        int i = 0;
        StringBuilder paramKey = new StringBuilder();
        for (Object obj : pjpParam.getArgs()) {
            if (ignoreList.contains(i++)) {
                continue;
            }
            if (obj != null) {
                paramKey.append(obj.toString());
            } else {
                paramKey.append("NULL");
            }
        }
        return keyPrefix + POINT + DigestUtils.md5Hex(methodPath + paramKey);
    }


    /**
     * 计算过期时间 如果缓存设置了需要延迟失效，取设置的延迟时间1-2倍之间的一个随机值作为真正的延迟时间值
     */
    private int getExpireTime(CacheData cacheData) {
        int expire = cacheData.expireTime();

        if (expire == 0) {
            expire = (int) (60 * 60 * 24 - ((System.currentTimeMillis() / 1000 + 8 * 3600) % (60 * 60
                    * 24)));
        }

        int offset = 0;
        return expire + offset;
    }
    

//    protected void setToRedis(byte[] key, Object value, int expire) {
//        if (expire == 0) {
//            expire = (int) (60 * 60 * 24 - ((System.currentTimeMillis() / 1000 + 8 * 3600) % (60 * 60 * 24)));
//        }
//
//        Jedis jedis = null;
//        try {
//            jedis = jedisPool.getResource();
//            String setex = jedis.setex(key, expire, SerializableUtil.serialize(value));
//            System.out.println(setex);
//        } catch (Exception e) {
//
//        } finally {
//            try {
//                if (jedis != null) {
//                    if (jedis.isConnected()) {
//                        jedis.close();
//                    }
//                }
//            } catch (Exception e) {
//            }
//        }
//    }

//    protected byte[] getFromRedis(byte[] key) {
//        if (key == null) {
//            return null;
//        }
//        Jedis jedis = null;
//        try {
//            jedis = jedisPool.getResource();
//            return jedis.get(key);
//        } catch (Exception e) {
//            // LOGGER.error("[Redis-invoke] Exception {} - {}", ex.getMessage(), ex);
//        } finally {
//            if (jedis != null) {
//                if (jedis.isConnected()) {
//                    jedis.close();
//                }
//            }
//        }
//        return null;
//    }

//    protected Boolean exists(byte[] key) {
//        if (key == null) {
//            return false;
//        }
//        Jedis jedis = null;
//        try {
//            jedis = jedisPool.getResource();
//            return jedis.exists(key);
//        } catch (Exception e) {
//
//        } finally {
//            if (jedis != null) {
//                if (jedis.isConnected()) {
//                    jedis.close();
//                }
//            }
//        }
//        return false;
//    }

}