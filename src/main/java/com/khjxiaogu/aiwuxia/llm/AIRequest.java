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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPolicy;
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
     * 工具调用管理器：同时承载本次请求的工具注册表与调用规则（次数、权限、许可）。
     * <p>
     * 由会话/应用提供（{@code ToolCallManager.builder()...build()}），提供者不再自行维护
     * 工具列表与规则；为 null 表示本次请求不提供任何工具。
     * 单次生成的调用状态由 {@code ToolCallContext.create(toolCallManager, ...)} 承载。
     * </p>
     */
    public final ToolCallManager toolCallManager;
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
        if(builder.modelHint!=null)
        	this.modelHint = builder.modelHint.split("/");
        else
        	this.modelHint = new String[0];
        this.user = builder.user;
        this.maxToken=builder.max_tokens;
        this.temperature=builder.temperature;
        this.history=builder.history;
        this.format=builder.format;
        this.prefix=builder.prefix;
        this.toolCallManager=builder.buildToolCallManager();
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
    /**
     * 获取本次请求的工具调用管理器，未提供时为 null。
     */
    public ToolCallManager getToolCallManager() {
    	return toolCallManager;
    }
    /**
     * 获取本次请求可用的工具表（名称 → 工具）。
     * <p>未提供管理器时返回空表，便于提供者统一处理“无工具”的情况。</p>
     */
    public Map<String,ToolData> getTools() {
    	return toolCallManager==null?Collections.emptyMap():toolCallManager.getTools();
    }
    /**
     * 工具调用轮次上限，等同 {@link ToolCallPolicy#getMaxRounds()}。
     *
     * @deprecated 请通过 {@link #getToolCallManager()} 与 {@code ToolCallContext} 统一管理调用次数。
     */
    @Deprecated
    public int getMaxToolCall() {
    	return toolCallManager==null?ToolCallPolicy.DEFAULT_MAX_ROUNDS:toolCallManager.getPolicy().getMaxRounds();
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
     * 基于会话创建 Builder：会话提供可用工具与管理策略，共同装配成
     * {@link ToolCallManager} 交给请求。
     *
     * @param session 会话
     * @return Builder 对象
     */
    public static Builder builder(AISession session) {
        return new Builder(session.user).modelHint(session.getExtraData().modelHint)
        		.toolCallManager(ToolCallManager.builder()
        				.tools(session.getAvailableTools())
        				.policy(session.getToolCallPolicy())
        				.build());
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
        /** 工具注册与规则构建器（未显式提供管理器时使用） */
        private ToolCallManager.Builder toolManagerBuilder=ToolCallManager.builder();
        /** 显式提供的工具调用管理器，为 null 表示由 toolManagerBuilder 构建 */
        private ToolCallManager toolCallManager;
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
         * 设置工具调用管理器（工具注册表 + 调用次数/权限/许可规则）。
         * <p>
         * 调用本方法后再使用 {@link #addTool}/{@link #addTools}/{@link #toolCallPolicy} 等方法，
         * 会以该管理器为基础继续追加。
         * </p>
         *
         * @param manager 工具调用管理器，为 null 表示不提供工具
         * @return 当前 Builder 实例
         */
        public Builder toolCallManager(ToolCallManager manager) {
            this.toolCallManager = manager;
            this.toolManagerBuilder = null;
            return this;
        }
        /**
         * 注册单个工具（集成到 {@link ToolCallManager} 中）。
         *
         * @param tool 工具定义
         * @return 当前 Builder 实例
         */
        public Builder addTool(ToolData tool) {
            mutableToolManagerBuilder().tool(tool);
            return this;
        }
        /**
         * 批量注册工具（集成到 {@link ToolCallManager} 中）。
         *
         * @param tools 工具定义集合
         * @return 当前 Builder 实例
         */
        public Builder addTools(Collection<ToolData> tools) {
            mutableToolManagerBuilder().tools(tools);
            return this;
        }
        /**
         * 设置工具调用管理策略（调用次数、权限、许可），由 {@code ToolCallManager} 执行。
         *
         * @param policy 管理策略，为 null 时使用默认策略
         * @return 当前 Builder 实例
         */
        public Builder toolCallPolicy(ToolCallPolicy policy) {
            mutableToolManagerBuilder().policy(policy);
            return this;
        }
        /**
         * 设置工具调用轮次上限。
         * <p>调用次数、权限、许可等完整规则请使用 {@link #toolCallPolicy(ToolCallPolicy)}
         * 或直接提供 {@link ToolCallManager}。</p>
         *
         * @param maxToolCall 最大工具调用轮次，小于等于 0 表示不限制
         * @return 当前 Builder 实例
         */
        public Builder maxToolCall(int maxToolCall) {
            mutableToolManagerBuilder().maxRounds(maxToolCall);
            return this;
        }
        /**
         * 获取当前可变的工具管理器构建器：若此前显式提供了管理器，则以其工具与策略为基础
         * 转为可变构建器，保证后续注册与显式管理器叠加而不是互相覆盖。
         */
        private ToolCallManager.Builder mutableToolManagerBuilder() {
            if (toolManagerBuilder == null) {
                toolManagerBuilder = toolCallManager == null ? ToolCallManager.builder() : toolCallManager.toBuilder();
                toolCallManager = null;
            }
            return toolManagerBuilder;
        }
        /**
         * 构建本次请求的工具调用管理器；未注册任何工具且未设置任何规则时返回 null，
         * 表示本次请求不提供工具。
         */
        private ToolCallManager buildToolCallManager() {
            if (toolCallManager != null) {
                return toolCallManager;
            }
            return toolManagerBuilder == null || toolManagerBuilder.isEmpty() ? null : toolManagerBuilder.build();
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