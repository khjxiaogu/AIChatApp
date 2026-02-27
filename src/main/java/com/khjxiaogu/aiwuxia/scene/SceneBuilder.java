package com.khjxiaogu.aiwuxia.scene;

import java.util.ArrayList;
import java.util.List;

import com.khjxiaogu.aiwuxia.scene.SceneSelector.Predicate;

public abstract class SceneBuilder<T,O extends SceneBuilder<T,O>> {

	public static class PredicateBuilder<T,O extends PredicateBuilder<T,O>>{
		protected T parent;
		protected List<String> values;
		protected String key;
		public PredicateBuilder(T parent,String key) {
			super();
			this.parent=parent;
			this.values = new ArrayList<>();
			this.key = key;
		}
		public O withValue(String value){
			values.add(value);
			return (O) this;
		}
		public T end() {
			return parent;
		}
	}
	public static class SimplePredicateBuilder<T> extends PredicateBuilder<T,SimplePredicateBuilder<T>>{

		public SimplePredicateBuilder(T parent, String key) {
			super(parent, key);
			// TODO Auto-generated constructor stub
		}
		public SimplePredicateBuilder<T> withValue(String value){
			values.add(value);
			return this;
		}
	}
	public static class SimpleSceneBuilder<T> extends SceneBuilder<T,SimpleSceneBuilder<T>>{

		public SimpleSceneBuilder(T par) {
			super(par);
		}
		public SimpleSceneBuilder<SimpleSceneBuilder<T>> withAlt(){
			return withAlt(new SimpleSceneBuilder<>(this));

		}
		public static SimpleSceneBuilder<Object> builder() {
			return new SimpleSceneBuilder<>(new Object());
		}
	}
	protected T parent;
	protected SceneSelector scene=new SceneSelector();
	protected String pathPrefix="";
	public SceneBuilder(T par) {
		this.parent=par;
	}
	public O addPrefix(String path) {
		pathPrefix+=path+"/";
		return (O) this;
	}
	public O setPrefix(String path) {
		pathPrefix=path;
		return (O) this;
	}
	public T end() {
		return parent;
	}

	public O withPredicate(String name,String value) {
		scene.predicates.add(new Predicate(name,value));
		return (O) this;
	}
	public <N extends PredicateBuilder<O,N>> N withPredicate(N pb) {
		scene.predicates.add(new Predicate(pb.key,pb.values));
		return pb;
	}
	public SimplePredicateBuilder<O> withPredicate(String name) {
		SimplePredicateBuilder<O> pb=new SimplePredicateBuilder<>((O)this,name);
		scene.predicates.add(new Predicate(name,pb.values));
		return pb;
	}
	public O withPredicate(String name,String... value) {
		scene.predicates.add(new Predicate(name,value));
		return (O) this;
	}
	public O withPredicate(String name,List<String> value) {
		scene.predicates.add(new Predicate(name,value));
		return (O) this;
	}
	public O withScene(String fn){
		scene.scene=pathPrefix+fn;
		return (O) this;
	}
	public abstract SceneBuilder<O,?> withAlt();
	public <N extends SceneBuilder<O,N>> N withAlt(N pb) {
		scene.selectors.add(pb.scene);
		pb.setPrefix(pathPrefix);
		return (N)pb;
	}
	public O withAlt(String fn,String name,String value) {
		withAlt().withPredicate(name, value).setPrefix(pathPrefix).withScene(fn).end();
		return (O) this;
	}
	public O withAlt(String fn,String name,String... value) {
		withAlt().withPredicate(name, value).setPrefix(pathPrefix).withScene(fn).end();
		return (O) this;
	}
	public O withAlt(String fn,String name,List<String> value) {
		withAlt().withPredicate(name, value).setPrefix(pathPrefix).withScene(fn).end();
		return (O) this;
	}
	public SceneSelector build() {
		return scene;
	}
}
