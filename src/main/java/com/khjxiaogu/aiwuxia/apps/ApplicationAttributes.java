package com.khjxiaogu.aiwuxia.apps;

import java.util.ArrayList;
import java.util.List;

public class ApplicationAttributes {
	public boolean paidOnly=false;
	public boolean freeNow=false;
	public List<String> models=new ArrayList<>();
	/** 应用默认模型标识，为空时回退到 {@link #models} 的第一项 */
	public String defaultModel=null;
	public String url=null;
	public AIApplication app;
	public String appid;
	public String ui;
	public ApplicationAttributes(AIApplication app,String appid) {
		this.app=app;
		this.appid=appid;
	}
	public String getName() {
		return app.getName();
	}

	/**
	 * 获取本应用的默认模型标识。
	 * <p>
	 * 未在 meta.json 中配置 {@code defaultModel} 时取 {@link #models} 的第一项，
	 * 即模型清单中排在最前面的模型。会话未显式指定模型时会使用该值。
	 * </p>
	 *
	 * @return 默认模型标识，没有可用模型时返回空字符串
	 */
	public String getDefaultModelHint() {
		if(defaultModel!=null&&!defaultModel.isEmpty())
			return defaultModel;
		return models==null||models.isEmpty()?"":models.get(0);
	}

}
