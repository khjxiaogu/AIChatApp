package com.khjxiaogu.aiwuxia;

public class DoubaoModelApi extends ModelApi {

	public DoubaoModelApi(String name, String host, String url) {
		super(name);
	}

	@Override
	public String getModel(ModelEffort effort) {
		return "doubao-1-5-pro-32k-250115";
	}

	@Override
	public String getToken(String model) {
		return System.getProperty("volcmodeltoken");
	}

	@Override
	public String getUrl(String model) {
		return "/api/v3/chat/completions";
	}

	@Override
	public String getHost(String model) {
		return "ark.cn-beijing.volces.com";
	}

}
