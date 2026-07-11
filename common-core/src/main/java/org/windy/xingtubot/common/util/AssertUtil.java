package org.windy.xingtubot.common.util;

import java.util.Collection;

/**
 * 参数断言工具类——在方法入口做防御性校验。
 *
 * <p>取代散落在各处的 {@code if (xxx == null) throw ...} 样板。
 * 统一异常类型（均抛出 {@link IllegalArgumentException}），错误信息格式一致。
 *
 * <pre>
 *   AssertUtil.notNull(config, "config");
 *   AssertUtil.notEmpty(name, "name");
 *   AssertUtil.isTrue(port > 0, "port 必须大于 0，实际: %s", port);
 * </pre>
 */
public final class AssertUtil {

    private AssertUtil() {
    }

    // ─────────────────────── 非空对象 ───────────────────────

    /**
     * 断言对象非 {@code null}。
     *
     * @param obj  待检查对象
     * @param name 参数名称（用于错误提示）
     * @throws IllegalArgumentException 当 {@code obj == null} 时抛出
     */
    public static void notNull(Object obj, String name) {
        if (obj == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    /**
     * 断言对象非 {@code null}，支持格式化消息。
     *
     * @param obj     待检查对象
     * @param format  错误消息模板（如 {@code "param %s is null"}）
     * @param args    模板参数
     * @throws IllegalArgumentException 当 {@code obj == null} 时抛出
     */
    public static void notNull(Object obj, String format, Object... args) {
        if (obj == null) {
            throw new IllegalArgumentException(String.format(format, args));
        }
    }

    // ─────────────────────── 非空字符串 ─────────────────────

    /**
     * 断言字符串非 {@code null} 且长度不为 0。
     *
     * <p>注意：不去除前后空白，纯空格字符串视为"非空"。需要 trim 后判断
     * 请使用 {@link #notBlank(String, String)}。
     *
     * @param str  待检查字符串
     * @param name 参数名称
     * @throws IllegalArgumentException 当 {@code str == null || str.isEmpty()}
     */
    public static void notEmpty(String str, String name) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    /**
     * 断言字符串非 {@code null} 且去除前后空白后不为空。
     *
     * @param str  待检查字符串
     * @param name 参数名称
     * @throws IllegalArgumentException 当 {@code str == null || str.trim().isEmpty()}
     */
    public static void notBlank(String str, String name) {
        if (str == null || str.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    // ─────────────────────── 非空集合 ───────────────────────

    /**
     * 断言集合非 {@code null} 且不为空。
     *
     * @param coll 待检查集合
     * @param name 参数名称
     * @throws IllegalArgumentException 当 {@code coll == null || coll.isEmpty()}
     */
    public static void notEmpty(Collection<?> coll, String name) {
        if (coll == null || coll.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    /**
     * 断言数组非 {@code null} 且长度不为 0。
     *
     * @param array 待检查数组
     * @param name  参数名称
     * @throws IllegalArgumentException 当 {@code array == null || array.length == 0}
     */
    public static void notEmpty(Object[] array, String name) {
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    // ─────────────────────── 无 null 元素 ───────────────────

    /**
     * 断言集合中不含 {@code null} 元素。
     *
     * @param coll 待检查集合
     * @param name 参数名称
     * @throws IllegalArgumentException 当集合为 {@code null}，或包含 {@code null} 元素时抛出
     */
    public static void noNullElements(Collection<?> coll, String name) {
        notNull(coll, name);
        int i = 0;
        for (Object e : coll) {
            if (e == null) {
                throw new IllegalArgumentException(name + " contains null element at index " + i);
            }
            i++;
        }
    }

    /**
     * 断言数组中不含 {@code null} 元素。
     *
     * @param array 待检查数组
     * @param name  参数名称
     * @throws IllegalArgumentException 当数组为 {@code null}，或包含 {@code null} 元素时抛出
     */
    public static void noNullElements(Object[] array, String name) {
        notNull(array, name);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) {
                throw new IllegalArgumentException(name + " contains null element at index " + i);
            }
        }
    }

    // ─────────────────────── 布尔条件 ───────────────────────

    /**
     * 断言布尔表达式为 {@code true}。
     *
     * @param expression 布尔表达式
     * @param message    失败时的异常信息
     * @throws IllegalArgumentException 当 {@code expression == false} 时抛出
     */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言布尔表达式为 {@code true}，支持格式化消息。
     *
     * @param expression 布尔表达式
     * @param format     错误消息模板
     * @param args       模板参数
     * @throws IllegalArgumentException 当 {@code expression == false} 时抛出
     */
    public static void isTrue(boolean expression, String format, Object... args) {
        if (!expression) {
            throw new IllegalArgumentException(String.format(format, args));
        }
    }

    /**
     * 断言所有对象均非 {@code null}。
     * <p>适用于批量检查，如方法有多个参数需要做 null 校验。
     *
     * @param names 交替排列的参数名与参数值（如 {@code "a", a, "b", b}）
     * @throws IllegalArgumentException 当任一参数为 {@code null} 时抛出
     */
    public static void nonNullAll(Object... names) {
        if (names.length % 2 != 0) {
            throw new IllegalArgumentException("names length must be even (name-value pairs)");
        }
        for (int i = 0; i < names.length; i += 2) {
            String name = (String) names[i];
            Object value = names[i + 1];
            if (value == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
        }
    }
}
