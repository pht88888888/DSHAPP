package android.view
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.dsh.android.feature.bubble.R
// Narrow L0 View scheduler: real renderer/player logic runs against deterministic queues.
open class View {
 var id=0; var visibility=VISIBLE; var height=100; var width=300; var rotation=0f; var scaleX=1f; var scaleY=1f; var pivotY=0f; var background:Any?=null
 val children=mutableListOf<View>(); val tasks=mutableListOf<Pair<Runnable,Long>>()
 val childCount get()=children.size
 fun getChildAt(i:Int):View = children[i]
 @Suppress("UNCHECKED_CAST") fun <T:View> findViewById(key:Int):T = (children.firstOrNull {it.id==key} ?: this) as T
 fun post(task:Runnable):Boolean { tasks.add(task to 0L); return true }
 fun postDelayed(task:Runnable,delay:Long):Boolean { tasks.add(task to delay); return true }
 fun removeCallbacks(task:Runnable):Boolean = tasks.removeAll { it.first===task }
 fun runNext() { val task=tasks.removeAt(0); task.first.run() }
 companion object { const val VISIBLE=0; const val GONE=8; const val FOCUS_DOWN=130; const val ROTATION="rotation"; const val SCALE_X="scaleX"; const val SCALE_Y="scaleY" }
}
object Gravity { const val END=5; const val START=3; const val CENTER=17 }
class LayoutInflater {
 fun inflate(layout:Int, parent:View?, attach:Boolean):View = LinearLayout().apply { addView(TextView(Context()).apply {id=R.id.msgRole});addView(TextView(Context()).apply {id=R.id.msgBody}) }
 companion object { fun from(context:Context)=LayoutInflater() }
}
