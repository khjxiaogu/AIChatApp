package com.khjxiaogu.aiwuxia.llm.scheme;



public interface UsageIntf<T extends UsageIntf<T>> {

	void add(T another);
	
	void set(T another);

	double getEquivantTokens();

}