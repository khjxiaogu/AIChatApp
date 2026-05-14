package com.khjxiaogu.aiwuxia.llm;

import java.util.HashMap;
import java.util.Map;

public class ToolData {
	public final Tool tool;
	public final String name;
	public final String description;
	public final Map<String,String> params;
	public ToolData(Tool tool, String name, String description, Map<String,String> params) {
		super();
		this.tool = tool;
		this.name = name;
		this.description = description;
		this.params = params;
	}

    public static class Builder {
        private Tool tool;
        private String name;
        private String description;
        private Map<String, String> params = new HashMap<>();

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
            return new ToolData(tool, name, description, immutableParams);
        }

		public Builder(String name, String description) {
			super();
			this.name = name;
			this.description = description;
		}
    }
}
