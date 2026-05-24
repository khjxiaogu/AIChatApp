package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class QQMcp {
	public static MCPTools create(String groupId,ObjectStorageProvider tos) {
		
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("avatar_fetch", "获取用户头像的图片id以便后续处理。")
				.putParam("user_id", "用户的id，可以从消息的senderId中取得。").tool((data) -> {
					Pattern patt = Pattern.compile("\"user_id\"\\s*:\\s*\"([0-9]+)\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);

						try {
							byte[] picture = HttpRequestBuilder.create("q1.qlogo.cn").defUA()
									.url("/g?b=qq&nk=" + id + "&s=640").get().readBytes();
							String fn = tos.uploadIfNotExists(picture);

							return fn;
						} catch (IOException e) {
							e.printStackTrace();
							return "头像获取失败";
						}

					}
					return "参数格式错误";
				}).build());
		tools.register(
				new ToolData.Builder("avatar_group_recognition", "获取群头像的图片id以便后续处理。").tool((data) -> {

					try {
						byte[] picture = HttpRequestBuilder.create("p.qlogo.cn").defUA()
								.url("/gh/" + groupId + "/" + groupId + "/640").get().readBytes();
						String fn = tos.uploadIfNotExists(picture);

						return fn;
					} catch (IOException e) {
						e.printStackTrace();
						return "头像获取失败";
					}

				}).build());
		return tools;
	}
}
