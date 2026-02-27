package com.khjxiaogu.aiwuxia.state;

public class RegenerateNeededException extends RuntimeException {
	public final StateIntf oldState;

	public RegenerateNeededException(StateIntf oldState) {
		super();
		this.oldState = oldState;
	}
}
