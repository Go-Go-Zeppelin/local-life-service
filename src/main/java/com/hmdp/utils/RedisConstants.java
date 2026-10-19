package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_VOUCHER_LIST_KEY = "cache:voucher:list:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 秒杀预占记录。它把 orderId 与 voucherId/userId 绑定，便于消息丢失时安全补偿。 */
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";
    public static final String SECKILL_RESERVATION_PENDING_KEY = "seckill:reservation:pending";
    public static final String SECKILL_CLOSED_RELEASE_KEY = "seckill:closed:released:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
