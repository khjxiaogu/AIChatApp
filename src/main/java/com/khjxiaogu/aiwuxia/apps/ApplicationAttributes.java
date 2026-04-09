package com.khjxiaogu.aiwuxia.apps;

import java.util.ArrayList;
import java.util.List;

public class ApplicationAttributes {
	public boolean paidOnly=false;
	public List<String> models=new ArrayList<>();
	public String url=null;
	public AIApplication app;
	public String appid;
	public ApplicationAttributes(AIApplication app,String appid) {
		this.app=app;
		this.appid=appid;
	}
	public String getName() {
		return app.getName();
	}

}
