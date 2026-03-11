package com.khjxiaogu.aiwuxia.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LLMConnector {
	private static ModelRouter router;
	private LLMConnector() {}
	public static void initDefault() {
		List<ModelProvider> providers=new ArrayList<>();
		if(System.getProperty("deepseektoken")!=null)
			providers.add(new DeepseekModelProvider());
		if(System.getProperty("volcmodeltoken")!=null)
			providers.add(new VolcanoModelProvider());
		router=new DefaultModelRouter(providers);
	}
	public static AIOutput call(AIRequest request) throws ModelRouteException, IOException {
		System.out.println(request);
		return router.route(request).execute(request);
	}
}
