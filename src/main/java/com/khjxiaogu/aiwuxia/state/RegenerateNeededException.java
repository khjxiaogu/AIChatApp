package com.khjxiaogu.aiwuxia.state;

public class RegenerateNeededException extends RuntimeException {
	/**
	 * 
	 */
	private static final long serialVersionUID = -3535919120053640005L;
	public final StateIntf oldState;

	public RegenerateNeededException(StateIntf oldState) {
		super();
		this.oldState = oldState;
	}
}
