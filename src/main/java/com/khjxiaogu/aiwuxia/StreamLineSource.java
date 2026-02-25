/*
 * Copyright (c) 2024 TeamMoeg
 *
 * This file is part of Frosted Heart.
 *
 * Frosted Heart is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Frosted Heart is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Frosted Heart. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.khjxiaogu.aiwuxia;

public abstract class StreamLineSource{
	boolean hasNext=true;
	int lch=0;
	public StreamLineSource() {
		super();
	}

	protected abstract int readCh();
	public int read() {
		if(lch!=0) {
			int ret=lch;
			lch=0;
			return ret;
		}
		return readCh();
	};
	public void rewind(int c) {
		lch=c;
	}
	public String readLine() {
		StringBuilder sb=new StringBuilder();
		int ch;
		while((ch=read())>0) {
			if(ch=='\r'||ch=='\n') {
				int nch=read();
				
				if(nch>0&&(ch==nch||(nch!='\r'&&nch!='\n'))) {
					lch=nch;
				} else {
					ch=nch;
				}
				break;
			}
			sb.appendCodePoint(ch);
		}
		if(ch<0)hasNext=false;
		return sb.toString();
	}


	public final boolean hasNext() {
		if(lch>0)return true;
		lch=readCh();
		if(lch>0)return true;
		else hasNext=false;
		return hasNext;
	}

}
