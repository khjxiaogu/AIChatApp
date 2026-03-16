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

import com.khjxiaogu.aiwuxia.scene.SceneSelector.Predicate;

/**
 * 场景构建器的抽象基类，用于以流式方式构建 {@link SceneSelector} 对象。
 * 该类支持嵌套构建，通过泛型参数 {@code T} 表示父构建器的类型，
 * {@code O} 表示当前构建器自身的类型，以实现方法链中返回正确子类型。
 *
 * @param <T> 父构建器的类型（即调用 {@link #end()} 返回的对象类型）
 * @param <O> 当前构建器自身的类型（用于方法链返回自身）
 */
@SuppressWarnings("unchecked")
public abstract class SceneBuilder<T, O extends SceneBuilder<T, O>> {

    /**
     * 谓词构建器，用于构建 {@link SceneSelector.Predicate} 对象。
     * 该类允许以流式方式添加多个允许的值，最后通过 {@link #end()} 返回父构建器。
     *
     * @param <T> 父构建器的类型
     * @param <O> 当前谓词构建器自身的类型（用于方法链）
     */
    public static class PredicateBuilder<T, O extends PredicateBuilder<T, O>> {
        /** 父构建器引用，调用 {@link #end()} 时返回 */
        protected T parent;
        /** 当前正在构建的谓词的允许值列表 */
        protected List<String> values;
        /** 当前正在构建的谓词的键名 */
        protected String key;

        /**
         * 构造一个谓词构建器，指定父构建器和谓词键名。
         *
         * @param parent 父构建器
         * @param key    谓词的键名
         */
        public PredicateBuilder(T parent, String key) {
            super();
            this.parent = parent;
            this.values = new ArrayList<>();
            this.key = key;
        }

        /**
         * 向当前谓词添加一个允许的值。
         *
         * @param value 要添加的值
         * @return 当前构建器自身（用于链式调用）
         */
        public O withValue(String value) {
            values.add(value);
            return (O) this;
        }

        /**
         * 结束当前谓词的构建，返回父构建器。
         *
         * @return 父构建器
         */
        public T end() {
            return parent;
        }
    }

    /**
     * 简单的谓词构建器，固定了自身类型为 {@code SimplePredicateBuilder<T>}。
     * 这是 {@link PredicateBuilder} 的一个特化，简化了泛型签名。
     *
     * @param <T> 父构建器的类型
     */
    public static class SimplePredicateBuilder<T> extends PredicateBuilder<T, SimplePredicateBuilder<T>> {

        /**
         * 构造一个简单谓词构建器。
         *
         * @param parent 父构建器
         * @param key    谓词的键名
         */
        public SimplePredicateBuilder(T parent, String key) {
            super(parent, key);
            // TODO Auto-generated constructor stub
        }

        /**
         * 向当前谓词添加一个允许的值，并返回自身。
         * 此方法重写了父类方法，以返回正确的子类型。
         *
         * @param value 要添加的值
         * @return 当前构建器自身
         */
        public SimplePredicateBuilder<T> withValue(String value) {
            values.add(value);
            return this;
        }
    }

    /**
     * 简单的场景构建器，固定了自身类型为 {@code SimpleSceneBuilder<T>}。
     * 这是 {@link SceneBuilder} 的一个特化，提供了便捷的静态工厂方法和嵌套构建方法。
     *
     * @param <T> 父构建器的类型
     */
    public static class SimpleSceneBuilder<T> extends SceneBuilder<T, SimpleSceneBuilder<T>> {

        /**
         * 构造一个简单场景构建器。
         *
         * @param par 父构建器
         */
        public SimpleSceneBuilder(T par) {
            super(par);
        }

        /**
         * 创建一个新的嵌套场景构建器（替代场景），并返回该嵌套构建器。
         * 此方法用于开始构建一个备选场景，嵌套构建器的父构建器为当前构建器。
         *
         * @return 新的嵌套 {@link SimpleSceneBuilder} 实例
         */
        public SimpleSceneBuilder<SimpleSceneBuilder<T>> withAlt() {
            return withAlt(new SimpleSceneBuilder<>(this));
        }

        /**
         * 静态工厂方法，创建一个顶层场景构建器，其父构建器为一个新 {@link Object}。
         * 通常用于开始构建一个独立的场景选择器。
         *
         * @return 新的 {@link SimpleSceneBuilder} 实例，父构建器为 {@link Object}
         */
        public static SimpleSceneBuilder<Object> builder() {
            return new SimpleSceneBuilder<>(new Object());
        }
    }

    /** 父构建器引用，调用 {@link #end()} 时返回 */
    protected T parent;
    /** 正在构建的 {@link SceneSelector} 实例 */
    protected SceneSelector scene = new SceneSelector();
    /** 当前路径前缀，用于构建场景文件路径时自动添加 */
    protected String pathPrefix = "";

    /**
     * 构造一个场景构建器，指定父构建器。
     *
     * @param par 父构建器
     */
    public SceneBuilder(T par) {
        this.parent = par;
    }

    /**
     * 向当前路径前缀追加一段路径，并返回自身。
     *
     * @param path 要追加的路径段（会自动添加 "/"）
     * @return 当前构建器自身
     */
    public O addPrefix(String path) {
        pathPrefix += path + "/";
        return (O) this;
    }

