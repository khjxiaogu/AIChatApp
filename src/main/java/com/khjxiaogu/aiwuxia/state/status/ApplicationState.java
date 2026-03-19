/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.state.status;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ApplicationState implements Serializable{
	/**
	 * 
	 */
	private static final long serialVersionUID = 846642562841460630L;
	public Map<String, MemoryAttributeSet> intfs = new LinkedHashMap<>();
	public Map<String, String> perks = new LinkedHashMap<>();
	public List<String> extras=new ArrayList<>();
	public ApplicationState(ApplicationState last) {
		super();
		for(Entry<String, MemoryAttributeSet> intf:last.intfs.entrySet()) {
			this.intfs.put(intf.getKey(), new MemoryAttributeSet(intf.getValue()));
		}
		this.perks.putAll(last.perks);
		this.extras.addAll(last.extras);
	}
	public ApplicationState() {
		super();
	}
	public AttributeSet getOrCreateInterface(String name) {
		return intfs.computeIfAbsent(name, MemoryAttributeSet::new);
	}
	public void set(ApplicationState last) {
		this.intfs.clear();
		this.perks.clear();
		this.extras.clear();
		this.intfs.putAll(last.intfs);;
		this.perks.putAll(last.perks);;
		this.extras.addAll(last.extras);
	}

}