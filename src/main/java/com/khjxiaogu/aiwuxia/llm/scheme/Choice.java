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
package com.khjxiaogu.aiwuxia.llm.scheme;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class Choice{
	public static class ToolFunction{
		public String name;
		public String arguments;
		public ToolFunction() {
			super();
		}
		public ToolFunction(String name, String arguments) {
			super();
			this.name = name;
			this.arguments = arguments;
		}
		@Override
		public String toString() {
			return "ToolFunction [name=" + name + ", arguments=" + arguments + "]";
		}
		
	}
	public static class ToolCall{
		public int index;
		public String id;
		public ToolFunction function;
		public ToolCall() {
			super();
		}
		@Override
		public String toString() {
			return "ToolCall [index=" + index + ", id=" + id + ", function=" + function + "]";
		}
		public ToolCall(int index, String id, ToolFunction function) {
			super();
			this.index = index;
			this.id = id;
			this.function = function;
		}
	}
	public static class Datable{
		public String data;
	}
	public static class Message{
		public String content;
		public String reasoning_content;
		public String role;
		public String refusal;
		public Datable audio;
		public List<ToolCall> tool_calls;
		/**
		 * DeepSeek 扩展字段：服务端联网工具（{@code deepseek_web_search} 等）调用。
		 * <p>
		 * 每个元素是一步 {@code {index, reasoning_content, calls:[...]}}；流式下发时只在带
		 * {@code finish_reason} 的最后一个 chunk 的 {@code delta} 里出现一次。客户端不需要执行这些调用，
		 * 但要把它们原样回传，见 {@code ServerToolUseContent}。
		 * </p>
		 */
		public JsonArray server_tool_calls;
	}
	/**
	 * DeepSeek 扩展字段：服务端联网工具的执行进度（{@code choice.server_tool_event}）。
	 * <p>
	 * 一个 chunk 最多带一个事件，类型为 {@code step_reasoning_done}、{@code call_started}
	 * 或 {@code call_completed}；{@code call} 里是该次调用的完整参数或结果。
	 * </p>
	 */
	public static class ServerToolEvent{
		public String type;
		public Integer step;
		public JsonObject call;
	}
	public String finish_reason;
	public int index;
	public Choice.Message message;
	public Choice.Message delta;
	/** DeepSeek 扩展字段：服务端联网工具的执行进度，与 {@link #delta} 同级。 */
	public ServerToolEvent server_tool_event;
	
}
