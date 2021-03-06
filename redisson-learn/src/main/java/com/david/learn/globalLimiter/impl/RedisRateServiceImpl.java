package com.david.learn.globalLimiter.impl;

import com.david.learn.globalLimiter.RedisRateService;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.io.ByteStreams;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import redis.clients.jedis.Jedis;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by jiakang on 2017/12/5.
 * 基于Redis的分布式限速服务
 *
 * 示例：
 *  spring中添加：
 *      <bean id="redisRateService" class="com.david.learn.globalLimiter.impl.RedisRateServiceImpl">
 *          <constructor-arg name="jedisAddress" value="10.75.0.27:6380"/>
 *          <constructor-arg name="rateLimitKey" value="indirect_material"/>
 *          <constructor-arg name="maxPermits" value="3000"/>
 *          <constructor-arg name="defaultMcSize" value="15"/>
 *          <!--<constructor-arg name="monitorPeriodInMillis" value="50"/>-->
 *      </bean>
 *  代码中注入bean：
 *      @Resource
 *      private RedisRateService redisRateService;
 *  在需要限速的地方执行：
 *      redisRateService.acquire(); 或
 *      redisRateService.acquire(int count);
 *
 * 注意：
 *  如果需要更改要限速的值，则只需修改redis中${rateLimitKey}_m的值即可，最多一分钟之后即可生效，无需重新上线。
 *  ${rateLimitKey}_m的值具有永久性，下次上线时会覆盖spring代码本地注入的maxPermits值。
 *  如果只是临时更改，请一定记得在下次上线前删掉redis中${rateLimitKey}_m的值。
 *
 * @author jiakang
 */
@Slf4j
public class RedisRateServiceImpl implements RedisRateService {

    /** 最大qps的key后缀 */
    private static final String MAX_PERMITS_SUFFIX = "_m";
    /** 当前已用许可数的key后缀 */
    private static final String USED_PERMITS_SUFFIX = "_u";

    public RedisRateServiceImpl(String jedisAddress, String rateLimitKey, int maxPermits, int defaultMcSize) {
        this.jedisAddress = jedisAddress;
        this.rateLimitKey = rateLimitKey;
        this.maxPermits = maxPermits;
        this.defaultMcSize = defaultMcSize;
    }

    public RedisRateServiceImpl(String jedisAddress, String rateLimitKey, int maxPermits, int defaultMcSize, int monitorPeriodInMillis) {
        this.jedisAddress = jedisAddress;
        this.rateLimitKey = rateLimitKey;
        this.maxPermits = maxPermits;
        this.defaultMcSize = defaultMcSize;
        this.monitorPeriodInMillis = monitorPeriodInMillis;
    }

    /**
     * jedis端口地址
     */
    private String jedisAddress;
    /**
     * 限速使用的key
     * 此key一定要保证在一个${jedisAddress}端口中唯一
     */
    private String rateLimitKey;
    /**
     * 限制的最大qps
     */
    private volatile int maxPermits;
    /**
     * 默认服务机器数（用于lua脚本出错时设置默认的单机qps）
     */
    private int defaultMcSize;
    /**
     * 从redis获取许可的执行间隔
     */
    private int monitorPeriodInMillis = 50;


    /** jedis客户端 */
    private Jedis jedis;

    /** 每秒钟从redis获取许可执行次数 */
    private volatile int applyCountsPerSecond;

    /** 每次从redis申请的许可数 */
    private volatile int applyPermitsPerCount;

    /** 默认每次从redis申请来的许可数，以maxPermits和defaultMcSize计算 */
    private volatile int defaultApplyPermitsPerCount;

    /** redis最大qps的key */
    private String maxPermitsKey;

    /** redis当前已用许可数的key */
    private String usedPermitsKey;

    /** lua脚本加载后的shaKey */
    private String shaKey;

    /** 本地当前存储的许可数 */
    private final AtomicInteger storedPermits = new AtomicInteger();

    @PostConstruct
    public void init() {
        checkSotArgs();
        initLocalArgs();
        loadLuaScript();
        startMonitorMaxPermitsTask();
        startMonitorTokenTask();
    }

