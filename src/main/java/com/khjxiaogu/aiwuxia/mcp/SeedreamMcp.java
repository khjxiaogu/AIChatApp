package com.khjxiaogu.aiwuxia.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.JimengImageGenerator;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class SeedreamMcp {
	public static MCPTools create(JsonObject config,Consumer<byte[]> imageCollector,Map<String,String> refImages,ObjectStorageProvider tos) {
		JimengImageGenerator jig=new JimengImageGenerator(config);
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("jimeng_image", "使用即梦AI生成图片发送，注意参考图的图片id或名称不会被传递给模型，因此只能用从1开始的顺序索引指代图片。")
				.putParam("reference", "参考图列表，以英文逗号,分隔后给出，参考图可以是图片id或者系统提供的内置参考图文本，同一个人仅使用一张参考图，参考图数量越少越好。")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节200字以上，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String ref=jo.get("reference").getAsString();
					String lprompt="画风是与参考图一致的动漫画风，"+jo.get("prompt").getAsString();;
					String[] refs=ref.split(",");
					List<String> links=new ArrayList<>();
					List<String> errors=new ArrayList<>();
					for(String refImg:refs) {
						if(refImg.length()==72) {
							if(tos.exists(refImg.trim())) {
								links.add(tos.getUrl(refImg.trim()));
							}else {
								errors.add("参考图"+refImg.trim()+"已清理或不存在；");
							}
						}
						String link=refImages.get(refImg.trim());
						if(link==null) {
							errors.add("参考图"+refImg.trim()+"不存在；");
						}else
							links.add(link);
						
					}
					if(errors.size()>0) {
						StringBuilder sb=new StringBuilder();
						for(String err:errors) {
							sb.append(err);
						}
						return "参数错误："+sb.toString();
					}
					CompletableFuture<Void> cf=jig.generateImage(links, lprompt).thenAccept(imageCollector);
					if(cf.isDone()) {
						try {
							cf.get();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (ExecutionException e) {
							e.printStackTrace();
							return "API错误："+e.getMessage();
						}
					}
					return "图片发送成功";
				}).build());
		return tools;
	}
}
