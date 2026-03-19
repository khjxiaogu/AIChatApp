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
package com.khjxiaogu.aiwuxia.llm;

import com.google.gson.JsonObject;

/**
 * 统一请求实体，封装了向 AI 模型发送请求所需的所有参数。
 * 该类使用 Builder 模式构建，支持设置模型类型、多模态类型、推理强度、任务类型、流式输出等。
 * 请求的核心是一个 JSON 对象，可以根据不同模型的需要扩展字段（如 max_tokens, temperature 等）。
 */
public class AIRequest {

    /**
     * 模型类别，用于区分是否支持推理能力。
     */
    public static enum ModelCategory {
        /** 推理型模型，支持深度思考、思维链等 */
        REASONING,
        /** 非推理型模型，通常为普通生成模型 */
        NON_REASONING
    }

    /**
     * 多模态类型，标识请求中包含的数据类型。
     */
    public static enum MultimodalType {
        /** 纯文本（默认） */
        TEXT_ONLY,
        /** 文本 + 图片 */
        TEXT_IMAGE,
        /** 文本 + 音频 */
        TEXT_AUDIO,
        /** 文本 + 视频 */
        TEXT_VIDEO,
        /** 纯图片（用于图生文、图生图等任务） */
        IMAGE_ONLY,
        /** 纯音频（用于语音识别、音频理解等） */
        AUDIO_ONLY
    }

    /**
     * 推理强度，控制模型在生成回答前进行思考的深度。
     */
    public static enum ReasoningStrength {
        /** 不需要思维链，直接生成回答 */
        NONE,
        /** 简单推理，适合快速响应 */
        WEAK,
        /** 中等推理，平衡速度与深度 */
        MEDIUM,
        /** 深度思考，适合复杂逻辑问题 */
        STRONG
    }

    /**
     * 任务类型，标识用户请求的主要目的。
     */
    public static enum TaskType {
        /** 逻辑推理任务 */
        LOGIC,
        /** 代码生成任务 */
        CODE,
        /** 数据分析任务 */
        ANALYSIS,
        /** 故事创作任务 */
        STORY
    }

    /** 请求的完整 JSON 数据，包含具体参数（如 prompt、max_tokens 等） */
    public final JsonObject request;

    /** 模型类别（推理型/非推理型） */
    public final ModelCategory category;

    /** 推理强度（当 category 为 REASONING 时有效） */
    public final ReasoningStrength strength;

    /** 多模态类型，标识输入数据的类型组合 */
    public final MultimodalType multimodal;

    /** 任务类型，帮助模型调整生成风格 */
    public final TaskType taskType;

    /** 是否启用流式输出（实时返回生成内容） */
    public final boolean stream;

    /**
     * 私有构造函数，通过 Builder 创建实例。
     *
     * @param builder 包含各字段值的 Builder 对象
     * @param request 请求的 JSON 对象
     */
    private AIRequest(Builder builder, JsonObject request) {
        this.request = request;
        this.category = builder.category;
        this.strength = builder.strength;
        this.taskType = builder.taskType;
        this.stream = builder.stream;
        this.multimodal = builder.multimodal;
    }

    /**
     * 创建一个新的 Builder 实例，用于构建 AIRequest。
     *
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回该请求的字符串表示，便于调试。
     *
     * @return 包含所有字段值的字符串
     */
    @Override
    public String toString() {
        return "AIRequest [request=" + request + ", category=" + category + ", strength=" + strength + ", multimodal="
                + multimodal + ", taskType=" + taskType + ", stream=" + stream + "]";
    }

    /**
     * AIRequest 的 Builder 类，用于链式设置各参数并最终构建不可变的 AIRequest 对象。
     */
    public static class Builder {
        /** 模型类别，默认为 NON_REASONING */
        private ModelCategory category = ModelCategory.NON_REASONING;
        /** 推理强度，默认为 NONE */
        private ReasoningStrength strength = ReasoningStrength.NONE;
        /** 任务类型，默认为 ANALYSIS */
        private TaskType taskType = TaskType.ANALYSIS;
        /** 是否流式输出，默认为 false */
        private boolean stream = false;
        /** 多模态类型，默认为 TEXT_ONLY */
        private MultimodalType multimodal = MultimodalType.TEXT_ONLY;

        /**
         * 设置模型类别。
         *
         * @param category 模型类别枚举值
         * @return 当前 Builder 实例
         */
        public Builder category(ModelCategory category) {
            this.category = category;
            return this;
        }

        /**
         * 设置推理强度，并自动将模型类别设置为 REASONING。
         * 若需使用推理能力，推荐直接调用此方法。
         *
         * @param strength 推理强度枚举值
         * @return 当前 Builder 实例
         */
        public Builder strength(ReasoningStrength strength) {
            this.strength = strength;
            this.category = ModelCategory.REASONING;
            return this;
        }

        /**
         * 设置任务类型。
         *
         * @param taskType 任务类型枚举值
         * @return 当前 Builder 实例
         */
        public Builder taskType(TaskType taskType) {
            this.taskType = taskType;
            return this;
        }

        /**
         * 设置是否流式输出。
         *
         * @param stream true 为流式输出，false 为一次性输出
         * @return 当前 Builder 实例
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * 设置多模态类型。
         *
         * @param multimodal 多模态类型枚举值
         * @return 当前 Builder 实例
         */
        public Builder multimodal(MultimodalType multimodal) {
            this.multimodal = multimodal;
            return this;
        }

        /**
         * 便捷方法：开启深度思考模式。
         * 将模型类别设置为 REASONING，推理强度设置为 STRONG。
         *
         * @return 当前 Builder 实例
         */
        public Builder enableDeepThink() {
            this.category = ModelCategory.REASONING;
            this.strength = ReasoningStrength.STRONG;
            return this;
        }

        /**
         * 构建 AIRequest 实例。
         *
         * @param request 请求的 JSON 对象，不能为 null
         * @return 构建好的 AIRequest 对象
         * @throws IllegalArgumentException 如果 request 为 null
         */
        public AIRequest build(JsonObject request) {
            if (request == null) throw new IllegalArgumentException("Prompt cannot be empty");
            return new AIRequest(this, request);
        }

        /**
         * 便捷方法：设置流式输出为 true。
         *
         * @return 当前 Builder 实例
         */
        public Builder streamed() {
            stream(true);
            return this;
        }
    }
}