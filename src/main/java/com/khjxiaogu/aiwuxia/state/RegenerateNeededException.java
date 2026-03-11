package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.state.status.StateIntf;

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