    /**
     * 外部参数校验
     */
    private void checkSotArgs() {
        Preconditions.checkArgument(Strings.isNotEmpty(jedisAddress), String.format("jedisAddress(%s) can not be empty", jedisAddress));
        Preconditions.checkArgument(Strings.isNotEmpty(rateLimitKey), String.format("rateLimitKey(%s) can not be empty", rateLimitKey));
        Preconditions.checkArgument(maxPermits > 0, String.format("maxPermits(%s) should be positive", maxPermits));
        Preconditions.checkArgument(defaultMcSize > 0, String.format("defaultMcSize(%s) should be positive", defaultMcSize));
        Preconditions.checkArgument(monitorPeriodInMillis > 0, String.format("applyPermitsPerCount(%s) should be positive", monitorPeriodInMillis));
    }

    /**
     * 初始化本地参数
     */
    private void initLocalArgs() {
        String[] split = jedisAddress.split(":");
        String host = split[0];
        int length = split.length;
        if (length == 1) {
            jedis = new Jedis(host);
        } else if (length == 2) {
            int port = Integer.parseInt(split[1]);
            jedis = new Jedis(host, port);
        }
        Preconditions.checkArgument(jedis != null, String.format("jedis(%s) can not be null", jedis));

        maxPermitsKey = rateLimitKey + MAX_PERMITS_SUFFIX;
        usedPermitsKey = rateLimitKey + USED_PERMITS_SUFFIX;

        resync();
    }

    /**
     * 加载lua脚本
     */
    private void loadLuaScript() {
        String luaPath = "lua/rate_limit.lua";
        InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream(luaPath);
        try {
            String script = new String(ByteStreams.toByteArray(resourceAsStream), Charsets.UTF_8);
            shaKey = jedis.scriptLoad(script);
        } catch (IOException e) {
            log.warn("loadLuaScript error, luaPath:{}", luaPath, e);
        }
    }

    /**
     * 开始监控redis的最大qps的task，用于实时更新本地的maxPermits
     * 每分钟执行1次
     * 如果想修改限制的最大qps时，直接通过redis改maxPermitsKey的值，免上线
     */
    private void startMonitorMaxPermitsTask() {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                String maxPermitsStr = jedis.get(maxPermitsKey);
                if (maxPermitsStr == null) {
                    jedis.set(maxPermitsKey, Integer.toString(maxPermits));
                    log.info("value of maxPermitsKey is null, so set maxPermitsKey to default maxPermits. maxPermitsKey:{}, maxPermits:{}", maxPermitsKey, maxPermits);
                    return;
                }
                int newMaxPermits = Integer.parseInt(maxPermitsStr);
                if (newMaxPermits == maxPermits) {
                    log.info("values of newMaxPermits and maxPermits are equal, so not update maxPermits. maxPermitsKey:{}, newMaxPermits:{}, maxPermits:{}", maxPermitsKey, newMaxPermits, maxPermits);
                    return;
                }
                resetMaxPermits(newMaxPermits);
                log.info("detected new maxPermits value from redis, so update local maxPermits and recalculate applyPermitsPerCount. maxPermitsKey:{}, newMaxPermits:{}, maxPermits:{}", maxPermitsKey, newMaxPermits, maxPermits);
            }
        }, 0, TimeUnit.MINUTES.toMillis(1));
    }

    /**
     * 开始监控redis的许可数的task，定时从redis申请许可
     * monitorPeriodInMillis由 1000 / monitorPeriodInMillis 计算得出
     */
    private void startMonitorTokenTask() {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                int currentStoredPermits = storedPermits.get();
                //当本地的许可数还没有消耗完时，不去从redis申请许可，避免远程许可过多地被本机无端消耗
                if (currentStoredPermits > 0) {
//                    log.info("storedPermits is remaining, so not pre take token from redis, currentStoredPermits:{}", currentStoredPermits);
                    return;
                }

                int realIncrCount = applyTokenFromRedis();
                if (realIncrCount > 0) {
                    //将redis申请来的许可加入本地存数的许可数里面
                    storedPermits.addAndGet(realIncrCount);
                    //通知所有等待的线程
                    synchronized (mutexWait) {
                        mutexWait.notifyAll();
                    }
                }
            }
        }, monitorPeriodInMillis, monitorPeriodInMillis);
    }

    /**
     * 执行lua脚本，从redis申请许可
     * @return 申请到的许可数
     */
    private int applyTokenFromRedis() {
        int realAppliedPermits;
        try {
            Long realIncrCount = (Long) jedis.evalsha(shaKey, 1, usedPermitsKey, Integer.toString(maxPermits), Integer.toString(applyPermitsPerCount));
            realAppliedPermits = realIncrCount.intValue();
        } catch (Exception e) {
            realAppliedPermits = defaultApplyPermitsPerCount;
            log.warn("applyTokenFromRedis error, so use default. usedPermitsKey:{}, maxPermits:{}, applyPermitsPerCount:{}", usedPermitsKey, maxPermits, applyPermitsPerCount, e);
        }
        return realAppliedPermits;
    }

    /**
     * 请求1个许可
     * 等同于 acquire(1)
     * @return 许可等待时间 in millis
     */
    @Override
    public long acquire() {
        return acquire(1);
    }

    /**
     * 请求许可
     * @param permits 请求的许可数
     * @return 许可等待时间 in millis
     */
    @Override
    public long acquire(int permits) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        synchronized (mutexEntry) {
            int current = storedPermits.get();
            //如果申请的许可数大于本地当前剩余许可数，则先获取剩余的许可，休息片刻后，再次从本地获取许可，直到所有许可都获取到
            while (permits > current) {
                permits -= current;
                storedPermits.addAndGet(-current);
                waitOnMutex();
                current = storedPermits.get();
            }
            //从本地许可数里面减去申请的许可
            storedPermits.addAndGet(-permits);
        }

        stopwatch.stop();
        return stopwatch.elapsed(TimeUnit.MILLISECONDS);
    }

    /** 当前线程在mutex()上等待 */
    private void waitOnMutex() {
        synchronized (mutexWait) {
            try {
                mutexWait.wait();
            } catch (InterruptedException e) {
                log.warn("waitOnMutex error", e);
            }
        }
    }

    /** 互斥锁对象 */
    private final Object mutexWait = new Object();

    private final Object mutexEntry = new Object();

