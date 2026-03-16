package com.khjxiaogu.aiwuxia.tools;

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
import com.khjxiaogu.aiwuxia.scene.SceneSelector;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class SceneGenerator {

	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub
		buildTLCM();
		buildCFS();
	}
	public static void buildCFS() throws IOException {
		//哀伤///严肃//笑/生气
		//
		File savePath=new File("save/");
		AICharaSceneBuilder<Endable> chara=AICharaSceneBuilder.builder().addPrefix("conspiracyfs/fgimage");
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> ghf=chara.withAlt().withName("归海枫").addPrefix("ghf");
		ghf.withAlt().emote().withValue("哀伤").end().withScene("ghfaishang3.png").end();
		ghf.withAlt().emote().withValue("震惊").end().withScene("ghfaishang1.png").end();
		ghf.withAlt().emote().withValue("哭").end().withScene("ghfaishang3.png").end();
		ghf.withAlt().emote().withValue("惊恐").end().withScene("ghfaishang1.png").end();
		ghf.withAlt().emote().withValue("严肃").end().withScene("ghfputong1.png").end();
		ghf.withAlt().emote().withValue("生气").end().withScene("ghfshengqi2.png").end();
		ghf.withAlt().emote().withValue("笑").end().withScene("ghfxiao1.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> hyz=chara.withAlt().withName("贺云舟").addPrefix("hyz");
		hyz.withAlt().emote().withValue("哀伤").end().withScene("hyzaishang2.png").end();
		hyz.withAlt().emote().withValue("震惊").end().withScene("hyzchijing3.png").end();
		hyz.withAlt().emote().withValue("哭").end().withScene("hyzku1.png").end();
		hyz.withAlt().emote().withValue("惊恐").end().withScene("hyzjingkong29.png").end();
		hyz.withAlt().emote().withValue("严肃").end().withScene("hyzputong2.png").end();
		hyz.withAlt().emote().withValue("生气").end().withScene("hyzshengqi3.png").end();
		hyz.withAlt().emote().withValue("笑").end().withScene("hyzxiao1.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> qly=chara.withAlt().withName("祁连遥").addPrefix("qly");
		qly.withAlt().emote().withValue("哀伤").end().withScene("qlyaishang1.png").end();
		qly.withAlt().emote().withValue("震惊").end().withScene("qlychijing8.png").end();
		qly.withAlt().emote().withValue("哭").end().withScene("qlyaishang1.png").end();
		qly.withAlt().emote().withValue("惊恐").end().withScene("qlychijing8.png").end();
		qly.withAlt().emote().withValue("严肃").end().withScene("qlyputong2.png").end();
		qly.withAlt().emote().withValue("生气").end().withScene("qlykuazhang1.png").end();
		qly.withAlt().emote().withValue("笑").end().withScene("qlyxiao1.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> rxy=chara.withAlt().withName("苒雪忆").addPrefix("rxy");
		rxy.withAlt().emote().withValue("哀伤").end().withScene("rxyaishang1.png").end();
		rxy.withAlt().emote().withValue("震惊").end().withScene("rxykongju22.png").end();
		rxy.withAlt().emote().withValue("哭").end().withScene("rxyku2.png").end();
		rxy.withAlt().emote().withValue("惊恐").end().withScene("rxykongju22.png").end();
		rxy.withAlt().emote().withValue("严肃").end().withScene("rxyputong2.png").end();
		rxy.withAlt().emote().withValue("生气").end().withScene("rxyshengqi15.png").end();
		rxy.withAlt().emote().withValue("笑").end().withScene("rxyxiao1.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> tl=chara.withAlt().withName("童玲").addPrefix("tl");
		tl.withAlt().emote().withValue("哀伤").end().withScene("tlaishang1.png").end();
		tl.withAlt().emote().withValue("震惊").end().withScene("tlputong5.png").end();
		tl.withAlt().emote().withValue("哭").end().withScene("tlaishang1.png").end();
		tl.withAlt().emote().withValue("惊恐").end().withScene("tlputong5.png").end();
		tl.withAlt().emote().withValue("严肃").end().withScene("tlputong1.png").end();
		tl.withAlt().emote().withValue("生气").end().withScene("tlshengqi1.png").end();
		tl.withAlt().emote().withValue("笑").end().withScene("tlxiao3.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> wxh=chara.withAlt().withName("汪星涵").addPrefix("wxh");
		wxh.withAlt().emote().withValue("哀伤").end().withScene("wxhaishang1.png").end();
		wxh.withAlt().emote().withValue("震惊").end().withScene("wxhkuazhang3.png").end();
		wxh.withAlt().emote().withValue("哭").end().withScene("wxhku2.png").end();
		wxh.withAlt().emote().withValue("惊恐").end().withScene("wxhkongju12.png").end();
		wxh.withAlt().emote().withValue("严肃").end().withScene("wxhputong5.png").end();
		wxh.withAlt().emote().withValue("生气").end().withScene("wxhshengqi2.png").end();
		wxh.withAlt().emote().withValue("笑").end().withScene("wxhxiao2.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> yyq=chara.withAlt().withName("扬雨晴").addPrefix("yyq");
		yyq.withAlt().emote().withValue("哀伤").end().withScene("yyqaishang2.png").end();
		yyq.withAlt().emote().withValue("震惊").end().withScene("yyqchijing5.png").end();
		yyq.withAlt().emote().withValue("哭").end().withScene("yyqku6.png").end();
		yyq.withAlt().emote().withValue("惊恐").end().withScene("yyqkongju3.png").end();
		yyq.withAlt().emote().withValue("严肃").end().withScene("yyqputong2.png").end();
		yyq.withAlt().emote().withValue("生气").end().withScene("yyqkuazhang1.png").end();
		yyq.withAlt().emote().withValue("笑").end().withScene("yyqxiao1.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> zfj=chara.withAlt().withName("周方谨").addPrefix("zfj");
		zfj.withAlt().emote().withValue("哀伤").end().withScene("zfjaishang4.png").end();
		zfj.withAlt().emote().withValue("震惊").end().withScene("zfjkongju2.png").end();
		zfj.withAlt().emote().withValue("哭").end().withScene("zfjaishang4.png").end();
		zfj.withAlt().emote().withValue("惊恐").end().withScene("zfjkongju2.png").end();
		zfj.withAlt().emote().withValue("严肃").end().withScene("zfjputong1.png").end();
		zfj.withAlt().emote().withValue("生气").end().withScene("zfjshengqi4.png").end();
		zfj.withAlt().emote().withValue("笑").end().withScene("zfjxiao1.png").end();
		
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> zsy=chara.withAlt().withName("赵书宇").addPrefix("zsy");
		zsy.withAlt().emote().withValue("哀伤").end().withScene("zsyaishang2.png").end();
		zsy.withAlt().emote().withValue("震惊").end().withScene("zsychijing2.png").end();
		zsy.withAlt().emote().withValue("哭").end().withScene("zsyaishang2.png").end();
		zsy.withAlt().emote().withValue("惊恐").end().withScene("zsykongju1.png").end();
		zsy.withAlt().emote().withValue("严肃").end().withScene("zsyputong2.png").end();
		zsy.withAlt().emote().withValue("生气").end().withScene("zsyteshu4.png").end();
		zsy.withAlt().emote().withValue("笑").end().withScene("zsyxiao1.png").end();
		
		
		checkSave(savePath,"xinghantrpg","chara",chara.build());
		
		AICharaSceneBuilder<Endable> back=AICharaSceneBuilder.builder().addPrefix("conspiracyfs/bgimage");
		AICharaSceneBuilder<AICharaSceneBuilder<Endable>> cur;
		cur=back.withAlt().addPrefix("操场").location().withValue("操场").end();
		cur.withScene("caochangri.jpg");
		cur.withAlt().time().sunset().end().withScene("caochanghun.jpg").end();
		cur.withAlt().time().evening().end().withScene("caochangye.jpg").end();
		cur.end();
		cur=back.withAlt().addPrefix("活动室").location().withValue("活动室").end();
		cur.withScene("huodongshiri.jpg");
		cur.withAlt().time().sunset().end().withScene("huodongshihun.jpg").end();
		cur.withAlt().time().evening().end().withScene("huodongshiye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("教室").location().withValue("教室").end();
		cur.withScene("jiaoshiri.jpg");
		cur.withAlt().time().sunset().end().withScene("jiaoshihun.jpg").end();
		cur.withAlt().time().evening().end().withScene("jiaoshiye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("校门内").location().withValue("校门内").end();
		cur.withScene("menkouri.jpg");
		cur.withAlt().time().sunset().end().withScene("menkouhun.jpg").end();
		cur.withAlt().time().evening().end().withScene("menkouye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("秘密地点").location().withValue("秘密地点").end();
		cur.withScene("mimididianhun.jpg");
		cur.withAlt().time().sunset().end().withScene("mimididianhun.jpg").end();
		cur.withAlt().time().evening().end().withScene("mimididianye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("食堂").location().withValue("食堂").end();
		cur.withScene("shitangri.jpg");
		cur.withAlt().time().sunset().end().withScene("shitanghun.jpg").end();
		cur.withAlt().time().evening().end().withScene("shitangye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("外界").location().withValue("外界").end();
		cur.withScene("shijieri.jpg");
		cur.withAlt().time().sunset().end().withScene("shijieye1.jpg").end();
		cur.withAlt().time().evening().end().withScene("shijieye1.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("庭院").location().withValue("庭院").end();
		cur.withScene("shuichiri1.jpg");
		cur.withAlt().time().sunset().end().withScene("shuichiye1.jpg").end();
		cur.withAlt().time().evening().end().withScene("shuichiye1.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("校门外").location().withValue("校门外").end();
		cur.withScene("xiaomenri1.jpg");
		cur.withAlt().time().sunset().end().withScene("xiaomenye.jpg").end();
		cur.withAlt().time().evening().end().withScene("xiaomenye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("小屋").location().withValue("小屋").end();
		cur.withScene("xiaowu.jpg");
		cur.withAlt().time().sunset().end().withScene("xiaowu.jpg").end();
		cur.withAlt().time().evening().end().withScene("xiaowu.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("悬崖").location().withValue("悬崖").end();
		cur.withScene("xuanyari.jpg");
		cur.withAlt().time().sunset().end().withScene("xuanyaye.jpg").end();
		cur.withAlt().time().evening().end().withScene("xuanyaye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("树林").location().withValue("树林").end();
		cur.withScene("yewairi.jpg");
		cur.withAlt().time().sunset().end().withScene("yewaiye1.jpg").end();
		cur.withAlt().time().evening().end().withScene("yewaiye1.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("中庭").location().withValue("中庭").end();
		cur.withScene("zhongtingri.jpg");
		cur.withAlt().time().sunset().end().withScene("zhongtinghun.jpg").end();
		cur.withAlt().time().evening().end().withScene("zhongtingye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("走廊").location().withValue("走廊").end();
		cur.withScene("zoulangerlouri.jpg");
		cur.withAlt().time().sunset().end().withScene("zoulangerlouhun.jpg").end();
		cur.withAlt().time().evening().end().withScene("zoulangerlouye.jpg").end();
		cur.end();

		cur=back.withAlt().addPrefix("宿舍").location().withValue("宿舍").end();
		cur.withScene("susheri.jpg");
		cur.withAlt().time().sunset().end().withScene("susheye.jpg").end();
		cur.withAlt().time().evening().end().withScene("susheye.jpg").end();
		cur.end();
		
		checkSave(savePath,"xinghantrpg","back",back.build());
	}
	public static void buildTLCM() throws IOException {
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
		checkSave(savePath,"fengxitalk","chara",chara.build());
		
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
		checkSave(savePath,"fengyitalk","chara",chara.build());
		
		//haiyin //////惊恐//
		chara=AICharaSceneBuilder.builder().addPrefix("truelovecm/fgimage/thy");
		school=chara.withAlt().cloth().withValue("校服").end();
		school.withAlt().emote().withValue("哀伤").end().withScene("thy1aishang2.png").end();
		school.withAlt().emote().withValue("脸红").end().withScene("thy1haixiu3bu.png").end();
		school.withAlt().emote().withValue("惊恐").end().withScene("thy1jingkong12.png").end();
		school.withAlt().emote().withValue("哭泣").end().withScene("thy1ku3.png").end();
		school.withAlt().emote().withValue("兴奋").end().withScene("thy1kuazhang1.png").end();
		school.withAlt().emote().withValue("冷漠").end().withScene("thy1putong1.png").end();
		school.withAlt().emote().withValue("愤怒").end().withScene("thy1shengqi5.png").end();
		school.withAlt().emote().withValue("微笑").end().withScene("thy1xiao1.png").end();
		school.withAlt().emote().withValue("大笑").end().withScene("thy1xiao4.png").end();
		school.withAlt().emote().withValue("戏谑").end().withScene("thy1xiao27.png").end();
		school.end();
		
		norm=chara.withAlt().cloth().withValue("汉服").end();
		school.withAlt().emote().withValue("哀伤").end().withScene("thy3aishang2.png").end();
		school.withAlt().emote().withValue("脸红").end().withScene("thy3haixiu3bu.png").end();
		school.withAlt().emote().withValue("惊恐").end().withScene("thy3jingkong16.png").end();
		school.withAlt().emote().withValue("哭泣").end().withScene("thy3ku2.png").end();
		school.withAlt().emote().withValue("兴奋").end().withScene("thy3kuazhang4.png").end();
		school.withAlt().emote().withValue("冷漠").end().withScene("thy3aishang1.png").end();
		school.withAlt().emote().withValue("愤怒").end().withScene("thy3shengqi4.png").end();
		school.withAlt().emote().withValue("微笑").end().withScene("thy3xiao1.png").end();
		school.withAlt().emote().withValue("大笑").end().withScene("thy3xiao4.png").end();
		school.withAlt().emote().withValue("戏谑").end().withScene("thy3xiao27.png").end();
		norm.end();
		checkSave(savePath,"haiyintalk","chara",chara.build());
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
		
		back.withAlt().addPrefix("海音家").location().withValue("唐海音家").end()
		.withScene("haiyinjiari2.jpg")
			.withAlt().season().winter().end()
				.withScene("haiyinjiari3.jpg")
				.withAlt().time().evening().end().withScene("haiyinjiaye3.jpg").end().end()
			.withAlt().time().evening().end().withScene("haiyinjiaye2.jpg").end().end().end()
		.end();
		Map<String,String> vals=new HashMap<>();
		vals.put("地点", "公园湖畔");
		vals.put("季节", "春");
		vals.put("时间", "下午");
		System.out.println(back.build().getSceneData(vals));
		checkSave(savePath,"fengyitalk","back",back.build());
		checkSave(savePath,"fengxitalk","back",back.build());
		checkSave(savePath,"haiyintalk","back",back.build());
	}
	public static void checkSave(File fn,String ai,String type,SceneSelector selector) throws IOException {
		File save=new File(fn,ai+"/"+type+".json");
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
