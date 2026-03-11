package com.khjxiaogu.aiwuxia.llm;

import com.google.gson.JsonObject;

// 统一请求实体 (使用 Builder 构建)
public class AIRequest {
	public static enum ModelCategory {
	    REASONING,     // 推理型模型
	    NON_REASONING  // 非推理型模型
	}
	public static enum MultimodalType {
	    TEXT_ONLY,          // 纯文本 (默认)
	    TEXT_IMAGE,         // 文本 + 图片
	    TEXT_AUDIO,         // 文本 + 音频
	    TEXT_VIDEO,         // 文本 + 视频
	    IMAGE_ONLY,         // 纯图片 (图生文/图生图)
	    AUDIO_ONLY          // 纯音频 (语音识别/音频理解)
	}
	public static enum ReasoningStrength {
	    NONE,   // 不需要思维链
	    WEAK,   // 简单推理
	    MEDIUM, // 中等推理
	    STRONG  // 深度思考
	}

	public static enum TaskType {
	    LOGIC,   // 逻辑推理
	    CODE,    // 代码生成
	    ANALYSIS,// 数据分析
	    STORY    // 故事创作
	}
    public final JsonObject request;
    public final ModelCategory category;
    public final ReasoningStrength strength;
    public final MultimodalType multimodal;
    public final TaskType taskType;
    public final boolean stream;
    // 可扩展字段：比如最大Token、温度等

    private AIRequest(Builder builder,JsonObject request) {
        this.request = request;
        this.category = builder.category;
        this.strength = builder.strength;
        this.taskType = builder.taskType;
        this.stream = builder.stream;
        this.multimodal=builder.multimodal;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
	public String toString() {
		return "AIRequest [request=" + request + ", category=" + category + ", strength=" + strength + ", multimodal="
				+ multimodal + ", taskType=" + taskType + ", stream=" + stream + "]";
	}

	public static class Builder {
        private ModelCategory category = ModelCategory.NON_REASONING;
        private ReasoningStrength strength = ReasoningStrength.NONE;
        private TaskType taskType = TaskType.ANALYSIS;
        private boolean stream = false;
        private MultimodalType multimodal=MultimodalType.TEXT_ONLY;

        public Builder category(ModelCategory category) { this.category = category; return this; }
        public Builder strength(ReasoningStrength strength) { this.strength = strength;this.category=ModelCategory.REASONING; return this; }
        public Builder taskType(TaskType taskType) { this.taskType = taskType; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        public Builder multimodal(MultimodalType multimodal) { this.multimodal = multimodal; return this; }
        // 便捷方法：开启强推理
        public Builder enableDeepThink() {
            this.category = ModelCategory.REASONING;
            this.strength = ReasoningStrength.STRONG;
            return this;
        }

        public AIRequest build(JsonObject request) {
            if (request == null) throw new IllegalArgumentException("Prompt cannot be empty");
            return new AIRequest(this,request);
        }
		public Builder streamed() {
			stream(true);
			return this;
		}
    }
}