    /**
     * 设置当前路径前缀为指定字符串（覆盖原有值）。
     *
     * @param path 新的路径前缀
     * @return 当前构建器自身
     */
    public O setPrefix(String path) {
        pathPrefix = path;
        return (O) this;
    }

    /**
     * 结束当前构建器的构建，返回父构建器。
     *
     * @return 父构建器
     */
    public T end() {
        return parent;
    }

    /**
     * 向当前选择器添加一个简单的谓词（单个允许值）。
     *
     * @param name  谓词键名
     * @param value 允许的值
     * @return 当前构建器自身
     */
    public O withPredicate(String name, String value) {
        scene.predicates.add(new SceneSelector.Predicate(name, value));
        return (O) this;
    }

    /**
     * 向当前选择器添加一个由 {@link PredicateBuilder} 构建的谓词。
     * 该方法接受一个已配置好的谓词构建器，将其构建的谓词添加到选择器中，
     * 并返回该构建器本身（而非父构建器），以便继续配置该谓词。
     *
     * @param pb 谓词构建器
     * @param <N> 谓词构建器的具体类型
     * @return 传入的谓词构建器自身
     */
    public <N extends PredicateBuilder<O, N>> N withPredicate(N pb) {
        scene.predicates.add(new SceneSelector.Predicate(pb.key, pb.values));
        return pb;
    }

    /**
     * 开始构建一个新的简单谓词，返回该谓词的构建器。
     * 调用此方法后，可以通过返回的构建器添加多个值，最后调用 {@link SimplePredicateBuilder#end()} 返回当前场景构建器。
     *
     * @param name 谓词键名
     * @return 新的 {@link SimplePredicateBuilder} 实例，其父构建器为当前场景构建器
     */
    public SimplePredicateBuilder<O> withPredicate(String name) {
        SimplePredicateBuilder<O> pb = new SimplePredicateBuilder<>((O) this, name);
        scene.predicates.add(new SceneSelector.Predicate(name, pb.values));
        return pb;
    }

    /**
     * 向当前选择器添加一个包含多个允许值的谓词（可变参数形式）。
     *
     * @param name   谓词键名
     * @param value  允许的值列表（可变参数）
     * @return 当前构建器自身
     */
    public O withPredicate(String name, String... value) {
        scene.predicates.add(new SceneSelector.Predicate(name, value));
        return (O) this;
    }

    /**
     * 向当前选择器添加一个包含多个允许值的谓词（列表形式）。
     *
     * @param name   谓词键名
     * @param value  允许的值列表
     * @return 当前构建器自身
     */
    public O withPredicate(String name, List<String> value) {
        scene.predicates.add(new SceneSelector.Predicate(name, value));
        return (O) this;
    }

    /**
     * 设置当前选择器的默认场景标识（文件路径）。
     * 传入的字符串会自动添加当前路径前缀 {@link #pathPrefix}。
     *
     * @param fn 场景文件名或路径（相对路径）
     * @return 当前构建器自身
     */
    public O withScene(String fn) {
        scene.scene = pathPrefix + fn;
        return (O) this;
    }

    /**
     * 创建一个新的嵌套场景构建器（替代场景），并返回该嵌套构建器。
     * 这是一个抽象方法，由子类实现以返回正确类型的嵌套构建器。
     *
     * @return 新的嵌套 {@link SceneBuilder} 实例
     */
    public abstract SceneBuilder<O, ?> withAlt();

    /**
     * 将已有的嵌套构建器添加到当前选择器的子选择器列表中。
     * 同时会将当前路径前缀传递给该嵌套构建器。
     *
     * @param pb 要添加的嵌套构建器
     * @param <N> 嵌套构建器的具体类型
     * @return 传入的嵌套构建器自身（以便继续配置）
     */
    public <N extends SceneBuilder<O, N>> N withAlt(N pb) {
        scene.selectors.add(pb.scene);
        pb.setPrefix(pathPrefix);
        return pb;
    }

    /**
     * 快速添加一个带单值谓词的嵌套场景。
     * 该方法会创建一个新的嵌套场景构建器，为其添加一个谓词（单值），设置场景文件，并自动结束嵌套构建。
     *
     * @param fn    场景文件名
     * @param name  谓词键名
     * @param value 谓词允许的单个值
     * @return 当前构建器自身
     */
    public O withAlt(String fn, String name, String value) {
        withAlt().withPredicate(name, value).setPrefix(pathPrefix).withScene(fn).end();
        return (O) this;
    }

    /**
     * 快速添加一个带多值谓词（可变参数）的嵌套场景。
     *
     * @param fn    场景文件名
     * @param name  谓词键名
     * @param value 谓词允许的多个值（可变参数）
     * @return 当前构建器自身
     */
    public O withAlt(String fn, String name, String... value) {
        withAlt().withPredicate(name, value).setPrefix(pathPrefix).withScene(fn).end();
        return (O) this;
    }

    /**
     * 快速添加一个带多值谓词（列表）的嵌套场景。
     *
     * @param fn    场景文件名
     * @param name  谓词键名
     * @param value 谓词允许的值列表
     * @return 当前构建器自身
     */
    public O withAlt(String fn, String name, List<String> value) {
        withAlt().withPredicate(name, value).setPrefix(pathPrefix).withScene(fn).end();
        return (O) this;
    }

    /**
     * 完成构建，返回最终生成的 {@link SceneSelector} 对象。
     *
     * @return 构建好的 {@link SceneSelector} 实例
     */
    public SceneSelector build() {
        return scene;
    }
}