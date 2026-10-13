package com.khjxiaogu.aiwuxia.llm;

import java.util.HashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;

public class ToolData {
	public final Tool tool;
	public final String name;
	public final String description;
	public final Map<String,String> params;
	/**
	 * 工具调用显示转换器：把原始调用信息转换为用户友好的简述与具体内容。
	 * <p>默认转换器随工具一起创建：简述为工具名称、具体内容为空。</p>
	 */
	public final ToolCallPresenter presenter;

	public ToolData(Tool tool, String name, String description, Map<String,String> params) {
		this(tool, name, description, params, ToolCallPresenter.defaults());
	}

	public ToolData(Tool tool, String name, String description, Map<String,String> params, ToolCallPresenter presenter) {
		super();
		this.tool = tool;
		this.name = name;
		this.description = description;
		this.params = params;
		this.presenter = presenter == null ? ToolCallPresenter.defaults() : presenter;
	}

    public static class Builder {
        private Tool tool;
        private String name;
        private String description;
        private Map<String, String> params = new HashMap<>();
        private ToolCallPresenter presenter;

        public Builder tool(Tool tool) {
            this.tool = tool;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置工具调用显示转换器（简述替代工具名称，具体内容用于展示）。
         * <p>不设置时使用默认转换器：简述为工具名称、具体内容为空。</p>
         *
         * @param presenter 显示转换器
         * @return 当前 Builder
         */
        public Builder presenter(ToolCallPresenter presenter) {
            this.presenter = presenter;
            return this;
        }

        // 添加单个参数
        public Builder putParam(String key, String value) {
            this.params.put(key, value);
            return this;
        }

        // 批量添加参数（覆盖已有键）
        public Builder params(Map<String, String> params) {
            this.params.putAll(params);
            return this;
        }

        public ToolData build() {
            // 防御性复制 + 不可变视图，保证外部无法修改 ToolData 内部的 Map
            Map<String, String> immutableParams =new HashMap<>(params);
            return new ToolData(tool, name, description, immutableParams, presenter);
        }

		public Builder(String name, String description) {
			super();
			this.name = name;
			this.description = description;
		}
    }
}
