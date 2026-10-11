package com.hotel.hms.common;

import java.security.SecureRandom;

/**
 * 不可枚举的随机令牌（房间二维码用）。
 *
 * <p>用 {@link SecureRandom} 而非 {@code Random}：扫码页是免登录入口，
 * 令牌一旦可被预测，任何人都能给任意房间挂账。
 * 32 位 Base62 的取值空间约 62^32 ≈ 2.3×10^57，穷举不可行。
 */
public final class RandomToken {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomToken() {
    }

    /** 生成 32 位 Base62 随机串 */
    public static String next() {
        return next(32);
    }

    public static String next(int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }
}
