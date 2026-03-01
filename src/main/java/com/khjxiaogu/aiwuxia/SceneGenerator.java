package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.GsonBuilder;
import com.khjxiaogu.aiwuxia.scene.AICharaSceneBuilder;
import com.khjxiaogu.aiwuxia.scene.Endable;
import com.khjxiaogu.aiwuxia.scene.SceneBuilder.SimplePredicateBuilder;
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class SceneGenerator {

	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub
		/*
		地点=主角卧室
		时间=上午（上午/下午/黄昏/夜晚）
		季节=春（春/夏/秋/冬）
		表情=微笑（严肃/哀伤/大笑/哭/脸红/惊恐/生气/愤怒/微笑/得意）
		服装=校服（常服/校服/睡衣/演出服）
		位置=前（前/侧/后/远程通话/不在身边）
		星期=一（一/二/三/四/五/六/日）
		*/
		
		//fengxi
		File savePath=new File("save/");
		AICharaSceneBuilder<Endable> chara=AICharaSceneBuilder.builder().addPrefix("truelovecm/fgimage/yfx");
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> school=chara.withAlt().cloth().withValue("校服").end();
		school.withAlt().emote().withValue("严肃").end().withScene("yfx1putong5.png").end();
		school.withAlt().emote().withValue("哀伤").end().withScene("yfx1aishang2.png").end();
		school.withAlt().emote().withValue("大笑").end().withScene("yfx2xiao12.png").end();
		school.withAlt().emote().withValue("哭" ).end().withScene("yfx2ku6.png").end();
		school.withAlt().emote().withValue("脸红").end().withScene("yfx1haixiu6.png").end();
		school.withAlt().emote().withValue("惊恐").end().withScene("yfx1jingkong8.png").end();
		school.withAlt().emote().withValue("生气").end().withScene("yfx1shengqi5.png").end();
		school.withAlt().emote().withValue("愤怒").end().withScene("yfx1shengqi4.png").end();
		school.withAlt().emote().withValue("微笑").end().withScene("yfx1xiao1.png").end();
		school.withAlt().emote().withValue("得意").end().withScene("yfx1xiao28.png").end();
		school.end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> norm=chara.withAlt().cloth().withValue("常服").end().season().green().end();
		norm.withAlt().emote().withValue("严肃").end().withScene("yfx3putong5.png").end();
		norm.withAlt().emote().withValue("哀伤").end().withScene("yfx3aishang2.png").end();
		norm.withAlt().emote().withValue("大笑").end().withScene("yfx4xiao12.png").end();
		norm.withAlt().emote().withValue("哭" ).end().withScene("yfx4ku17.png").end();
		norm.withAlt().emote().withValue("脸红").end().withScene("yfx4haixiu3.png").end();
		norm.withAlt().emote().withValue("惊恐").end().withScene("yfx4jingkong8.png").end();
		norm.withAlt().emote().withValue("生气").end().withScene("yfx3shengqi5.png").end();
		norm.withAlt().emote().withValue("愤怒").end().withScene("yfx3shengqi4.png").end();
		norm.withAlt().emote().withValue("微笑").end().withScene("yfx4xiao1.png").end();
		norm.withAlt().emote().withValue("得意").end().withScene("yfx4xiao26.png").end();
		norm.end();
		
		norm=chara.withAlt().cloth().withValue("常服").end().season().autumn().winter().end();
		norm.withAlt().emote().withValue("严肃").end().withScene("yfx10shengqi1.png").end();
		norm.withAlt().emote().withValue("哀伤").end().withScene("yfx9aishang2.png").end();
		norm.withAlt().emote().withValue("大笑").end().withScene("yfx10xiao12.png").end();
		norm.withAlt().emote().withValue("哭" ).end().withScene("yfx10ku2.png").end();
		norm.withAlt().emote().withValue("脸红").end().withScene("yfx10haixiu9.png").end();
		norm.withAlt().emote().withValue("惊恐").end().withScene("yfx10jingkong7.png").end();
		norm.withAlt().emote().withValue("生气").end().withScene("yfx9shengqi1.png").end();
		norm.withAlt().emote().withValue("愤怒").end().withScene("yfx9shengqi3.png").end();
		norm.withAlt().emote().withValue("微笑").end().withScene("yfx10xiao13.png").end();
		norm.withAlt().emote().withValue("得意").end().withScene("yfx10xiao26.png").end();
		norm.end();
		
		norm=chara.withAlt().cloth().withValue("睡衣").end();
		norm.withAlt().emote().withValue("严肃").end().withScene("yfx7putong2.png").end();
		norm.withAlt().emote().withValue("哀伤").end().withScene("yfx7aishang2.png").end();
		norm.withAlt().emote().withValue("大笑").end().withScene("yfx7xiao11.png").end();
		norm.withAlt().emote().withValue("哭" ).end().withScene("yfx7ku16.png").end();
		norm.withAlt().emote().withValue("脸红").end().withScene("yfx7haixiu3.png").end();
		norm.withAlt().emote().withValue("惊恐").end().withScene("yfx7jingkong7.png").end();
		norm.withAlt().emote().withValue("生气").end().withScene("yfx7shengqi4.png").end();
		norm.withAlt().emote().withValue("愤怒").end().withScene("yfx7shengqi2.png").end();
		norm.withAlt().emote().withValue("微笑").end().withScene("yfx7xiao1.png").end();
		norm.withAlt().emote().withValue("得意").end().withScene("yfx7xiao26.png").end();
		norm.end();
		
		norm=chara.withAlt().cloth().withValue("演出服").end();
		norm.withAlt().emote().withValue("严肃").end().withScene("yfx8putong1.png").end();
		norm.withAlt().emote().withValue("哀伤").end().withScene("yfx8aishang2.png").end();
		norm.withAlt().emote().withValue("大笑").end().withScene("yfx8xiao36.png").end();
		norm.withAlt().emote().withValue("哭" ).end().withScene("yfx8ku2.png").end();
		norm.withAlt().emote().withValue("脸红").end().withScene("yfx8haixiu3.png").end();
		norm.withAlt().emote().withValue("惊恐").end().withScene("yfx8jingkong8.png").end();
		norm.withAlt().emote().withValue("生气").end().withScene("yfx8shengqi1.png").end();
		norm.withAlt().emote().withValue("愤怒").end().withScene("yfx8shengqi3.png").end();
		norm.withAlt().emote().withValue("微笑").end().withScene("yfx8xiao7.png").end();
		norm.withAlt().emote().withValue("得意").end().withScene("yfx8xiao26.png").end();
		norm.end();
		checkSave(savePath,"fengxi","chara",chara.build());
		
		//fengyi
		chara=AICharaSceneBuilder.builder().addPrefix("truelovecm/fgimage/yfy");
		school=chara.withAlt().cloth().withValue("校服").end();
		school.withAlt().emote().withValue("严肃").end().withScene("yfy1putong4.png").end();
		school.withAlt().emote().withValue("哀伤").end().withScene("yfy1aishang2.png").end();
		school.withAlt().emote().withValue("大笑").end().withScene("yfy1xiao2.png").end();
		school.withAlt().emote().withValue("哭" ).end().withScene("yfy1ku15.png").end();
		school.withAlt().emote().withValue("脸红").end().withScene("yfy1haixiu5.png").end();
		school.withAlt().emote().withValue("阴暗").end().withScene("yfy1heihua2.png").end();
		school.withAlt().emote().withValue("惊恐").end().withScene("yfy1jingkong7.png").end();
		school.withAlt().emote().withValue("震惊").end().withScene("yfy1kuazhang5.png").end();
		school.withAlt().emote().withValue("生气").end().withScene("yfy1shengqi6.png").end();
		school.withAlt().emote().withValue("无神").end().withScene("yfy1wushen7a.png").end();
		school.withAlt().emote().withValue("微笑").end().withScene("yfy1xiao1.png").end();
		school.end();
		
		norm=chara.withAlt().cloth().withValue("常服").end();
		norm.withAlt().emote().withValue("严肃").end().withScene("yfy3putong1.png").end();
		norm.withAlt().emote().withValue("哀伤").end().withScene("yfy3aishang2.png").end();
		norm.withAlt().emote().withValue("大笑").end().withScene("yfy3xiao2.png").end();
		norm.withAlt().emote().withValue("哭" ).end().withScene("yfy3ku7.png").end();
		norm.withAlt().emote().withValue("脸红").end().withScene("yfy3haixiu5.png").end();
		norm.withAlt().emote().withValue("阴暗").end().withScene("yfy3shengqi1.png").end();
		norm.withAlt().emote().withValue("惊恐").end().withScene("yfy3jingkong7.png").end();
		norm.withAlt().emote().withValue("震惊").end().withScene("yfy3kuazhang3.png").end();
		norm.withAlt().emote().withValue("生气").end().withScene("yfy3shengqi3.png").end();
		norm.withAlt().emote().withValue("无神").end().withScene("yfy4putong5.png").end();
		norm.withAlt().emote().withValue("微笑").end().withScene("yfy3xiao1.png").end();
		norm.end();
		checkSave(savePath,"fengyi","chara",chara.build());
		
		AICharaSceneBuilder<Endable> back=AICharaSceneBuilder.builder().addPrefix("truelovecm/bgimage");

		back.withAlt().addPrefix("街道").location().withValue("街道").end()
		.withAlt().season().green().end().withScene("jiedaori.jpg")
			.withAlt().time().sunset().end().withScene("jiedaohun.jpg").end()
			.withAlt().time().evening().end().withScene("jiedaoye.jpg").end().end()
		.withAlt().season().red().end().withScene("jiedaoqiuri.jpg")
			.withAlt().time().sunset().end().withScene("jiedaoqiuri.jpg").end()//notimpl
			.withAlt().time().evening().end().withScene("jiedaoqiuye.jpg").end().end()
		.withAlt().season().white().end().withScene("jiedaoqiuri.jpg")//notimpl
			.withAlt().time().sunset().end().withScene("jiedaoqiuri.jpg").end()//notimpl
			.withAlt().time().evening().end().withScene("jiedaoye2.jpg").end().end().end().end()
		.end();

		back.withAlt().addPrefix("主角卧室").location().withValue("主角卧室").end()
		.withScene("nanzhuwoshiri.jpg")
			.withAlt().time().sunset().end().withScene("nanzhuwoshihun.jpg").end()
			.withAlt().time().evening().end().withScene("nanzhuwoshiye.jpg").end().end().end()
		.end();
;

		back.withAlt().addPrefix("十字路口").location().withValue("十字路口").end()
		.withScene("lukouri.jpg")
			.withAlt().time().evening().end().withScene("lukouye.jpg")
				.withAlt().season().white().end().withScene("lukouye7.jpg").end().end().end().end()
		.end();

		back.withAlt().addPrefix("姚枫茜卧室").location().withValue("姚枫茜卧室").end()
		.withScene("fengxiwoshiri.jpg")
			.withAlt().time().sunset().end().withScene("fengxiwoshihun.jpg").end()
			.withAlt().time().evening().end().withScene("fengxiwoshiye.jpg").end().end().end()
		.end();
;

		back.withAlt().location().withValue("姚枫怡卧室").end()
		.withScene("姚枫茜卧室/fengxiwoshiri.jpg")//notimpl
			.withAlt().time().sunset().end().withScene("姚枫茜卧室/fengxiwoshihun.jpg").end()//notimpl
			.withAlt().time().evening().end().withScene("姚枫怡卧室/fengyiwoshiye.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("姚枫怡家门").location().withValue("姚枫怡家门").end()
		.withScene("fengxijiawairi.jpg")
			.withAlt().time().daytime().end().season().white().end().withScene("fengxijiawairixue.jpg").end()
			.withAlt().time().sunset().end().withScene("fengxijiawaihun.jpg").end()
			.withAlt().time().evening().end().withScene("fengxijiawaiye1.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("学校大门").location().withValue("学校大门").end()
		.withAlt().season().green().end().withScene("xiaomenkouri1.jpg")
			.withAlt().time().sunset().end().withScene("xiaomenkouhun1.jpg").end()
			.withAlt().time().evening().end().withScene("xuexiaomenkouye1.jpg").end().end()
		.withAlt().season().red().end().withScene("xiaomenkouqiuri1.jpg")
			.withAlt().time().sunset().end().withScene("xiaomenkouqiuhun.jpg").end()
			.withAlt().time().evening().end().withScene("xiaomenkouqiuye.jpg").end().end()
		.withAlt().season().white().end().withScene("xiaomenkouri6.jpg")
			.withAlt().time().sunset().end().withScene("xiaomenkouqiuhun.jpg").end()
			.withAlt().time().evening().end().withScene("xiaomenkouqiuye.jpg").end().end().end().end()
		.end();

		back.withAlt().addPrefix("学校食堂").location().withValue("学校食堂").end()
		.withScene("shitangri.jpg")
			.withAlt().time().sunset().end().withScene("shitanghun.jpg").end()
			.withAlt().time().evening().end().withScene("shitanghun.jpg").end().end().end()//notimpl
		.end();

		back.withAlt().addPrefix("学校庭院").location().withValue("学校庭院").end()
		.withAlt().season().green().end().withScene("tingyuanri.jpg")
			.withAlt().time().sunset().end().withScene("tingyuanhun.jpg").end()
			.withAlt().time().evening().end().withScene("tingyuanye.jpg").end().end()
		.withAlt().season().red().end().withScene("tingyuanqiuri.jpg")
			.withAlt().time().sunset().end().withScene("tingyuanqiuhun.jpg").end()
			.withAlt().time().evening().end().withScene("tingyuanye.jpg").end().end()//notimpl
		.withAlt().season().white().end().withScene("tingyuanqiuri.jpg")//notimpl
			.withAlt().time().sunset().end().withScene("tingyuanqiuhun.jpg").end()//notimpl
			.withAlt().time().evening().end().withScene("tingyuanye.jpg").end().end().end().end()//notimpl
		.end();

		back.withAlt().addPrefix("学校图书馆").location().withValue("学校图书馆").end()
		.withScene("tushuguanri.jpg")
			.withAlt().time().sunset().end().withScene("tushuguanri.jpg").end()//notimpl
			.withAlt().time().evening().end().withScene("tushuguanri.jpg").end().end().end()//notimpl
		.end();

		back.withAlt().addPrefix("学校礼堂").location().withValue("学校礼堂").end()
		.withScene("litang1.jpg").end().end()
		.end();

		back.withAlt().addPrefix("文科教室").location().withValue("文科教室").end()
		.withScene("jiaoshiri5.jpg")
			.withAlt().time().sunset().end().withScene("jiaoshihun.jpg").end()
			.withAlt().time().evening().end().withScene("jiaoshiye.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("理科教室").location().withValue("理科教室").end()
		.withScene("jiaoshiri2.jpg")
			.withAlt().time().sunset().end().withScene("jiaoshihun1.jpg").end()
			.withAlt().time().evening().end().withScene("jiaoshiye2.jpg").end().end().end()
		.end();

		
		back.withAlt().addPrefix("学校社团室").location().withValue("学校社团室").end()
		.withScene("huodongshiri1.jpg")
			.withAlt().time().sunset().end().withScene("huodongshihun1.jpg").end()
			.withAlt().time().evening().end().withScene("huodongshihun1.jpg").end().end().end()//notimpl
		.end();

		back.withAlt().addPrefix("学校楼道左").location().withValue("学校楼道左").end()
		.withScene("zoulangri4.jpg")
			.withAlt().time().sunset().end().withScene("zoulanghun2.jpg").end()
			.withAlt().time().evening().end().withScene("zoulangye4.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("学校楼道右").location().withValue("学校楼道右").end()
		.withScene("zoulangri3.jpg")
			.withAlt().time().sunset().end().withScene("zoulanghun3.jpg").end()
			.withAlt().time().evening().end().withScene("zoulangye3.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("沙滩").location().withValue("沙滩").end()
		.withScene("haitanri.jpg")
			.withAlt().time().sunset().end().withScene("haitanri.jpg").end()//notimpl
			.withAlt().time().evening().end().withScene("haitanye1.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("商业街").location().withValue("商业街").end()
		.withScene("shangyejieri.jpg")
			.withAlt().time().evening().end().withScene("shangyejieye1.jpg")
				.withAlt().season().white().end().withScene("shangyejieye2.jpg").end().end().end().end()
		.end();

		back.withAlt().addPrefix("云游时空馆外").location().withValue("云游时空馆外").end()
		.withScene("shikongguanwairi.jpg")
			.withAlt().time().sunset().end().withScene("shikongguanwaihun1.jpg").end()
			.withAlt().time().evening().end().withScene("shikongguanwaiye.jpg")
				.withAlt().season().white().end().withScene("shikongguanwaiye1.jpg").end().end().end().end()
		.end();

		back.withAlt().addPrefix("云游时空馆内").location().withValue("云游时空馆内").end()
		.withScene("shikongguanneiri.jpg")
			.withAlt().time().evening().end().withScene("shikongguanneiye.jpg").end().end().end()
		.end();


		back.withAlt().addPrefix("快餐厅").location().withValue("快餐厅").end()
		.withScene("kuaicandianri.jpg")
			.withAlt().time().evening().end().withScene("kuaicandianri.jpg").end().end().end()//notimpl
		.end();

		back.withAlt().addPrefix("服装店").location().withValue("服装店").end()
		.withScene("fuzhuangdian.jpg")
			.withAlt().time().nighttime().end().withScene("fuzhuangdian1.jpg")
				.withAlt().season().white().end().withScene("fuzhuangdian2.jpg").end().end().end().end()
		.end();

		back.withAlt().addPrefix("枫音山路").location().withValue("枫音山路").end()
		.withScene("fenglinri.jpg")
		.withAlt().season().red().end().withScene("fenglinqiuri.jpg")
			.withAlt().time().nighttime().end().withScene("fenglinqiuhun.jpg").end().end()
		.withAlt().time().sunset().end().withScene("fenglinhun.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("枫音山顶").location().withValue("枫音山顶").end()
		.withAlt().season().green().white().end()
			.withAlt().time().daytime().end().withScene("dashijieri.jpg").end()
			.withAlt().time().sunset().end().withScene("dashijiehun.jpg").end().end()
		.withAlt().season().red().end()
			.withAlt().time().daytime().end().withScene("dashijieqiuri.jpg").end()
			.withAlt().time().sunset().end().withScene("dashijieqiuhun.jpg").end().end()
		.withAlt().time().evening().end().withScene("dashijieye.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("海边道路").location().withValue("海边道路").end()
		.withScene("haibianluri.jpg")
			.withAlt().time().sunset().end().withScene("haibianluhun.jpg").end()
			.withAlt().time().evening().end().withScene("haibianluye.jpg")
				.withAlt().season().white().end().withScene("haibianluye4.jpg").end().end().end().end()
		.end();


		back.withAlt().addPrefix("公园喷泉").location().withValue("公园喷泉").end()
		.withScene("gongyuanri.jpg")
			.withAlt().time().evening().end().withScene("gongyuanye.jpg")
				.withAlt().season().winter().end().withScene("gongyuanye2.jpg").end().end()
			.withAlt().time().daytime().sunset().end().season().autumn().end().withScene("gongyuanqiuri1.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("公园湖畔").location().withValue("公园湖畔").end()
		.withScene("gongyuan2ri.jpg")
			.withAlt().season().autumn().winter().end().withScene("gongyuan2qiuri.jpg")
				.withAlt().time().nighttime().end().withScene("gongyuan2qiuhun.jpg").end().end()
			.withAlt().time().nighttime().end().withScene("gongyuan2hun.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("医院走廊").location().withValue("医院走廊").end()
		.withScene("yiyuanzoulangri.jpg")
			.withAlt().time().nighttime().end().withScene("yiyuanzoulangye.jpg").end().end().end()
		.end();

		back.withAlt().addPrefix("医院病房").location().withValue("医院病房").end()
		.withScene("bingfangri1.jpg")
			.withAlt().time().sunset().end().withScene("bingfanghun1.jpg").end()
			.withAlt().time().evening().end().withScene("bingfangye1.jpg").end().end().end()
		.end();
		Map<String,String> vals=new HashMap<>();
		vals.put("地点", "公园湖畔");
		vals.put("季节", "春");
		vals.put("时间", "下午");
		System.out.println(back.build().getSceneData(vals));
		checkSave(savePath,"fengyi","back",back.build());
	}
	public static void checkSave(File fn,String ai,String type,SceneSelector selector) throws IOException {
		File save=new File(fn,ai+"talk/"+type+".json");
		List<File> files=new ArrayList<>();
		visit(selector,files,new File(fn,"resource"));
		AtomicBoolean err=new AtomicBoolean();
		files.forEach(t->{
			if(!t.exists()) {
				System.out.println("File Not Found:"+t);
				err.set(true);
			}
		});
		if(!err.get()) {
			System.out.println("saved as "+save);
			FileUtil.transfer(new GsonBuilder().setPrettyPrinting().create().toJson(selector), save);
		}
	}
	public static void visit(SceneSelector selector,List<File> visited,File basePath) {
		if(selector.scene!=null) {
			visited.add(new File(basePath,selector.scene));
		}
		for(SceneSelector sel:selector.selectors) {
			visit(sel,visited,basePath);
		}
	}
}
