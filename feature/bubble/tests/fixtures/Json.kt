package org.json
// JSON parsing belongs to contracts tests; fail if an L0 bubble test accidentally uses it.
class JSONObject() {
 constructor(raw:String):this(){error("JSON parsing is outside this L0 fixture")}
 fun optJSONArray(key:String):JSONArray?=error("JSON is outside this L0 fixture")
 fun optString(key:String,fallback:String):String=error("JSON is outside this L0 fixture")
 fun optBoolean(key:String,fallback:Boolean):Boolean=error("JSON is outside this L0 fixture")
 fun put(key:String,value:Any?):JSONObject=error("JSON is outside this L0 fixture")
}
class JSONArray {
 fun length():Int=error("JSON is outside this L0 fixture")
 fun optJSONObject(index:Int):JSONObject?=error("JSON is outside this L0 fixture")
 fun put(value:Any?):JSONArray=error("JSON is outside this L0 fixture")
}
