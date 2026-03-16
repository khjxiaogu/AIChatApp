package com.khjxiaogu.aiwuxia.scene;
/**
 * 针对 AI 角色场景的专用构建器，继承自 {@link SceneBuilder}。
 * 该类提供了一系列特定于角色场景的谓词构建方法，例如季节、时间、地点、服装等，
 * 使得构建场景选择器时可以使用更语义化的 API。
 *
 * @param <T> 父构建器的类型（即调用 {@link #end()} 返回的对象类型）
 */
public class AICharaSceneBuilder<T> extends SceneBuilder<T, AICharaSceneBuilder<T>> {

    /**
     * 季节谓词构建器，用于构建键为“季节”的谓词。
     * 提供了一系列便捷方法，如 {@link #spring()}、{@link #summer()} 等，
     * 以及组合方法 {@link #green()}（春+夏）、{@link #red()}（秋）、{@link #white()}（冬）。
     *
     * @param <T> 父构建器的类型
     */
    public static class SeasonBuilder<T> extends PredicateBuilder<T, SeasonBuilder<T>> {

        /**
         * 构造一个季节谓词构建器，指定父构建器。
         *
         * @param parent 父构建器
         */
        public SeasonBuilder(T parent) {
            super(parent, "季节");
        }

        /**
         * 向当前季节谓词添加一个允许的值，并返回自身。
         *
         * @param value 要添加的季节值（如“春”、“夏”等）
         * @return 当前构建器自身
         */
        @Override
        public SeasonBuilder<T> withValue(String value) {
            super.withValue(value);
            return this;
        }

        /**
         * 添加春季和夏季的组合（对应“绿”色主题）。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> green() {
            return spring().summer();
        }

        /**
         * 添加秋季（对应“红”色主题）。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> red() {
            return autumn();
        }

        /**
         * 添加冬季（对应“白”色主题）。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> white() {
            return winter();
        }

        /**
         * 添加春季值“春”。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> spring() {
            return withValue("春");
        }

        /**
         * 添加夏季值“夏”。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> summer() {
            return withValue("夏");
        }

        /**
         * 添加秋季值“秋”。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> autumn() {
            return withValue("秋");
        }

        /**
         * 添加冬季值“冬”。
         *
         * @return 当前构建器自身
         */
        public SeasonBuilder<T> winter() {
            return withValue("冬");
        }
    }

    /**
     * 时间谓词构建器，用于构建键为“时间”的谓词。
     * 提供了一系列便捷方法，如 {@link #morning()}、{@link #noon()} 等，
     * 以及组合方法 {@link #daytime()}（上午+下午）和 {@link #nighttime()}（黄昏+夜晚）。
     *
     * @param <T> 父构建器的类型
     */
    public static class TimeBuilder<T> extends PredicateBuilder<T, TimeBuilder<T>> {

        /**
         * 构造一个时间谓词构建器，指定父构建器。
         *
         * @param parent 父构建器
         */
        public TimeBuilder(T parent) {
            super(parent, "时间");
        }

        /**
         * 向当前时间谓词添加一个允许的值，并返回自身。
         *
         * @param value 要添加的时间值（如“上午”、“下午”等）
         * @return 当前构建器自身
         */
        @Override
        public TimeBuilder<T> withValue(String value) {
            super.withValue(value);
            return this;
        }

        /**
         * 添加“上午”值。
         *
         * @return 当前构建器自身
         */
        public TimeBuilder<T> morning() {
            return withValue("上午");
        }

        /**
         * 添加“下午”值。
         *
         * @return 当前构建器自身
         */
        public TimeBuilder<T> noon() {
            return withValue("下午");
        }

        /**
         * 添加“黄昏”值。
         *
         * @return 当前构建器自身
         */
        public TimeBuilder<T> sunset() {
            return withValue("黄昏");
        }

        /**
         * 添加“夜晚”值。
         *
         * @return 当前构建器自身
         */
        public TimeBuilder<T> evening() {
            return withValue("夜晚");
        }

        /**
         * 添加白天时段（上午+下午）。
         *
         * @return 当前构建器自身
         */
        public TimeBuilder<T> daytime() {
            return morning().noon();
        }

        /**
         * 添加夜间时段（黄昏+夜晚）。
         *
         * @return 当前构建器自身
         */
        public TimeBuilder<T> nighttime() {
            return sunset().evening();
        }
    }

