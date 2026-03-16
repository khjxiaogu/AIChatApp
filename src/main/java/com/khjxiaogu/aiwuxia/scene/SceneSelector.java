/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
 * 场景选择器，用于根据提供的场景数据（键值对）动态选择对应的场景标识。
 * 该类支持多层嵌套的选择逻辑：通过一系列谓词条件进行过滤，再依次尝试子选择器，
 * 最终返回匹配的场景字符串。如果没有任何子选择器匹配，则返回默认场景。
 */
public class SceneSelector {

    /**
     * 静态内部类，表示一个场景数据谓词条件。
     * 该条件定义了一个键和一组允许的值，用于测试给定的场景数据中该键的值是否在允许集合中。
     */
    public static class Predicate {
        /** 要检查的场景数据键名 */
        String key;
        /** 允许的值列表，只要场景数据中该键的值匹配列表中的任意一个，条件即成立 */
        List<String> values;

        /**
         * 构造一个只包含单个允许值的谓词。
         *
         * @param name  要检查的键名
         * @param value 允许的单个值
         */
        public Predicate(String name, String value) {
            key = name;
            values = new ArrayList<>();
            values.add(value);
        }

        /**
         * 构造一个包含多个允许值的谓词（可变参数形式）。
         *
         * @param name   要检查的键名
         * @param values 允许的多个值
         */
        public Predicate(String name, String... values) {
            key = name;
            this.values = new ArrayList<>();
            for (String value : values)
                this.values.add(value);
        }

        /**
         * 构造一个包含预定义值列表的谓词。
         *
         * @param name   要检查的键名
         * @param values 允许的值列表
         */
        public Predicate(String name, List<String> values) {
            key = name;
            this.values = values;
        }

        /**
         * 测试给定的场景数据是否满足该谓词条件。
         *
         * @param sceneData 场景数据映射（键值对）
         * @return 如果场景数据中指定键的值在允许值列表中，则返回 true；否则返回 false
         */
        public boolean test(Map<String, String> sceneData) {
            return values.contains(sceneData.get(key));
        }
    }

    /** 当前选择器的谓词列表，所有谓词必须全部通过才能继续向下匹配 */
    public List<Predicate> predicates;
    /** 子选择器列表，当当前谓词全部通过后，将按顺序尝试这些子选择器 */
    public List<SceneSelector> selectors;
    /** 默认场景标识，当所有子选择器均未匹配时返回此值 */
    public String scene;

    /**
     * 构造一个空的场景选择器，初始化谓词列表和子选择器列表。
     */
    public SceneSelector() {
        super();
        this.predicates = new ArrayList<>();
        this.selectors = new ArrayList<>();
    }

    /**
     * 根据给定的场景数据获取匹配的场景标识。
     * 执行流程：
     * 1. 依次检查当前选择器的所有谓词，如果有任何一个谓词不通过，则返回 null。
     * 2. 遍历所有子选择器，对每个子选择器递归调用本方法，如果某个子选择器返回非 null 值，则立即返回该值。
     * 3. 如果所有子选择器都返回 null，则返回当前选择器的默认场景 {@link #scene}。
     *
     * @param sceneData 场景数据映射
     * @return 匹配的场景标识字符串；如果没有任何匹配且默认场景为 null，则可能返回 null
     */
    public String getSceneData(Map<String, String> sceneData) {
        for (Predicate pred : predicates) {
            if (!pred.test(sceneData))
                return null;
        }
        for (SceneSelector filter : selectors) {
            String data = filter.getSceneData(sceneData);
            if (data != null)
                return data;
        }
        return scene;
    }
}
