package com.khjxiaogu.aiwuxia.utils;

import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;

public class BlockingReader extends Reader {
	private StringBuilder internal;
	private boolean isEnded;
	private Object lock=new Object();
	public BlockingReader() {
		
	}
	@Override
	public int read(CharBuffer cb) throws IOException {

		if(isEnded)
			return -1;
		if(internal==null)
			return 0;
		char[] ca;
	
		synchronized(lock) {
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
	public void setEnded() {
		isEnded=true;
	}
	public void putCh(String event) {
		synchronized(lock) {
			if(internal==null) {
				internal=new StringBuilder();
			}
			internal.append(event);
		}
	}
	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		CharBuffer cb=CharBuffer.allocate(len);
		int alen=read(cb);
		cb.rewind();
		if(alen>0)
			for(int i=0;i<alen;i++)
				cbuf[i+off]=cb.get();
		//System.out.println(Arrays.toString(Arrays.copyOfRange(cbuf, off, alen+off)));
		return alen;
	}
	@Override
	public void close() throws IOException {
		isEnded=true;
	}
	public boolean isEnded() {
		return isEnded;
	}
}
