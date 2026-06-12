package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOSUsage;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class SDXLMcp {
	public static class LoraConfigurations{
		public final List<LoraConfiguration> li=new ArrayList<>(5);
		public LoraConfigurations(String loraName, float loraValue) {
			super();
			li.add(new LoraConfiguration(loraName,loraValue));
		}
		public LoraConfigurations(String loraName, float loraValue,String loraName2, float loraValue2) {
			super();
			li.add(new LoraConfiguration(loraName,loraValue));
			li.add(new LoraConfiguration(loraName2,loraValue2));
		}

		public LoraConfigurations(LoraConfiguration...loras) {
			super();
			for(LoraConfiguration lora:loras) {
				li.add(lora);
			}
		}
	}
	public static class PromptClass{
		private final String prompt;
		private final String promptText;
		public PromptClass(String prompt) {
			super();
			this.prompt = cleanToken(prompt);
			this.promptText = prompt;
		}
	    private static String cleanToken(String token) {
	    	token = token.replaceAll(":[\\d.]+$", "").trim();
	        while (token.startsWith("(") && token.endsWith(")")) {
	        	token = token.substring(1, token.length() - 1).trim();
	        }
	        token = token.replaceAll(":[\\d.]+$", "").trim();
	        while (token.startsWith("[") && token.endsWith("]")) {
	        	token = token.substring(1, token.length() - 1).trim();
	        }
	        token = token.replaceAll(":[\\d.]+$", "").trim();
	        return token;
	    }
		public PromptClass(String prompt, String promptText) {
			super();
			this.prompt = prompt;
			this.promptText = promptText;
		}
		@Override
		public String toString() {
			return promptText;
		}
		@Override
		public int hashCode() {
			return Objects.hash(prompt);
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			PromptClass other = (PromptClass) obj;
			return Objects.equals(prompt, other.prompt);
		}
		
	}
	public static class LoraConfiguration{
		public final String loraName;
		public final float loraValue;
		public LoraConfiguration(String loraName, float loraValue) {
			super();
			this.loraName = loraName;
			this.loraValue = loraValue;
		}
		
	}
    /**
     * 处理 LoraConfiguration 列表
     * @param configList 输入的配置列表
     * @return 格式化后的字符串
     */
    public static String processLoraConfigurations(Collection<LoraConfigurations> configList) {
        if (configList == null || configList.isEmpty()) {
            return "";
        }

        // 第一步：根据 loraName 去重，保留 loraValue 最小的项
        Map<String, Float> minValueMap = new HashMap<>();
        for (LoraConfigurations configs : configList) {
        	for(LoraConfiguration config:configs.li) {
	            String name = config.loraName;
	            float value = config.loraValue;
	            if (!minValueMap.containsKey(name) || value < minValueMap.get(name)) {
	                minValueMap.put(name, value);
	            }
        	}
        }

        // 将去重后的结果转为列表，便于后续处理
        List<Map.Entry<String, Float>> uniqueEntries = new ArrayList<>(minValueMap.entrySet());

        // 第二步：如果有两个或以上不同的 loraName，每个 loraValue 乘以 0.8
        if (uniqueEntries.size() >= 2) {
            for (Map.Entry<String, Float> entry : uniqueEntries) {
                entry.setValue(entry.getValue() * 0.8f);
            }
        }

        // 第三步：按 loraName 排序（保证输出顺序一致），组装字符串
        uniqueEntries.sort(Comparator.comparing(Map.Entry::getKey));

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Float> entry : uniqueEntries) {
            String formattedValue = String.format("%.2f", entry.getValue());
            result.append(String.format("<lora:%s:%s>", entry.getKey(), formattedValue));
        }

        return result.toString();
    }
    public static boolean matchWord(String text, String word) {
        if (text == null || text.isEmpty() || word == null || word.isEmpty()) {
            return false;
        }
        String regex = "\\b" + Pattern.quote(word) + "\\b";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        return matcher.find();
    }
    public static boolean validatePrompt(String prompt) {
    	for(int i=0;i<prompt.length();i++){
			if(prompt.codePointAt(i)>0x100) {
				return false;
			}
		}
    	return true;
    }
    public static String appendCommonPrompt(String prompt,Collection<PromptClass> commons) {
    	Set<PromptClass> prompts=new LinkedHashSet<>(commons);
		if(!prompt.isEmpty()) {
			for(String s:prompt.split(",")) {
				prompts.add(new PromptClass(s));
			}
		}
		StringBuilder sb=new StringBuilder();
		for(PromptClass s:prompts) {
			sb.append(s).append(",");
		}
		sb.deleteCharAt(sb.length()-1);
		return sb.toString();
    }
	public static MCPTools create(AISession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,boolean isNsfw,ResourceLock lock) {
		MCPTools tools=new MCPTools();
		Map<String, int[]> resolutions = new HashMap<>();
		resolutions.put("16:9", new int[] { 1920, 1080 });
		resolutions.put("9:16", new int[] { 1080, 1920 });
		resolutions.put("3:4", new int[] { 1200, 1600 });
		resolutions.put("4:3", new int[] { 1600, 1200 });
		resolutions.put("1:1", new int[] { 1200, 1200 });
		List<PromptClass> commonNegatives=new ArrayList<>();
		for(String s:"low quality,bad anatomy,unnatural hair color,bad feet,malformed hands,bad hands,missing fingers,fused fingers,too many fingers,poorly drawn hands,malformed limbs,missing limb,mutated hands,extra arms,extra limb,mutated hands and fingers,extra legs,floating limbs,disconnected limbs,trademark,artist's name,username,watermark,signature,watermark,text,words".split(",")) {
			commonNegatives.add(new PromptClass(s));
		}
		List<PromptClass> commonPositive=new ArrayList<>();
		for(String s:"(masterpiece),(best quality),(absurdres)".split(",")) {
			commonPositive.add(new PromptClass(s));
		}
		if(!isNsfw)
			commonNegatives.add(new PromptClass("nsfw","((nsfw))"));
		
		tools.register(new ToolData.Builder("image_interrogate", "通过WD1.4模型反推图片的所有可能提示词按可能性从大到小排列，需要结合图片描述功能把错误的提示词排除。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容")
				.tool((data) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						try {
							JsonObject datax = interrogate(tos.download(id),lock);
							StringBuilder sb=new StringBuilder("按可能性从大到小排列的提示词：");
							for(String key:datax.keySet()) {
								sb.append(key).append(",");
							}
							return sb.toString();
						} catch (IOException e) {
							e.printStackTrace();
						}
						return "服务暂不可用";
						
					}
					return "参数格式错误";
				}).build());
		
		
		tools.register(new ToolData.Builder("sdxl_gen_image",
				"使用Stable Diffusion XL生成图片，生成后需要使用其他工具发送。")
				.putParam("positive", "正面提示词，必须是纯英文，不需要包含画质提示词。")
				.putParam("negative", "负面提示词，必须是纯英文，不需要包含画质提示词。")
				.putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一")
				.tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						System.out.println(data);
						String resolution = jo.get("resolution").getAsString();
						int[] its = resolutions.get(resolution);
						if (its == null)
							return "不支持的分辨率设置";
						String prompt=jo.get("positive").getAsString();
						String negative=jo.get("negative").getAsString();
						
						if(!validatePrompt(prompt)||!validatePrompt(negative)) {
							return "失败，提示词中有非英文/符号的内容。";
						}
						prompt=appendCommonPrompt(prompt,commonPositive);
						negative=appendCommonPrompt(negative,commonNegatives);
						prompt=addLoras(prompt,lora);
						
						System.out.println(prompt);
						System.out.println(negative);
						try {
							byte[] image=generateImage(prompt,
									negative
									,30,
									its[0],
									its[1],lock);
							state.addUsage(new TOSUsage(image.length));
							String fn = tos.uploadIfNotExists(image);
							return "生成成功，图片id为："+fn;
						} catch (IOException e) {
							e.printStackTrace();
							return "图片生成失败，服务暂不可用。";
						}
					}catch(Throwable t) {
						t.printStackTrace();
						return "参数格式错误";
					}
				}).build());
		tools.register(new ToolData.Builder("regional_image",
				"使用Stable Diffusion XL搭配分区提示词生成图片，不需要包含画质提示词，提示词必须是纯英文，生成后需要使用其他工具发送。")
				.putParam("common_positive", "共用正面提示词")
				.putParam("first_positive", "第一区域提示词")
				.putParam("second_positive", "第二区域提示词")
				.putParam("common_negative", "共用负面提示词")
				.putParam("placement", "区域排布，必须是row/col之一，col表示两个区域呈现左右结构，row表示两个区域呈现上下结构")
				.putParam("area_ratio", "区域比例，必须是以x:y的格式，其中x是第一区域面积占比，y是第二区域面积占比，x和y必须为整数。例如1:1")
				.putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					try {
						String resolution = jo.get("resolution").getAsString();
						int[] its = resolutions.get(resolution);
						if (its == null)
							return "不支持的分辨率设置";
						String placement=jo.get("placement").getAsString();
						boolean isRow=false;
						if(placement.startsWith("row")) {
							isRow=true;
						}else if(placement.startsWith("col")) {
							isRow=false;
						}else {
							return "区域排布错误，区域排布必须是row/col之一";
						}
						String area_ratio=jo.get("area_ratio").getAsString();
						if(!area_ratio.contains(":")) {
							return "区域比例错误，必须是x:y的格式";
						}
						String[] ratios=area_ratio.split(":");
						if(ratios.length!=2) {
							return "区域比例错误，必须是x:y的格式";
						}

						String prompt=appendCommonPrompt(jo.get("common_positive").getAsString(),commonPositive);
						String negative=appendCommonPrompt(jo.get("common_negative").getAsString(),commonNegatives);
						prompt=addLoras(prompt,lora);
						String ratio=ratios[0]+","+ratios[1];
						prompt=prompt+" BREAK\n";
						prompt=prompt+jo.get("first_positive").getAsString()+" BREAK\n";
						prompt=prompt+jo.get("second_positive").getAsString();
						prompt=addLoras(prompt,lora);
						System.out.println(prompt);
						System.out.println(negative);
						try {
							byte[] image=generateRegionalImage(prompt,negative,30,
									its[0],
									its[1],isRow,ratio,lock);
							state.addUsage(new TOSUsage(image.length));
							String fn = tos.uploadIfNotExists(image);
							return "生成成功，图片id为："+fn;
						} catch (IOException e) {
							e.printStackTrace();
							return "图片生成失败，服务暂不可用。";
						}
					}catch(Throwable t) {
						t.printStackTrace();
						return "参数格式错误";
					}
				}).build());
		tools.register(new ToolData.Builder("image_to_image",
				"使用Stable Diffusion XL的图生图功能，生成后需要使用其他工具发送。")
				.putParam("positive", "正面提示词，必须是纯英文。")
				.putParam("negative", "负面提示词，必须是纯英文。")
				.putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容")
				.tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						System.out.println(data);
						String resolution = jo.get("resolution").getAsString();
						int[] its = resolutions.get(resolution);
						if (its == null)
							return "不支持的分辨率设置";
						String prompt=jo.get("positive").getAsString();
						String negative=jo.get("negative").getAsString();
						String pid=jo.get("picture_id").getAsString();
						prompt=appendCommonPrompt(prompt,commonPositive);
						negative=appendCommonPrompt(negative,commonNegatives);
						prompt=addLoras(prompt,lora);
						System.out.println(prompt);
						System.out.println(negative);
						try {

							byte[] image=generateImage2Image(tos.download(pid),prompt,negative
									,30,
									its[0],
									its[1],lock);

							state.addUsage(new TOSUsage(image.length));
							String fn = tos.uploadIfNotExists(image);
	

							return "生成成功，图片id为："+fn;
						} catch (IOException e) {
							e.printStackTrace();
							return "图片生成失败，服务暂不可用。";
						}
					}catch(Throwable t) {
						t.printStackTrace();
						return "参数格式错误";
					}
				}).build());
		return tools;
	}
	public static String addLoras(String prompt,Map<String, LoraConfigurations> lora) {
		Set<LoraConfigurations> usingLoras=new HashSet<>();
		for(Entry<String, LoraConfigurations> s:lora.entrySet()) {
			if(matchWord(prompt,s.getKey()))
				usingLoras.add(s.getValue());
		}
		return prompt+processLoraConfigurations(usingLoras);
	}
	public static JsonObject interrogate(byte[] image,ResourceLock lock) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("image", Base64.getEncoder().encodeToString(image));
		payload.addProperty("model", "wd-v1-4-moat-tagger.v2");
		payload.addProperty("threshold", 0.2);
		JsonObject jsonResponse;
		try(ResourcePermit l=lock.acquire(3))  {
			jsonResponse = HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; utf-8")
			.url("/tagger/v1/interrogate")
			.post()
			.send(payload.toString())
			.readJson();
			HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; utf-8")
			.url("/tagger/v1/unload-interrogators")
			.post(false).readString();
		}
		return jsonResponse.get("caption").getAsJsonObject().get("tag").getAsJsonObject();
	}
	public static byte[] generateImage(String prompt, String negativePrompt, int steps, int width, int height,ResourceLock lock) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");

		JsonObject jsonResponse;
		try(ResourcePermit l=lock.acquire(12))  {
			jsonResponse = HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; utf-8")
			.url("/sdapi/v1/txt2img")
			.post()
			.send(payload.toString())
			.readJson();
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
	}
	public static byte[] generateImage2Image(byte[] baseImg,String prompt, String negativePrompt, int steps, int width, int height,ResourceLock lock) throws IOException {
		JsonObject payload = new JsonObject();
		payload.add("init_images", JsonBuilder.array().add(Base64.getEncoder().encodeToString(baseImg)).end());
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("resize_mode", 0);
		payload.addProperty("denoising_strength", 0.8);
		JsonObject jsonResponse;
		try(ResourcePermit l=lock.acquire(12))  {
			jsonResponse = HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; utf-8")
			.url("/sdapi/v1/img2img")
			.post()
			.send(payload.toString())
			.readJson();
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
		
	}
	public static byte[] getFirstImage(JsonArray images) {
		for (int i = 0; i < images.size(); i++) {
			try {
				String base64Image = images.get(i).getAsString();

				if (base64Image.contains(",")) {
					base64Image = base64Image.split(",", 2)[1];
				}
				byte[] imageBytes = Base64.getDecoder().decode(base64Image);
				return imageBytes;
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		return null;
	}
	public static byte[] generateRegionalImage(String prompt, String negativePrompt, int steps, int width, int height,boolean isRow,String ratio,ResourceLock lock) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.add("alwayson_scripts", JsonBuilder.object().object("Regional Prompter").array("args")
				.add(true)
				.add(false)
				.add("Matrix")
				.add(isRow?"Rows":"Columns")
				.add("Mask")
				.add("Prompt")
				.add(ratio)
				.add("0.2")
				.add(false)
				.add(true)
				.add(false)
				.add("Attention")
				.array().add(false).end()
				.add("0")
				.add("0")
				.add("0.4")
				.add(JsonNull.INSTANCE)
				.add("0")
				.add("0")
				.add(false).end().end().end());
		JsonObject jsonResponse;
		try(ResourcePermit l=lock.acquire(12))  {
			jsonResponse = HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; utf-8")
			.url("/sdapi/v1/txt2img")
			.post()
			.send(payload.toString())
			.readJson();
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());

	}

}