    /**
     * 构造一个 AI 角色场景构建器，指定父构建器。
     *
     * @param par 父构建器
     */
    public AICharaSceneBuilder(T par) {
        super(par);
    }

    /**
     * 开始构建一个季节谓词，并返回对应的 {@link SeasonBuilder} 实例。
     * 通过该构建器可以添加一个或多个允许的季节值。
     *
     * @return 季节谓词构建器，其父构建器为当前 AI 角色场景构建器
     */
    public SeasonBuilder<AICharaSceneBuilder<T>> season() {
        return (SeasonBuilder<AICharaSceneBuilder<T>>) super.withPredicate(new SeasonBuilder<AICharaSceneBuilder<T>>(this));
    }

    /**
     * 开始构建一个时间谓词，并返回对应的 {@link TimeBuilder} 实例。
     * 通过该构建器可以添加一个或多个允许的时间值。
     *
     * @return 时间谓词构建器，其父构建器为当前 AI 角色场景构建器
     */
    public TimeBuilder<AICharaSceneBuilder<T>> time() {
        return (TimeBuilder<AICharaSceneBuilder<T>>) super.withPredicate(new TimeBuilder<AICharaSceneBuilder<T>>(this));
    }

    /**
     * 静态工厂方法，创建一个顶层 AI 角色场景构建器，其父构建器为一个 {@link Endable} 对象。
     * 通常用于开始构建一个独立的场景选择器。
     *
     * @return 新的 {@link AICharaSceneBuilder} 实例，父构建器为 {@link Endable}
     */
    public static AICharaSceneBuilder<Endable> builder() {
        return new AICharaSceneBuilder<>(new Endable());
    }

    /**
     * 创建一个新的嵌套 AI 角色场景构建器（替代场景），并返回该嵌套构建器。
     * 此方法用于开始构建一个备选场景，嵌套构建器的父构建器为当前构建器。
     *
     * @return 新的嵌套 {@link AICharaSceneBuilder} 实例
     */
    public AICharaSceneBuilder<AICharaSceneBuilder<T>> withAlt() {
        return withAlt(new AICharaSceneBuilder<>(this));
    }

    /**
     * 开始构建一个地点谓词，返回一个简单的谓词构建器，可以添加一个或多个地点值。
     *
     * @return 简单的谓词构建器，用于添加地点值
     */
    public SimplePredicateBuilder<AICharaSceneBuilder<T>> location() {
        return super.withPredicate("地点");
    }

    /**
     * 快速添加一个单值的地点谓词。
     *
     * @param name 地点值
     * @return 当前构建器自身
     */
    public AICharaSceneBuilder<T> withLocation(String name) {
        return super.withPredicate("地点").withValue(name).end();
    }

    /**
     * 快速添加一个单值的姓名谓词。
     *
     * @param name 姓名值
     * @return 当前构建器自身
     */
    public AICharaSceneBuilder<T> withName(String name) {
        return super.withPredicate("姓名").withValue(name).end();
    }

    /**
     * 开始构建一个表情谓词，返回一个简单的谓词构建器，可以添加一个或多个表情值。
     *
     * @return 简单的谓词构建器，用于添加表情值
     */
    public SimplePredicateBuilder<AICharaSceneBuilder<T>> emote() {
        return super.withPredicate("表情");
    }

    /**
     * 快速添加一个单值的服装谓词。
     *
     * @param name 服装值
     * @return 当前构建器自身
     */
    public AICharaSceneBuilder<T> withCloth(String name) {
        return super.withPredicate("服装").withValue(name).end();
    }

    /**
     * 开始构建一个服装谓词，返回一个简单的谓词构建器，可以添加一个或多个服装值。
     *
     * @return 简单的谓词构建器，用于添加服装值
     */
    public SimplePredicateBuilder<AICharaSceneBuilder<T>> cloth() {
        return super.withPredicate("服装");
    }

    /**
     * 开始构建一个星期谓词，返回一个简单的谓词构建器，可以添加一个或多个星期值。
     *
     * @return 简单的谓词构建器，用于添加星期值
     */
    public SimplePredicateBuilder<AICharaSceneBuilder<T>> week() {
        return super.withPredicate("星期");
    }
}