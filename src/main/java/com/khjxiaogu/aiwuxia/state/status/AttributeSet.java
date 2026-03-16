package com.khjxiaogu.aiwuxia.state.status;

import java.util.Map;

/**
 * 表示一组键值对属性的集合，每个键和值都是字符串类型。
 * 该接口扩展了 {@link Iterable}，允许遍历其中的每个属性条目（键值对）。
 * 它提供了类似于 {@link java.util.Map} 的操作，但专注于字符串键值对，
 * 并且可能用于特定的上下文，如配置、元数据等。
 */
public interface AttributeSet extends Iterable<Map.Entry<String, String>> {

    /**
     * 清空所有属性，移除集合中的所有键值对。
     */
    void clear();

    /**
     * 如果指定的键尚未与任何值关联，则将其与给定值关联。
     *
     * @param key   要关联的键，不能为 null
     * @param value 要关联的值，如果为 null 则可能移除该键（取决于实现）
     * @return 之前与该键关联的值，如果该键没有映射关系，则返回 null
     */
    String putIfAbsent(String key, String value);

    /**
     * 检查集合中是否包含指定键的属性。
     *
     * @param key 要检查的键，不能为 null
     * @return 如果存在该键的属性则返回 true，否则返回 false
     */
    boolean contains(String key);

    /**
     * 获取指定键关联的值。
     *
     * @param key 要获取值的键，不能为 null
     * @return 与该键关联的值，如果该键不存在则返回 null
     */
    String get(String key);

    /**
     * 将指定键与指定值关联。如果该键之前已有映射，则旧值将被替换。
     *
     * @param key   要关联的键，不能为 null
     * @param val   要关联的值，可以为 null（表示移除该键的映射？取决于实现）
     * @return 之前与该键关联的值，如果该键没有映射关系，则返回 null
     */
    String put(String key, String val);

    /**
     * 判断集合是否为空（即不包含任何属性）。
     *
     * @return 如果集合为空则返回 true，否则返回 false
     */
    boolean isEmpty();

    /**
     * 返回包含所有属性的 {@link Map} 视图。
     * 返回的 Map 可能是不可修改的视图，也可能是底层数据的副本，具体取决于实现。
     * 对该 Map 的修改可能会影响原始属性集，反之亦然。
     *
     * @return 一个 {@link Map} 实例，包含所有键值对
     */
    Map<String, String> getAsMap();
}