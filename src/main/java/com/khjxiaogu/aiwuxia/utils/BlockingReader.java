package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.io.Reader;

public class BlockingReader extends Reader {
	private StringBuilder internal=new StringBuilder();
	private volatile int position,length;
	private volatile boolean isEnded;
	private Object lock=new Object();
	public BlockingReader() {
		
	}
	public void setEnded() {
		isEnded=true;
	}
	public void putCh(String event) {
		synchronized(lock) {
			internal.append(event);
			length=internal.length();
		}
	}
	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		if(position>=length) {
			if(isEnded)
				return -1;
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
			if(position>=length) {
				if(isEnded)
					return -1;
				return 0;
			}
		}
		int originOff=off;
		synchronized(lock) {
			len=Math.min(len,length);
			while(position<length) {
				cbuf[off++]=internal.charAt(position++);
			}
		}
		return off-originOff;
	}
	@Override
	public void close() throws IOException {
		isEnded=true;
	}
	public boolean isEnded() {
		return isEnded;
	}
}
