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

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;

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
        TEXT_ONLY(true, false, false, false),
        /** 文本 + 图片 */
        TEXT_IMAGE(true, true, false, false),
        /** 文本 + 音频 */
        TEXT_AUDIO(true, false, true, false),
        /** 文本 + 视频 */
        TEXT_VIDEO(true, false, false, true),
        /** 纯图片（用于图生文、图生图等任务） */
        IMAGE_ONLY(false, true, false, false),
        /** 纯音频（用于语音识别、音频理解等） */
        AUDIO_ONLY(false, false, true, false);

        public final boolean text, image, audio, video;

        private MultimodalType(boolean text, boolean image, boolean audio, boolean video) {
            this.text = text;
            this.image = image;
            this.audio = audio;
            this.video = video;
        }

        public boolean canSupport(boolean text, boolean image, boolean audio, boolean video) {
            // 如果枚举要求支持某模态，但实际模型不支持，则返回 false
            if (this.text && !text) return false;
            if (this.image && !image) return false;
            if (this.audio && !audio) return false;
            if (this.video && !video) return false;
            return true;
        }
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
    public static enum ResponseFormat{
    	TEXT,JSON
    }


    /** 模型类别（推理型/非推理型） */
    public final ModelCategory category;

    /** 推理强度（当 category 为 REASONING 时有效） */
    public final ReasoningStrength strength;

    /** 多模态类型，标识输入数据的类型组合 */
    public final MultimodalType multimodal;

    /** 任务类型，帮助模型调整生成风格 */
    public final TaskType taskType;

    public final ResponseFormat format;
    
    /** 是否启用流式输出（实时返回生成内容） */
    public final boolean stream;
    
    public final String[] modelHint;
    

    public final String user;
    public final int maxToken;
    public final float temperature;
    public final String prefix;
    
    public final List<HistoryItem> history;
    /**
     * 私有构造函数，通过 Builder 创建实例。
     *
     * @param builder 包含各字段值的 Builder 对象
     * @param request 请求的 JSON 对象
     */
    private AIRequest(Builder builder) {
        ModelCategory category = builder.category;
        ReasoningStrength strength = builder.strength;
        this.taskType = builder.taskType;
        this.stream = builder.stream;
        this.multimodal = builder.multimodal;
        this.modelHint = builder.modelHint.split("/");
        this.user = builder.user;
        this.maxToken=builder.max_tokens;
        this.temperature=builder.temperature;
        this.history=builder.history;
        this.format=builder.format;
        this.prefix=builder.prefix;
        if(hasModelProperty("reasoning"))
			category=ModelCategory.REASONING;
		else if(hasModelProperty("non-reasoning"))
			category=ModelCategory.NON_REASONING;
        this.category=category;
        for(String s:modelHint) {
			if(s.endsWith("-strength")) {
				try {
					ReasoningStrength rs=ReasoningStrength.valueOf(s.split("-")[0].toUpperCase());
					strength=rs;
					break;
				}catch(IllegalArgumentException ex) {
					
				}
			}
		}
        this.strength=strength;
    }
    public boolean isModelNamed(String name) {
    	return modelHint.length>0&&modelHint[0].equals(name);
    }
    public String getModelName() {
    	return modelHint.length>0?modelHint[0]:"";
    }
    public boolean hasModelProperty(String name) {
    	for(String s:modelHint) {
    		if(name.equals(s))
    			return true;
    	}
    	return false;
    }
    /**
     * 创建一个新的 Builder 实例，用于构建 AIRequest。
     *
     * @return Builder 对象
     */
    public static Builder builder(AISession session) {
        return new Builder(session.user).modelHint(session.getData().modelHint);
    }
	public static Builder builder(String user) {
		return new Builder(user);
	}

    /**
     * 返回该请求的字符串表示，便于调试。
     *
     * @return 包含所有字段值的字符串
     */
    @Override
    public String toString() {
        return "AIRequest [ category=" + category + ", strength=" + strength + ", multimodal="
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
        private ResponseFormat format = ResponseFormat.TEXT;
        private List<HistoryItem> history=new ArrayList<>(200);
        private int max_tokens=1024;
        private float temperature=2.0f;
        private String modelHint=null;
        private String user="";
        private String prefix;
        Builder(String user){
        	this.user=user;
        }
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
        public Builder modelHint(String modelHint) {
            this.modelHint = modelHint;
            return this;
        }
        public Builder maxTokens(int maxToken) {
            this.max_tokens = maxToken;
            return this;
        }
        public Builder temperature(float value) {
            this.temperature = value;
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
        public Builder format(ResponseFormat format) {
            this.format = format;
            return this;
        }
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }
        /**
         * 构建 AIRequest 实例。
         *
         * @param request 请求的 JSON 对象，不能为 null
         * @return 构建好的 AIRequest 对象
         * @throws IllegalArgumentException 如果 request 为 null
         */
        public AIRequest build() {
            return new AIRequest(this);
        }
        public Builder addHistoryItem(HistoryItem hi) {
        	history.add(hi);
        	return this;
        } 
        public Builder addHistoryItem(Role role,String content) {
        	history.add(new DirectHistoryItem(role,content));
        	return this;
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