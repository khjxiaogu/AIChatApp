package com.khjxiaogu.aiwuxia.scene;

public class AICharaSceneBuilder<T> extends SceneBuilder<T,AICharaSceneBuilder<T>> {
	public static class SeasonBuilder<T> extends PredicateBuilder<T,SeasonBuilder<T>>{

		public SeasonBuilder(T parent) {
			super(parent, "季节");
		}
		
		@Override
		public SeasonBuilder<T> withValue(String value) {
			super.withValue(value);
			return this;
		}
		public SeasonBuilder<T> green(){
			return spring().summer();
		}
		public SeasonBuilder<T> red(){
			return autumn();
		}
		public SeasonBuilder<T> white(){
			return winter();
		}
		public SeasonBuilder<T> spring(){
			return withValue("春");
		}
		public SeasonBuilder<T> summer(){
			return withValue("夏");
		}
		public SeasonBuilder<T> autumn(){
			return withValue("秋");
		}
		public SeasonBuilder<T> winter(){
			return withValue("冬");
		}
	}
	public static class TimeBuilder<T> extends PredicateBuilder<T,TimeBuilder<T>>{

		public TimeBuilder(T parent) {
			super(parent, "时间");
		}
		@Override
		public TimeBuilder<T> withValue(String value) {
			super.withValue(value);
			return this;
		}
		public TimeBuilder<T> morning(){
			return withValue("上午");
		}
		public TimeBuilder<T> noon(){
			return withValue("下午");
		}
		public TimeBuilder<T> sunset(){
			return withValue("黄昏");
		}
		public TimeBuilder<T> evening(){
			return withValue("夜晚");
		}
		public TimeBuilder<T> daytime(){
			return morning().noon();
		}
		public TimeBuilder<T> nighttime(){
			return sunset().evening();
		}
	}
	public AICharaSceneBuilder(T par) {
		super(par);
	}
	public SeasonBuilder<AICharaSceneBuilder<T>> season(){
		return (SeasonBuilder<AICharaSceneBuilder<T>>) super.withPredicate(new SeasonBuilder<AICharaSceneBuilder<T>>(this));
	}
	public TimeBuilder<AICharaSceneBuilder<T>> time(){
		return (TimeBuilder<AICharaSceneBuilder<T>>) super.withPredicate(new TimeBuilder<AICharaSceneBuilder<T>>(this));
	}
	public static AICharaSceneBuilder<Endable> builder(){
		return new AICharaSceneBuilder<>(new Endable());
	}
	public  AICharaSceneBuilder<AICharaSceneBuilder<T>> withAlt(){
		return withAlt(new AICharaSceneBuilder<>(this));
	}
	public  SimplePredicateBuilder<AICharaSceneBuilder<T>> location(){
		return super.withPredicate("地点");
	}
	public  AICharaSceneBuilder<T> withLocation(String name){
		return super.withPredicate("地点").withValue(name).end();
	}
	public  AICharaSceneBuilder<T> withName(String name){
		return super.withPredicate("姓名").withValue(name).end();
	}
	public SimplePredicateBuilder<AICharaSceneBuilder<T>> emote(){
		return super.withPredicate("表情");
	}
	public  AICharaSceneBuilder<T> withCloth(String name){
		return super.withPredicate("服装").withValue(name).end();
	}
	public SimplePredicateBuilder<AICharaSceneBuilder<T>> cloth(){
		return super.withPredicate("服装");
	}
	public SimplePredicateBuilder<AICharaSceneBuilder<T>> week(){
		return super.withPredicate("星期");
	}

}
