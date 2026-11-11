package android.widget
import android.view.View
import android.content.Context
open class LinearLayout:View() {
 var gravity=0
 fun addView(v:View) {children.add(v)}
 fun addView(v:View,p:LayoutParams) {children.add(v)}
 fun removeAllViews() {children.clear()}
 class LayoutParams(val width:Int,val height:Int) {companion object { const val MATCH_PARENT = -1; const val WRAP_CONTENT = -2}}
}
class ScrollView:View() { var scrollY=0; var fullScrolls=0; fun fullScroll(direction:Int):Boolean {fullScrolls++;return true} }
class TextView(context:Context):View() {var text:CharSequence?="";var textSize=14f;var gravity=0;var maxWidth=0;var color=0;fun setTextColor(c:Int){color=c}; fun setPadding(a:Int,b:Int,c:Int,d:Int){} }
