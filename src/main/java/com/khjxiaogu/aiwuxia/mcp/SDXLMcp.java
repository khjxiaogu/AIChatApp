package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.PainterSession;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
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
		public LoraConfigurations(JsonObject lora) {
			super();
			li.add(new LoraConfiguration(lora));
			
		}
		public LoraConfigurations(JsonArray loras) {
			super();
			for(JsonElement lora:loras) {
				li.add(new LoraConfiguration(lora.getAsJsonObject()));
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
		public String loraName;
		public float loraValue;
		public final boolean fixed;
		public LoraConfiguration(JsonObject loraConfig) {
			super();
			this.loraName = loraConfig.get("key").getAsString();
			this.loraValue = loraConfig.get("weight").getAsFloat();
			if(loraConfig.has("fixed")) {
				fixed=loraConfig.get("fixed").getAsBoolean();
			}else
				fixed=false;
		}
		LoraConfiguration(String loraName, float loraValue, boolean fixed) {
			super();
			this.loraName = loraName;
			this.loraValue = loraValue;
			this.fixed = fixed;
		}
		LoraConfiguration(String loraName, float loraValue) {
			super();
			this.loraName = loraName;
			this.loraValue = loraValue;
			this.fixed = false;
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
        Map<String, Float> fixedValueMap = new HashMap<>();
        for (LoraConfigurations configs : configList) {
        	for(LoraConfiguration config:configs.li) {
	            String name = config.loraName;
	            float value = config.loraValue;
	            if(!fixedValueMap.containsKey(name)&&config.fixed) {
	            	fixedValueMap.put(name, value);
	            	continue;
	            }
	            
	            if (!minValueMap.containsKey(name) || value < minValueMap.get(name)) {
	                minValueMap.put(name, value);
	            }
        	}
        }
        // 将去重后的结果转为列表，便于后续处理
        

        // 第二步：如果有两个或以上不同的 loraName，每个 loraValue 乘以 0.8
        if (minValueMap.size() >= 2) {
            for (Map.Entry<String, Float> entry : minValueMap.entrySet()) {
                entry.setValue(entry.getValue() * 0.8f);
            }
        }
        minValueMap.putAll(fixedValueMap);
        List<Map.Entry<String, Float>> uniqueEntries = new ArrayList<>(minValueMap.entrySet());
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
	public static MCPTools createRemoteLocal(PainterSession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,List<String> charas,Function<String,String> urlGetter,boolean isNsfw) {
		BiFunction<String,String,CompletableFuture<JsonObject>> func=state::requestLocal;
		return create(state,tos,lora,charas,isNsfw,urlGetter,func);
	}
	public static MCPTools createLocal(AISession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,List<String> charas,boolean isNsfw,ResourceLock lock) {
		BiFunction<String,String,CompletableFuture<JsonObject>> func=(url,payload)->CompletableFuture.supplyAsync(()->{
			try(ResourcePermit l=lock.acquire(12))  {
				return HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; utf-8")
				.url("/sdapi/v1/txt2img")
				.post()
				.send(payload.toString())
				.readJson();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		return create(state,tos,lora,charas,isNsfw,s->{
			try {
				return Base64.getEncoder().encodeToString(tos.download(s,state::addUsage));
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		},func);
	}
	/**
	 * 从字符串列表中搜索同时包含所有搜索关键词的字符串。
	 * 搜索关键词由 searchStr 按空格切分得到，多个关键词之间为“与”关系。
	 *
	 * @param list      待搜索的字符串列表
	 * @param searchStr 包含关键词的字符串，以空格分隔
	 * @return 包含所有关键词的字符串集合（HashSet，不保证顺序）
	 */
	public static Set<String> searchContainingAllKeywords(List<String> list, String searchStr) {
	    Set<String> result = new HashSet<>();
	    if (list == null || searchStr == null) {
	        return result;
	    }

	    // 按空格切分，并过滤掉开头/结尾空格导致的空串
	    String[] keywords = searchStr.trim().split("\\s+");
	    if (keywords.length == 0 || (keywords.length == 1 && keywords[0].isEmpty())) {
	        return result;
	    }

	    for (String item : list) {
	        if (item == null) {
	            continue;
	        }
	        boolean containsAll = true;
	        for (String keyword : keywords) {
	            if (!item.contains(keyword)) {
	                containsAll = false;
	                break;
	            }
	        }
	        if (containsAll) {
	            result.add(item);
	        }
	    }
	    if(result.isEmpty()) {
	        for (String item : list) {
		        if (item == null) {
		            continue;
		        }
		        for (String keyword : keywords) {
		            if (item.contains(keyword)) {
		 		        result.add(item);
		 		        break;
		            }
		        }
		       
		    }
	    }
	    return result;
	}
	public static List<String> readLinesFromFile(File filePath) {
	    // 使用 Files.readAllLines，默认 UTF-8 编码
		if(!filePath.exists())
			return null;
	    try {
			return Files.readAllLines(filePath.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
	    return null;
	}
	public static MCPTools create(AISession state,ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,List<String> charas,boolean isNsfw,Function<String,String> urlGetter,BiFunction<String,String,CompletableFuture<JsonObject>> func) {
		MCPTools tools=new MCPTools();
		
		Map<String, int[]> resolutions = new HashMap<>();
		resolutions.put("16:9", new int[] { 1920, 1080 });
		resolutions.put("9:16", new int[] { 1080, 1920 });
		resolutions.put("3:4", new int[] { 1200, 1600 });
		resolutions.put("4:3", new int[] { 1600, 1200 });
		resolutions.put("1:1", new int[] { 1200, 1200 });
		/*resolutions.put("16:9", new int[] { 1280, 720 });
		resolutions.put("9:16", new int[] { 720, 1280 });
		resolutions.put("3:4", new int[] { 960, 1280 });
		resolutions.put("4:3", new int[] { 1280, 960 });
		resolutions.put("1:1", new int[] { 1280, 1280 });*/
		List<PromptClass> commonNegatives=new ArrayList<>();
		for(String s:"low quality,bad anatomy,unnatural hair color,bad feet,malformed hands,bad hands,missing fingers,fused fingers,too many fingers,poorly drawn hands,malformed limbs,missing limb,mutated hands,extra arms,extra limb,mutated hands and fingers,extra legs,floating limbs,disconnected limbs,trademark,artist's name,username,watermark,signature,watermark,text,words,extra limbs,missing limbs,distorted,blurry,fused toes,too many toes,extra toes".split(",")) {
			commonNegatives.add(new PromptClass(s));
		}
		List<PromptClass> commonPositive=new ArrayList<>();
		for(String s:"(masterpiece),(best quality),(absurdres)".split(",")) {
			commonPositive.add(new PromptClass(s));
		}
		if(!isNsfw)
			commonNegatives.add(new PromptClass("nsfw","((nsfw))"));

		if(charas!=null)
		tools.register(new ToolData.Builder("search_character", "通过关键词搜索特定角色的提示词，返回相符的提示词，以,隔开")
				.putParam("keyword", "角色英文名")
				.tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						
						String keyword=jo.get("keyword").getAsString();
						System.out.println(keyword);
						StringBuilder sb=new StringBuilder("符合的角色提示词列表：");
						for(String key:searchContainingAllKeywords(charas,keyword)) {
							sb.append(key).append(",");
						}
						return sb.toString();
					} catch (Exception e) {
						e.printStackTrace();
					}
					return "参数格式错误";
				}).build());
		tools.register(new ToolData.Builder("image_interrogate", "通过WD1.4模型反推图片的所有可能提示词按可能性从大到小排列，需要结合图片描述功能把错误的提示词排除。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容")
				.tool((data) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						try {
							JsonObject datax = interrogate(urlGetter.apply(id),func);
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
						String extras="";
						if(!validatePrompt(prompt)||!validatePrompt(negative)) {
							return "失败，提示词中有非英文/符号的内容。";
						}
						prompt=appendCommonPrompt(prompt,commonPositive);
						negative=appendCommonPrompt(negative,commonNegatives);
						Set<LoraConfigurations> loras=addLoras(prompt,lora);
						prompt=prompt+processLoraConfigurations(loras);
						if(loras.size()>1)
							extras="生成多人图片需要使用regional_image，使用该工具生成图片可能混乱，需要检查。";
						
						System.out.println(prompt);
						System.out.println(negative);
						try {
							byte[] image=generateImage(prompt,
									negative
									,20,
									its[0],
									its[1],func);
							
							String fn = tos.uploadIfNotExists(image,state::addUsage);
							Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
									.multimodal(MultimodalType.IMAGE_ONLY).strength(ReasoningStrength.WEAK);
							builder.addHistoryItem(Role.SYSTEM,
									"请观察图片，简要描述图片内容，并判断其是否为18+成人向图片并给出原因。也需要判断图片是否包含AI生成错误，并给出依据。"
									+ "判定标准：直接裸露性器官，或者包含血腥暴力等内容。仅擦边不属于。");

							builder.addHistoryItem(
									new DirectHistoryItem(Role.USER, new ImageContent(tos.getPublicUrl(fn,state::addUsage))));
							AIOutput output=LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build());
							output.addUsageListener(state::addUsage);
							String caption= FileUtil.printAndCollectContent(
								output.getContent());
							return "生成成功，图片id为："+fn+"。"+extras+caption;
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
				.putParam("picture_id", "如果要使用图生图，务必在这里填上图片ID，否则不填或留空")
				.putParam("common_positive", "共用正面提示词")
				.putParam("first_positive", "第一区域提示词，必须是纯英文，不需要包含画质提示词。")
				.putParam("second_positive", "第二区域提示词，必须是纯英文，不需要包含画质提示词。")
				.putParam("common_negative", "共用负面提示词，必须是纯英文，不需要包含画质提示词。")
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
						String ratio=ratios[0]+","+ratios[1];
						prompt=prompt+" BREAK\n";
						prompt=prompt+jo.get("first_positive").getAsString()+" BREAK\n";
						prompt=prompt+jo.get("second_positive").getAsString();
						Set<LoraConfigurations> loras=addLoras(prompt,lora);
						prompt=prompt+processLoraConfigurations(loras);
						System.out.println(prompt);
						System.out.println(negative);
						if(!validatePrompt(prompt)||!validatePrompt(negative)) {
							return "失败，提示词中有非英文/符号的内容。";
						}
						try {
							byte[] image;
							if(jo.has("picture_id")&&jo.get("picture_id").getAsString().length()>20) {
								image=generateRegionalImage2Image(urlGetter.apply(jo.get("picture_id").getAsString()),prompt,negative,30,
										its[0],
										its[1],isRow,ratio,func);
							}else{
								image=generateRegionalImage(prompt,negative,30,
										its[0],
										its[1],isRow,ratio,func);
							}
							String fn = tos.uploadIfNotExists(image,state::addUsage);
							Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
									.multimodal(MultimodalType.IMAGE_ONLY).strength(ReasoningStrength.WEAK);
							builder.addHistoryItem(Role.SYSTEM,
									"请观察图片，简要描述图片内容，并判断其是否为18+成人向图片并给出原因。也需要判断图片是否包含AI生成错误，并给出依据。"
									+ "判定标准：直接裸露性器官，或者包含血腥暴力等内容。仅擦边不属于。");

							builder.addHistoryItem(
									new DirectHistoryItem(Role.USER, new ImageContent(tos.getPublicUrl(fn,state::addUsage))));
							AIOutput output=LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build());
							output.addUsageListener(state::addUsage);
							String caption= FileUtil.printAndCollectContent(
								output.getContent());
							return "生成成功，图片id为："+fn+"。"+caption;
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
						Set<LoraConfigurations> loras=addLoras(prompt,lora);
						prompt=prompt+processLoraConfigurations(loras);
						System.out.println(prompt);
						System.out.println(negative);
						try {

							byte[] image=generateImage2Image(urlGetter.apply(pid),prompt,negative
									,30,
									its[0],
									its[1],func);

							
							String fn = tos.uploadIfNotExists(image,state::addUsage);
							Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
									.multimodal(MultimodalType.IMAGE_ONLY).strength(ReasoningStrength.WEAK);
							builder.addHistoryItem(Role.SYSTEM,
									"请观察图片，简要描述图片内容，并判断其是否为18+成人向图片并给出原因。也需要判断图片是否包含AI生成错误，并给出依据。"
									+ "判定标准：直接裸露性器官，或者包含血腥暴力等内容。仅擦边不属于。");

							builder.addHistoryItem(
									new DirectHistoryItem(Role.USER, new ImageContent(tos.getPublicUrl(fn,state::addUsage))));
							AIOutput output=LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build());
							output.addUsageListener(state::addUsage);
							String caption= FileUtil.printAndCollectContent(
								output.getContent());

							return "生成成功，图片id为："+fn+"。"+caption;
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
	public static Set<LoraConfigurations> addLoras(String prompt,Map<String, LoraConfigurations> lora) {
		Set<LoraConfigurations> usingLoras=new HashSet<>();
		for(Entry<String, LoraConfigurations> s:lora.entrySet()) {
			if(matchWord(prompt,s.getKey()))
				usingLoras.add(s.getValue());
		}
		return usingLoras;
	}
	private static byte[] getFirstImage(JsonArray images) {
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
	public static JsonObject interrogate(String image,BiFunction<String,String,CompletableFuture<JsonObject>> connector) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("image", image);
		payload.addProperty("model", "wd-v1-4-moat-tagger.v2");
		payload.addProperty("threshold", 0.2);
		
		JsonObject jsonResponse;
	
		try {
			jsonResponse = connector.apply("/tagger/v1/interrogate", payload.toString()).get();
			connector.apply("/tagger/v1/unload-interrogators", "");
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			throw new IOException("服务异常");
		}
			
		
		return jsonResponse.get("caption").getAsJsonObject().get("tag").getAsJsonObject();
	}
	public static byte[] generateImage(String prompt, String negativePrompt, int steps, int width, int height,BiFunction<String,String,CompletableFuture<JsonObject>> connector) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "Euler a");
		payload.addProperty("scheduler", "Karras");
		JsonObject jsonResponse;
		
		try {
			jsonResponse = connector.apply("/sdapi/v1/txt2img", payload.toString()).get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			throw new IOException("服务异常");
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
	}
	public static byte[] generateImage2Image(String baseImg,String prompt, String negativePrompt, int steps, int width, int height,BiFunction<String,String,CompletableFuture<JsonObject>> connector) throws IOException {
		JsonObject payload = new JsonObject();
		payload.add("init_images", JsonBuilder.array().add(baseImg).end());
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("scheduler", "Karras");

		
		payload.addProperty("resize_mode", 0);
		payload.addProperty("denoising_strength", 0.8);
		JsonObject jsonResponse;
		try {
			jsonResponse = connector.apply("/sdapi/v1/img2img", payload.toString()).get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			throw new IOException("服务异常");
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
		
	}
	public static byte[] generateRegionalImage2Image(String baseImg,String prompt, String negativePrompt, int steps, int width, int height,boolean isRow,String ratio,BiFunction<String,String,CompletableFuture<JsonObject>> connector) throws IOException {
		JsonObject payload = new JsonObject();
		payload.add("init_images", JsonBuilder.array().add(baseImg).end());
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("scheduler", "Karras");
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
		
		payload.addProperty("resize_mode", 0);
		payload.addProperty("denoising_strength", 0.8);
		JsonObject jsonResponse;
		try {
			jsonResponse = connector.apply("/sdapi/v1/img2img", payload.toString()).get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			throw new IOException("服务异常");
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
		
	}
	public static byte[] generateRegionalImage(String prompt, String negativePrompt, int steps, int width, int height,boolean isRow,String ratio,BiFunction<String,String,CompletableFuture<JsonObject>> connector) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("scheduler", "Karras");
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
		
		try {
			jsonResponse = connector.apply("/sdapi/v1/txt2img", payload.toString()).get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			throw new IOException("服务异常");
		}
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());

	}
}
