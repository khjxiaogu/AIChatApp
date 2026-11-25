package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.io.IOException;

/**
 * 工具参数里的“文件引用”解析器。
 * <p>
 * 统一处理对象存储 ID / URL 两种写法：URL 由实现下载并转存成对象存储 ID，
 * 已经是 ID 的值原样返回。工具调用管理器在真正执行工具前调用本解析器，
 * 并可以按 {@link #isUrlContext()} 决定向模型展示“72位id”还是“http(s)链接”的参数说明。
 * </p>
 */
public interface ToolFileResolver {

	/**
	 * 解析一个参数值。
	 * <p>
	 * 值可能是单个引用，也可能是逗号分隔的引用列表；实现应保持非 URL 值不变。
	 * </p>
	 *
	 * @param value 原始参数值，可为 null
	 * @return 解析后的值；无法解析时抛出异常
	 * @throws IOException 下载、解码或转存失败
	 */
	String resolve(String value) throws IOException;

	/**
	 * 当前上下文是否以 URL 形式描述参数。
	 *
	 * @return true 表示工具参数说明使用“内容为http或者https链接”
	 */
	boolean isUrlContext();
}
