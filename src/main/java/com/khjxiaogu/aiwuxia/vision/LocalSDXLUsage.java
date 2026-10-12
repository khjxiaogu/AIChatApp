package com.khjxiaogu.aiwuxia.vision;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class LocalSDXLUsage implements UsageIntf<LocalSDXLUsage> {
	int count;
	int interrogate;
	public LocalSDXLUsage(int count) {
		super();
		this.count = count;
	}
	public LocalSDXLUsage(int count,int interrogate) {
		super();
		this.count = count;
		this.interrogate = interrogate;
	}
	@Override
	public void add(LocalSDXLUsage another) {
		count+=another.count;
		interrogate+=another.interrogate;
	}
	@Override
	public void set(LocalSDXLUsage another) {
		count=another.count;
		interrogate=another.interrogate;
	}
	@Override
	public double getEquivantTokens() {
		return count*5000+interrogate*500; //1 000 000
	}
    @Override
    public String toString() {
        return "图片生成" + count+"，推理"+interrogate;
    }
}
