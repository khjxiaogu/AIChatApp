package com.khjxiaogu.aiwuxia.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 一次提供多个 {@link ToolData} 的工具集。
 * <p>
 * 需要共享配置/状态的一组工具（例如角色设定查询、SDXL 图片工具、音乐工具）实现本接口，
 * 由 {@code ToolCallManager} 统一注册；相互独立、没有共享状态的工具直接提供静态工厂即可，
 * 不必为了注册而创建实现类。
 * </p>
 * <p>
 * 需要临时把几个独立工具绑定为共享调用次数的组时，可以使用
 * {@link #of(ToolData...)} / {@link #of(Collection)} 生成匿名实现。
 * </p>
 */
@FunctionalInterface
public interface ToolProvider {

	/**
	 * 当前提供的全部工具。
	 *
	 * @return 工具集合，永不为 null
	 */
	Collection<ToolData> getTools();

	/**
	 * 工具集名称，用于日志与排查。
	 *
	 * @return 名称，永不为 null
	 */
	default String getName() {
		return getClass().getSimpleName();
	}

	/**
	 * 用一组现成的工具创建工具集。
	 *
	 * @param tools 工具，null 元素忽略
	 * @return 工具集
	 */
	static ToolProvider of(ToolData... tools) {
		if (tools == null || tools.length == 0) {
			return Collections::emptyList;
		}
		return of(Arrays.asList(tools));
	}

	/**
	 * 用一组现成的工具创建工具集。
	 *
	 * @param tools 工具集合，null 视为空
	 * @return 工具集
	 */
	static ToolProvider of(Collection<ToolData> tools) {
		final List<ToolData> copy = new ArrayList<>();
		if (tools != null) {
			for (ToolData tool : tools) {
				if (tool != null) {
					copy.add(tool);
				}
			}
		}
		final List<ToolData> immutable = Collections.unmodifiableList(copy);
		return () -> immutable;
	}
}
