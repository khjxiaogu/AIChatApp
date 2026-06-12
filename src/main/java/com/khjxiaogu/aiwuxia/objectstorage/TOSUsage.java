package com.khjxiaogu.aiwuxia.objectstorage;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class TOSUsage implements UsageIntf<TOSUsage> {
	long size;
	@Override
	public void add(TOSUsage another) {
		this.size+=another.size;
	}

	@Override
	public void set(TOSUsage another) {
		this.size=another.size;
	}

	@Override
	public double getEquivantTokens() {
		return size/1024d/1024d/1024d*3d*1000000d;
	}

	public TOSUsage(long size) {
		super();
		this.size = size;
	}

	public TOSUsage() {
		super();
	}

}
