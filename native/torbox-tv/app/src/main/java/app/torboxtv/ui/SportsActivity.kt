package app.torboxtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import app.torboxtv.data.SportsClient
import app.torboxtv.data.SportsBrowse
import app.torboxtv.data.SportsItem
import app.torboxtv.data.SportsPolicy
import app.torboxtv.data.StateStore
import app.torboxtv.data.BuffStreams
import app.torboxtv.data.BuffStreamsPolicy
import kotlinx.coroutines.*

/** Native remote-friendly sports catalog, usable before TorBox setup. */
class SportsActivity : Activity() {
    private val bg = Color.rgb(9,14,25)
    private val ink = Color.rgb(244,245,250)
    private val accent = Color.rgb(102,209,196)
    private val muted = Color.rgb(164,172,193)
    private var mode = "live"
    private var sport = "all"
    private var provider = "all"
    private var query = ""
    private var page = 0
    private var items = emptyList<SportsItem>()
    private lateinit var grid: LinearLayout
    private lateinit var label: TextView
    private lateinit var sportButton: Button
    private lateinit var providerButton: Button
    private lateinit var store: StateStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var refreshing: Job?=null
    private var refreshGeneration=0
    private var providerStatus=""
    private fun dp(v:Int) = (resources.displayMetrics.density*v).toInt()
    private fun text(value:String,size:Int=16,color:Int=ink,bold:Boolean=false) = TextView(this).apply { text=value;textSize=size.toFloat();setTextColor(color);if(bold)setTypeface(null,Typeface.BOLD) }
    private fun button(value:String, action:()->Unit) = Button(this).apply {
        text=value;contentDescription=value;isAllCaps=false;textSize=14f;setTextColor(ink);setPadding(dp(15),dp(8),dp(15),dp(8))
        setOnClickListener { action() };setOnFocusChangeListener { _,focus -> background=GradientDrawable().apply {setColor(Color.rgb(25,32,48));cornerRadius=dp(10).toFloat();setStroke(dp(if(focus)3 else 1),if(focus)accent else Color.rgb(52,64,84))} };background=GradientDrawable().apply {setColor(Color.rgb(25,32,48));cornerRadius=dp(10).toFloat()}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);store=StateStore(this)
        if(store.currentProfile().kidMode){finish();return}
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(dp(32),dp(18),dp(32),dp(16))}
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        head.addView(text("Sports",28,ink,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(button("Refresh games") {refreshGames()}.apply{tag="sports-refresh"})
        head.addView(button("Source address") {sourceAddress()})
        head.addView(button("Back") {finish()});root.addView(head)
        val filters=LinearLayout(this)
        filters.addView(button("Live channels") {mode="live";page=0;render()},LinearLayout.LayoutParams(dp(160),dp(46)))
        filters.addView(button("Replays") {mode="replay";page=0;render()},LinearLayout.LayoutParams(dp(130),dp(46)).apply {leftMargin=dp(8)})
        sportButton=button("All sports") {
            val values=listOf("all")+SportsBrowse.sports(items)
            AlertDialog.Builder(this).setTitle("Sport").setSingleChoiceItems(values.map{if(it=="all")"All sports" else SportsBrowse.sportName(it)}.toTypedArray(),values.indexOf(sport)){dialog,which ->sport=values[which];page=0;dialog.dismiss();render()}.show()
        }
        filters.addView(sportButton,LinearLayout.LayoutParams(dp(180),dp(46)).apply {leftMargin=dp(8)})
        providerButton=button("All providers") {
            val values=listOf("all")+SportsBrowse.providers(items)
            AlertDialog.Builder(this).setTitle("Provider").setSingleChoiceItems(values.map{if(it=="all")"All providers" else it}.toTypedArray(),values.indexOf(provider)){dialog,which ->provider=values[which];page=0;dialog.dismiss();render()}.show()
        }
        filters.addView(providerButton,LinearLayout.LayoutParams(dp(185),dp(46)).apply {leftMargin=dp(8)})
        root.addView(filters)
        val liveTab=filters.getChildAt(0)
        root.addView(EditText(this).apply {
            tag="sports:search"
            hint="Search sports, channels and events";contentDescription=hint;setTextColor(ink);setHintTextColor(muted);textSize=15f;setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            this.filters=arrayOf(android.text.InputFilter.LengthFilter(80))
            setOnKeyListener { _,key,event ->
                if(event.action!=android.view.KeyEvent.ACTION_DOWN) false else when(key){
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> liveTab.requestFocus()
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if(::grid.isInitialized)firstRemoteFocus(grid)?.requestFocus()==true else false
                    else -> false
                }
            }
            addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s?.toString().orEmpty();page=0;if(::grid.isInitialized)render()}
                override fun afterTextChanged(s:Editable?)=Unit
            })
        },LinearLayout.LayoutParams(-1,dp(42)).apply {topMargin=dp(7)})
        label=text("",13,muted).apply {setPadding(0,dp(9),0,dp(10))};root.addView(label)
        val scroll=ScrollView(this);grid=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        try {items=SportsClient().catalog(this);render()} catch(_:Exception){label.text="Sports could not load. Close and reopen Sports to try again."}
        filters.getChildAt(0).requestFocus()
        intent.getStringExtra("phoneQuery")?.let { searchFromPhone(it) }
    }
    internal fun searchFromPhone(value:String) {
        if(store.currentProfile().kidMode)return
        window.decorView.findViewWithTag<EditText>("sports:search")?.apply {setText(value.take(80));setSelection(text.length);requestFocus()}
    }
    private fun firstRemoteFocus(view:View):View?{
        if(view.visibility!=View.VISIBLE||!view.isEnabled)return null
        if(view.isFocusable)return view
        if(view is android.view.ViewGroup)for(i in 0 until view.childCount)firstRemoteFocus(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun render(){
        val focusedId=(currentFocus?.tag as? String)?.takeIf{items.any{item->item.id==it}}
        sportButton.text=if(sport=="all")"All sports" else SportsBrowse.sportName(sport)
        providerButton.text=if(provider=="all")"All providers" else provider
        val selected=SportsBrowse.select(items,mode,sport,provider,query)
        val pages=((selected.size+47)/48).coerceAtLeast(1)
        page=page.coerceIn(0,pages-1)
        label.text="${selected.size} ${if(mode=="live")"channels and game streams" else "replays"}"+if(providerStatus.isEmpty())"" else "\n$providerStatus"
        grid.removeAllViews()
        if(selected.isEmpty()){grid.addView(text("No matches. Try All sports, All providers, a different search or the other tab.",16,muted));return}
        val visible=selected.drop(page*48).take(48)
        for(chunk in visible.chunked(3)){
            val row=LinearLayout(this)
            for(item in chunk){
                val bookmarks=getSharedPreferences("sports-resume",MODE_PRIVATE)
                val resume=SportsPolicy.resume(bookmarks.getLong("${item.id}:position",0),bookmarks.getLong("${item.id}:duration",0))
                val subtitle=listOf(SportsBrowse.sportName(item.sport),SportsBrowse.provider(item),item.date,if(item.kind=="replay"&&resume>0)"Resume available" else "").filter {it.isNotBlank()}.joinToString(" · ")
                row.addView(button((if(item.sources.any{it.type=="buffstreams"})"GAME STREAM" else if(item.kind=="live")"LIVE CHANNEL" else "FULL REPLAY")+"\n\n"+item.title+"\n\n"+subtitle){
                    if(store.currentProfile().kidMode){finish();return@button}
                    startActivity(Intent(this,SportsPlayerActivity::class.java).putExtra("sportsId",item.id))
                }.apply {tag=item.id;gravity=Gravity.START or Gravity.CENTER_VERTICAL;textSize=16f},LinearLayout.LayoutParams(0,dp(152),1f).apply {rightMargin=dp(12);bottomMargin=dp(12)})
            }
            if(chunk.size<3)repeat(3-chunk.size){row.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply {rightMargin=dp(12)})}
            grid.addView(row)
        }
        if(pages>1){
            val navigation=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            fun changePage(next:Int){page=next;render();SportsBrowse.select(items,mode,sport,provider,query).getOrNull(page*48)?.id?.let{grid.findViewWithTag<View>(it)?.requestFocus()}}
            navigation.addView(button("Previous page"){changePage(page-1)}.apply{isEnabled=page>0},LinearLayout.LayoutParams(dp(165),dp(46)))
            navigation.addView(text("${page+1} / $pages",14,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(105),dp(46)))
            navigation.addView(button("Next page"){changePage(page+1)}.apply{isEnabled=page<pages-1},LinearLayout.LayoutParams(dp(165),dp(46)))
            grid.addView(navigation)
        }
        if(focusedId!=null)(grid.findViewWithTag<View>(focusedId) ?: window.decorView.findViewWithTag<View>("sports-refresh"))?.requestFocus()
    }
    private fun refreshGames(){
        if(store.currentProfile().kidMode){finish();return}
        refreshing?.cancel();val current=++refreshGeneration;providerStatus="Updating Buffstreams games…";render()
        refreshing=scope.launch{
            try{
                val games=BuffStreams().catalog(this@SportsActivity)
                if(current!=refreshGeneration||store.currentProfile().kidMode)return@launch
                items=SportsClient().catalog(this@SportsActivity)
                providerStatus=if(games.isEmpty())"No Buffstreams games are listed now." else "${games.size} game streams · No scores shown · Live games only"
                render()
            }catch(e:CancellationException){if(e is TimeoutCancellationException&&current==refreshGeneration){providerStatus="Game listings took too long. Existing channels remain available. Press Refresh games.";render()}else throw e}
            catch(_:Exception){if(current==refreshGeneration){providerStatus="Game listings could not update. Existing channels remain available. Try Refresh games or Source address.";render()}}
        }
    }
    private fun sourceAddress(){
        if(store.currentProfile().kidMode){finish();return}
        val input=EditText(this).apply{setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;setText(BuffStreams.configuredBase(this@SportsActivity))}
        val dialog=AlertDialog.Builder(this).setTitle("Buffstreams address").setMessage("If the site moves, enter its current HTTPS address here. This changes game listings only.").setView(input).setNegativeButton("Cancel",null).setNeutralButton("Use default"){_,_->BuffStreams.setBase(this,BuffStreamsPolicy.DEFAULT_BASE);items=SportsClient().catalog(this);refreshGames()}.setPositiveButton("Save",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{BuffStreams.setBase(this,input.text.toString());items=SportsClient().catalog(this);dialog.dismiss();refreshGames()}catch(_:Exception){input.error="Enter an HTTPS Buffstreams address, such as https://mybuffstreams.plus, without a page path."}
        }}
        dialog.show()
    }
    override fun onStart(){super.onStart();if(::grid.isInitialized)refreshGames()}
    override fun onStop(){refreshGeneration++;refreshing?.cancel();super.onStop()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    override fun onResume(){super.onResume();if(::store.isInitialized&&store.currentProfile().kidMode)finish() else if(::grid.isInitialized&&items.isNotEmpty())render()}
}
