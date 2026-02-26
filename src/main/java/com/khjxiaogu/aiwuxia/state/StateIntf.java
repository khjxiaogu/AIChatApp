package com.khjxiaogu.aiwuxia.state;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
		this.intfs.putAll(last.intfs);;
		this.perks.putAll(last.perks);
		this.extras.addAll(last.extras);
	}
	public StateIntf() {
		super();
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