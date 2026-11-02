package dev.kizuna.meridian.peddler.test;

/** 26.2 的 Fabric 注解默认时限过短，数据验证统一使用 100 tick。 */
final class PeddlerGameTestTicks {
    static final int DEFAULT_TIMEOUT = 100;

    private PeddlerGameTestTicks() {
        // 只放 GameTest 时限常量。
    }
}
