package com.khjxiaogu.aiwuxia.state;

import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

public class RegenerateNeededException extends RuntimeException {
	/**
	 * 
	 */
	private static final long serialVersionUID = -3535919120053640005L;
	public final ApplicationState oldState;

	public RegenerateNeededException(ApplicationState oldState) {
		super();
		this.oldState = oldState;
	}
}
