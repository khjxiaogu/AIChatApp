package com.khjxiaogu.aiwuxia.utils;

public class RunnableOnce implements Runnable {
	boolean isRunned=false;
	Runnable runnable;
	public RunnableOnce(Runnable runnable) {
		super();
		this.runnable = runnable;
	}
	@Override
	public void run() {
		if(!isRunned) {
			isRunned=true;
			runnable.run();
		}
	}

}
