package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class TotalCredits implements UsageIntf<TotalCredits> {
	long credit;
	@Override
	public void add(TotalCredits another) {
		credit+=another.credit;
	}
	public void add(double credit) {
		this.credit+=credit*100;
	}
	@Override
	public void set(TotalCredits another) {
		credit=another.credit;
	}

	@Override
	public double getEquivantTokens() {
		return credit/100d;
	}

}
