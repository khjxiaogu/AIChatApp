/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator;
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator.Rule;
import com.khjxiaogu.aiwuxia.state.status.AttributeValidator.RuleSerializer;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class ParameterLimitGenerator {

	public ParameterLimitGenerator() {
	}
	public static void main(String[] args) throws IOException {
		File basePath=new File("save");
		{
			
			AttributeValidator blytrpg=new AttributeValidator();
			blytrpg.addRule("(归海枫|贺云舟|祁连遥|苒雪忆|童玲|汪星涵|扬雨晴|周方谨|赵书宇)表情", "(哀伤|震惊|惊恐|严肃|哭|笑|生气)");
			blytrpg.addRule("时间", "上午|下午|黄昏|夜晚");
			blytrpg.addRule("(归海枫|贺云舟|祁连遥|苒雪忆|童玲|汪星涵|扬雨晴|周方谨|赵书宇)位置", "(前|侧|后|不在)");
			blytrpg.addRule("地点", "(活动室|教室|校门内|秘密地点|食堂|外界|庭院|校门外|小屋|悬崖|树林|中庭|走廊|操场|宿舍)");
			blytrpg.addRule("天数", "[0-9]+");
			System.out.println(blytrpg.validate("扬雨晴表情", "笑"));
			System.out.println(blytrpg.validate("扬雨晴表情", "笑（尴尬）"));
			System.out.println(blytrpg.validate("天数", "20"));
			System.out.println(blytrpg.validate("天数", "未知"));
			System.out.println(blytrpg.validate("地点", "活动室"));
			System.out.println(blytrpg.validate("地点", "枫音乡"));
			save(basePath,"xinghantrpg",blytrpg);
		}{
			AttributeValidator fengyitalk=new AttributeValidator();
			fengyitalk.addRule("地点", "(街道|主角卧室|十字路口|姚枫茜卧室|姚枫怡卧室|姚枫怡家门|学校大门|学校食堂|学校庭院|学校图书馆|学校礼堂|文科教室|理科教室|学校社团室|学校楼道左|学校楼道右|沙滩|商业街|云游时空馆外|云游时空馆内|快餐厅|服装店|枫音山路|枫音山顶|海边道路|公园喷泉|公园湖畔|医院走廊|医院病房)");
			fengyitalk.addRule("时间", "(上午|下午|黄昏|夜晚)");
			fengyitalk.addRule("季节", "(春|夏|秋|冬)");
			fengyitalk.addRule("表情", "(严肃|哀伤|大笑|哭|脸红|阴暗|惊恐|震惊|生气|无神|微笑)");
			fengyitalk.addRule("服装", "(常服|校服)");
			fengyitalk.addRule("位置", "(前|侧|后|远程通话|不在身边)");
			fengyitalk.addRule("星期", "(一|二|三|四|五|六|日)");
			save(basePath,"fengyitalk",fengyitalk);
		}{
			AttributeValidator fengxitalk=new AttributeValidator();
			fengxitalk.addRule("地点", "(街道|主角卧室|十字路口|姚枫茜卧室|姚枫怡卧室|姚枫怡家门|学校大门|学校食堂|学校庭院|学校图书馆|学校礼堂|文科教室|理科教室|学校社团室|学校楼道左|学校楼道右|沙滩|商业街|云游时空馆外|云游时空馆内|快餐厅|服装店|枫音山路|枫音山顶|海边道路|公园喷泉|公园湖畔|医院走廊|医院病房)");
			fengxitalk.addRule("时间", "(上午|下午|黄昏|夜晚)");
			fengxitalk.addRule("季节", "(春|夏|秋|冬)");
			fengxitalk.addRule("表情", "(严肃|哀伤|大笑|哭|脸红|惊恐|生气|愤怒|微笑|得意)");
			fengxitalk.addRule("服装", "(常服|校服|睡衣|演出服)");
			fengxitalk.addRule("位置", "(前|侧|后|远程通话|不在身边)");
			fengxitalk.addRule("星期", "(一|二|三|四|五|六|日)");
			save(basePath,"fengxitalk",fengxitalk);
		}{
			AttributeValidator haiyintalk=new AttributeValidator();
			haiyintalk.addRule("地点", "(街道|主角卧室|十字路口|姚枫茜卧室|姚枫怡卧室|姚枫怡家门|唐海音家|学校大门|学校食堂|学校庭院|学校图书馆|学校礼堂|文科教室|理科教室|学校社团室|学校楼道左|学校楼道右|沙滩|商业街|云游时空馆外|云游时空馆内|快餐厅|服装店|枫音山路|枫音山顶|海边道路|公园喷泉|公园湖畔|医院走廊|医院病房)");
			haiyintalk.addRule("时间", "(上午|下午|黄昏|夜晚)");
			haiyintalk.addRule("季节", "(春|夏|秋|冬)");
			haiyintalk.addRule("表情", "(哀伤|冷漠|脸红|微笑|哭泣|大笑|惊恐|愤怒|戏谑|兴奋)");
			haiyintalk.addRule("服装", "(汉服|校服)");
			haiyintalk.addRule("位置", "(前|侧|后|远程通话|不在身边)");
			haiyintalk.addRule("星期", "(一|二|三|四|五|六|日)");
			save(basePath,"haiyintalk",haiyintalk);
		}
		
	}
	public static void save(File fn,String ai,AttributeValidator selector) throws IOException {
		File save=new File(fn,ai+"/validator.json");
		List<File> files=new ArrayList<>();
		System.out.println("saved as "+save);
		Gson gson = new GsonBuilder()
	        .registerTypeAdapter(Rule.class, new RuleSerializer())
	        .setPrettyPrinting()
	        .create();
		FileUtil.transfer(gson.toJson(selector), save);
		
	}
}
