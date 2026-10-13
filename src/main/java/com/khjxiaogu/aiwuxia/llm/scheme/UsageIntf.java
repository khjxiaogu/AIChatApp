package com.khjxiaogu.aiwuxia.llm.scheme;



public interface UsageIntf<T extends UsageIntf<T>> {

	/** 默认的使用量类型名称，未识别的用量类型使用该常量 */
	String DEFAULT_USAGE_NAME = "其他";

	void add(T another);
	
	void set(T another);

	double getEquivantTokens();

	/**
	 * 获取该用量的类型名称（如"Deepseek V4 对话"、"豆包语音合成"），用于补充配额消费明细的记录与展示。
	 * <p>
	 * 默认返回常量 {@link #DEFAULT_USAGE_NAME}，实现类应覆盖以给出准确且不重复的名称。
	 * </p>
	 *
	 * @return 使用量类型名称
	 */
	default String getUsageName() {
		return DEFAULT_USAGE_NAME;
	}

}
