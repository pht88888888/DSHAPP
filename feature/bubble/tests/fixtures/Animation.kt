package android.animation
import android.view.View
open class Animator { val listeners=mutableListOf<AnimatorListenerAdapter>(); var running=false; open fun cancel(){ running=false;listeners.forEach{it.onAnimationEnd(this)} };fun addListener(listener:AnimatorListenerAdapter){listeners.add(listener)} }
open class AnimatorListenerAdapter {open fun onAnimationEnd(animation:Animator){} }
open class ValueAnimator:Animator() {
 var duration=0L;var repeatCount=0;var repeatMode=0;var interpolator:Any?=null;var animatedFraction=0f
 val updates=mutableListOf<(ValueAnimator)->Unit>();fun addUpdateListener(f:(ValueAnimator)->Unit){updates.add(f)}
 fun start(){running=true};fun tick(f:Float){animatedFraction=f;updates.forEach{it(this)}}
 fun finish(){running=false;listeners.forEach{it.onAnimationEnd(this)}}
 companion object {val all=mutableListOf<ValueAnimator>();fun ofFloat(vararg values:Float)=ValueAnimator().also{all.add(it)}}
}
class ObjectAnimator:ValueAnimator() {
 var target:View?=null;var property="";var values=floatArrayOf()
 companion object {const val INFINITE=-1;const val RESTART=1;const val REVERSE=2;fun ofFloat(target:View,property:String,vararg values:Float)=ObjectAnimator().also{it.target=target;it.property=property;it.values=values;ValueAnimator.all.add(it)}}
}