//    /**
//     * 获取互斥锁
//     * 参考guava
//     */
//    private Object mutex() {
//        Object mutex = mutexDoNotUseDirectly;
//        if (mutex == null) {
//            synchronized (this) {
//                mutex = mutexDoNotUseDirectly;
//                if (mutex == null) {
//                    mutexDoNotUseDirectly = mutex = new Object();
//                }
//            }
//        }
//        return mutex;
//    }

    /**
     * 重设全局最大许可的qps数
     * @param maxPermits 全局最大许可的qps数
     */
    @Override
    public void resetMaxPermits(int maxPermits) {
        this.maxPermits = maxPermits;
        resync();
    }

    /**
     * 重置一些关联属性值
     */
    private void resync() {
        applyCountsPerSecond = 1000 / monitorPeriodInMillis;
        applyPermitsPerCount = maxPermits / applyCountsPerSecond;
        defaultApplyPermitsPerCount = (maxPermits / defaultMcSize) / applyCountsPerSecond;
        log.info("resync done, monitorPeriodInMillis:{}, applyCountsPerSecond:{}, maxPermits:{}, applyPermitsPerCount:{}", monitorPeriodInMillis, applyCountsPerSecond, maxPermits, applyPermitsPerCount);
    }

    public String getJedisAddress() {
        return jedisAddress;
    }

    public String getRateLimitKey() {
        return rateLimitKey;
    }

    public int getMaxPermits() {
        return maxPermits;
    }

    public int getDefaultMcSize() {
        return defaultMcSize;
    }

    public int getMonitorPeriodInMillis() {
        return monitorPeriodInMillis;
    }

    public Jedis getJedis() {
        return jedis;
    }

    public int getApplyCountsPerSecond() {
        return applyCountsPerSecond;
    }

    public int getApplyPermitsPerCount() {
        return applyPermitsPerCount;
    }

    public int getDefaultApplyPermitsPerCount() {
        return defaultApplyPermitsPerCount;
    }

    public String getMaxPermitsKey() {
        return maxPermitsKey;
    }

    public String getUsedPermitsKey() {
        return usedPermitsKey;
    }

    public String getShaKey() {
        return shaKey;
    }

    public AtomicInteger getStoredPermits() {
        return storedPermits;
    }

    public void setMonitorPeriodInMillis(int monitorPeriodInMillis) {
        this.monitorPeriodInMillis = monitorPeriodInMillis;
        resync();
    }
}
