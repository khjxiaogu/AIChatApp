package com.khjxiaogu.aiwuxia;

import java.io.IOException;
import java.nio.CharBuffer;

public class FilledReadable implements Readable {
	private StringBuilder internal;
	private boolean isEnded;
	public FilledReadable() {
		
	}
	@Override
	public int read(CharBuffer cb) throws IOException {
		//wait for insert
		try {
			while(internal==null) {
				if(isEnded)
					return -1;
				if(internal!=null)
					break;
				
					Thread.sleep(100);
				
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		char[] ca;
	
		synchronized(this) {
			int len=Math.min(cb.length(), internal.length());
			if(len>=internal.length()) {
				ca=internal.toString().toCharArray();
				internal=null;
			}else {
				ca=internal.substring(0, len).toCharArray();
				internal=new StringBuilder(internal.substring(len));
			}
			
			
		}
		cb.put(ca);
		return ca.length;
	}
	public void putCh(String event) {
		if(event==null) {
			isEnded=true;
			return;
		}
		synchronized(this) {
			if(internal==null) {
				internal=new StringBuilder();
			}
			internal.append(event);
		}
	}
}
