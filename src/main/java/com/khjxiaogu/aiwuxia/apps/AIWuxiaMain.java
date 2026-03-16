package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.MemoryAttributeSet;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.state.status.AttributeSet;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonArrayBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;

public class AIWuxiaMain extends AIApplication {
	Pattern sxPattern = Pattern.compile("【([^】]+)】([^【]+)");
	Pattern intfPattern = Pattern.compile("【([^面]+)面板】");

	List<String> femalenames;
	List<String> malenames;
	{
		
		// naming
		handlers.add((state, ret) -> {

			if (state.getStage() == ApplicationStage.NAMING) {
				if ("换一批".equals(ret)) {
					provideNames(state);

					return null;
				}
				state.setStage(ApplicationStage.STARTED);
			}
			return ret;
		});
		// check interface
		handlers.add((state, ret) -> {
			if (state.getStage() == ApplicationStage.STARTED) {
				if ("查看面板".equals(ret)) {
					state.add(Role.USER, ret, false);
					state.add(Role.ASSISTANT, constructSystem(state.getState()), false);
					return null;
				} 
			}
			return ret;
		});
		handlers.add(revertAndRegen);
		// AI response, always valid
		handlers.add((state, ret) -> {
			
			state.add(Role.USER, ret, true);
			state.appendContextLine(Role.USER, "\n按上述行动继续，至少写出5条大纲，情景剧本部分按大纲扩写，必须包含所有大纲的要点，内容务必详细详尽，文本优美通顺，至少1500字。");
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state, constructSystem(state.getState())));
			state.getLast().setLastState(airet);
			state.addDialogRow();

			return null;
		});
	}

	public AIWuxiaMain(File path) {
		try {
			system = FileUtil.readString(new File(path, "promptwuxia.txt")).replace("\r", "");

			malenames = new ArrayList<>(Arrays.asList(FileUtil
				.readString(new File(path, "name-wuxia-male.txt")).replace("\r", "").split("\n")));
			femalenames = new ArrayList<>(Arrays.asList(FileUtil
				.readString(new File(path, "name-wuxia-female.txt")).replace("\r", "").split("\n")));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	protected static int popFirst(Set<Integer> set) {
		Iterator<Integer> it = set.iterator();
		int ret = it.next();
		it.remove();
		return ret;
	}
	public void provideNames(AISession state) {
		if (state.getStage() == ApplicationStage.NAMING) {
			state.removeLast();
			// state.removeLast();
		} else
			state.add(Role.USER, "请提供主角取名建议。", true);
		StringBuilder inital = new StringBuilder("请创建您的修仙者身份。\n建议姓名：\n");
		Random rnd = new Random();
		Set<Integer> rndNums = new LinkedHashSet<>();
		while (rndNums.size() < 2)
			rndNums.add(rnd.nextInt(malenames.size()));
		inital.append("1. ").append(malenames.get(popFirst(rndNums))).append("，男\n");
		inital.append("2. ").append(malenames.get(popFirst(rndNums))).append("，男\n");
		rndNums.clear();
		while (rndNums.size() < 2)
			rndNums.add(rnd.nextInt(femalenames.size()));
		inital.append("3. ").append(femalenames.get(popFirst(rndNums))).append("，女\n");
		inital.append("4. ").append(femalenames.get(popFirst(rndNums))).append("，女\n");

		state.add(Role.ASSISTANT, inital.toString() + "\n请选择一个姓名，或者输入姓名与性别，或输入“换一批”取得新姓名", "你选额：需要起名建议。==故事大纲==1. 为创建角色提供几个名字。\n==情景剧本==\n==属性面板==\n==操作=="+inital.toString() + "请选择一个姓名。");
	}

	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
		return precessResponse(resp, state);
		
	}

	public class MessageAndRole {
		String role;
		CharSequence message;

		public MessageAndRole(String role, CharSequence charSequence) {
			super();
			this.role = role;
			this.message = charSequence;
		}

	}

	public AIRequest constructAIrequest(AISession state, String status) {
		JsonArrayBuilder<JsonObjectBuilder<JsonObject>> b = JsonBuilder.object().array("messages").object()
			.add("role", "system").add("content", system).end();
		//if (status != null && !status.isEmpty())
		//	b.object().add("role", "system").add("content", status).end();
		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			int len=0;
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				len+=hi.getContextContent().length();
			}
			
			if(len>=140000) {//more than 140000 text:about 100k context,remove until 60000
				HistoryItem lasthi=null;
				it=history.validContextIterator();
				while(it.hasNext()) {
					HistoryItem hi=it.next();
					len-=hi.getContextContent().length();
					hi.setValidContext(false);
					if(len<=60000&&hi.getRole()==Role.ASSISTANT) {
						lasthi=hi;
						break;
					}
					
				}
				if(lasthi!=null)
					state.getExtra().put("lastSummary", constructSystem(lasthi.getLastState()));
			}
			if(state.getExtra().containsKey("lastSummary")) {
				b.object().add("role", "system").add("content", state.getExtra().get("lastSummary")).end();
			}
			it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				b.object().add("role", hi.getRole().getRoleName()).add("content", hi.getContextContent().toString().trim()).end();
				
			}
				
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		Builder builder=AIRequest.builder().taskType(TaskType.STORY);
		return builder.build(b.end().add("temperature", 1.6).add("frequency_penalty", 0.3).add("max_tokens", 8192).end());

	}


	public StringBuilder constructStroy(AISession state) {
		StringBuilder sb = new StringBuilder("==故事大纲==\n");
		for (String hs : state.getState().extras) {
			sb.append(hs).append("\n");
		}
		
		return sb;

	}

	public void provideInitial(AISession state) {
		provideNames(state);
		state.setStage(ApplicationStage.NAMING);
	}

	public ApplicationState precessResponse(AIOutput op, AISession state) throws IOException {
		boolean isWaiting = true;
		int status = 0;
		
		AttributeSet intf = null;
		ApplicationState oldstate = new ApplicationState(state.getState());
		boolean nstateModified = false;
		boolean isDraft=false;
		BufferedReader reader=new BufferedReader(op.getContent());
		handleReasonerContent(op,state);
		String last;
		while ((last=reader.readLine())!=null) {
			if (isWaiting && last.isEmpty()) {
				//System.out.println("\nWaiting");
				continue;
			}

			if (isWaiting) {
				//System.out.println("\n=================Content===============");
				isWaiting = false;
			}
			if(last.startsWith("==故事大纲==")) {
				state.appendContextLine(Role.ASSISTANT, last);
				isDraft=true;
				continue;
			}
			if(isDraft) {
				if(last.startsWith("==属性面板==")||last.startsWith("==操作==")||last.startsWith("==情景剧本==")) {
					isDraft=false;
					//no append because later
				}else {
					
					state.appendContextLine(Role.ASSISTANT, last);
					state.getState().extras.add(last);
					continue;
				}
			}
			
			if (status == 0) {//处理主要剧情
				if (last.startsWith("==属性面板==")) {
					status = 1;
					state.appendContextLine(Role.ASSISTANT, last);
				} else if (last.startsWith("==操作==")) {
					status = 3;
					state.appendContextLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
				} else {
					state.appendLine(Role.ASSISTANT, last, true);
					int codePoint=0,codePoint2=0;
					reader.mark(16);
					while((codePoint=reader.read())!=-1) {
						if(codePoint!='=') {
							reader.mark(16);
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), isDraft);
						}else if((codePoint2=reader.read())=='=') {
							reader.reset();
							break;
						}else {
							reader.mark(16);
							char[] ch=Character.toChars(codePoint);
							state.appendCh(Role.ASSISTANT, String.valueOf(ch), isDraft);
							if(codePoint2!=-1) {
								ch=Character.toChars(codePoint2);
								state.appendCh(Role.ASSISTANT, String.valueOf(ch), isDraft);
							}
						}
					}
				}
			} else if (status == 1) {//处理属性面板
				if (last.startsWith("==操作==")) {
					state.appendContextLine(Role.ASSISTANT, buildAttrStr(state));
					status = 3;
					state.appendContextLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
					
				}else {
					//state.appendInvisibleLine(Role.ASSISTANT, last);
					Matcher m1 = intfPattern.matcher(last);
					if (m1.find()) {
						intf = state.getState().intfs.computeIfAbsent(m1.group(1),s->{
							MemoryAttributeSet itf=new MemoryAttributeSet(s);
							if("主角".equals(s)) {
								itf.putIfAbsent("姓名","");
								itf.putIfAbsent("性别","");
								itf.putIfAbsent("年龄","");
								itf.putIfAbsent("容貌","");
								itf.putIfAbsent("天赋","");
								itf.putIfAbsent("灵根","");
								itf.putIfAbsent("境界","");
								itf.putIfAbsent("功法","");
								itf.putIfAbsent("出身","");
								itf.putIfAbsent("状态","");
								itf.putIfAbsent("灵石","");
								itf.putIfAbsent("物品清单","");
							}else {
								itf.putIfAbsent("姓名","");
								itf.putIfAbsent("性别","");
								itf.putIfAbsent("性格","");
								itf.putIfAbsent("年龄","");
								itf.putIfAbsent("容貌","");
								itf.putIfAbsent("天赋","");
								itf.putIfAbsent("灵根","");
								itf.putIfAbsent("境界","");
								itf.putIfAbsent("功法","");
								itf.putIfAbsent("出身","");
								itf.putIfAbsent("状态","");
								itf.putIfAbsent("物品清单","");
								itf.putIfAbsent("关系网络","");
								itf.putIfAbsent("好感度","");
								itf.putIfAbsent("记忆烙印",""); 
							}
							return itf;
						});
						continue;
					}
					if (intf == null)
						continue;
					Matcher m2 = sxPattern.matcher(last);
					int index = 0;
					while (m2.find(index)) {
						intf.put(m2.group(1), m2.group(2));
						index = m2.end();
					}
					nstateModified = true;
				}
			} else if (status == 2) {
				if (last.startsWith("==操作==")) {
					status = 3;
					state.appendContextLine(Role.ASSISTANT, last);
					state.appendLine(Role.ASSISTANT, "===== 操作 =====", false);
				} else {
					int pos = last.indexOf('.');
					if (pos > 0) {
						String cont = last.substring(pos + 1).trim();
						String num = last.substring(0, pos).trim();
						if (cont.startsWith("【删除】"))
							state.getState().perks.remove(num);
						else
							state.getState().perks.put(num, cont);
						nstateModified = true;
					}
				}
			} else {
				state.appendLine(Role.ASSISTANT, last, true);
			}

		}
		state.appendLine(Role.ASSISTANT, "\n输入“查看面板”查看你的面板，输入“重新生成”重新生成剧情，输入“撤回”删除上一次对话。", false);

		return nstateModified ? oldstate : null;
	}
	private String buildAttrStr(AISession state) {
		if (state == null || state.getState().intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("");
		for (AttributeSet intf : state.getState().intfs.values())
			sb.append(intf.toString());
		return sb.toString();
	}
	public String constructSystem(ApplicationState state) {
		if (state == null || state.intfs.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("==属性面板==\n");
		for (AttributeSet intf : state.intfs.values())
			sb.append(intf.toString());
		//sb.append("【叙事轨迹】\n");
		/*for (Entry<String, String> p : state.getState().perks.entrySet()) {
			sb.append(p.getKey()).append(". ").append(p.getValue()).append("\n");
		}*/

		return sb.toString();

	}

	@Override
	public String getName() {
		return "武侠模拟器";
	}

	@Override 
	public String getBrief(AISession state) {
		if(state.getState().intfs.isEmpty())
			return null;
		return state.getState().intfs.values().iterator().next().get("姓名");
	}
}
