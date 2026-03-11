package com.khjxiaogu.aiwuxia.state.status;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class StateIntf implements Serializable{
	/**
	 * 
	 */
	private static final long serialVersionUID = 846642562841460630L;
	public Map<String, Interface> intfs = new LinkedHashMap<>();
	public Map<String, String> perks = new LinkedHashMap<>();
	public List<String> extras=new ArrayList<>();
	public StateIntf(StateIntf last) {
		super();
		for(Entry<String, Interface> intf:last.intfs.entrySet()) {
			this.intfs.put(intf.getKey(), new Interface(intf.getValue()));
		}
		this.perks.putAll(last.perks);
		this.extras.addAll(last.extras);
	}
	public StateIntf() {
		super();
	}
	public Interface getOrCreateInterface(String name) {
		return intfs.computeIfAbsent(name, Interface::new);
	}
	public void set(StateIntf last) {
		this.intfs.clear();
		this.perks.clear();
		this.extras.clear();
		this.intfs.putAll(last.intfs);;
		this.perks.putAll(last.perks);;
		this.extras.addAll(last.extras);
	}

}