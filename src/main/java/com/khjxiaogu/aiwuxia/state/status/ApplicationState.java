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