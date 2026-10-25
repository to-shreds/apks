package app.torboxtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import app.torboxtv.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
class SportsPlayerActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val client=SportsClient()
    private var item:SportsItem?=null
    private var player:ExoPlayer?=null
    private var session:MediaSession?=null
    private var opening:Job?=null
    private var timer:Job?=null
    private var source=0
    private var quality=720
    private var generation=0
    private var desiredPlay=true
    private var receiverRegistered=false
    private val interruptionReceiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){
            if(intent?.action==Intent.ACTION_SCREEN_OFF){requestPlayback(false);save();window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        }
    }
    private var foreground=false
    private var ready=false
    private lateinit var view:PlayerView
    private lateinit var message:TextView
    private lateinit var qualityButton:Button
    private lateinit var sourceButton:Button
    private val media=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    private fun dp(v:Int)=(resources.displayMetrics.density*v).toInt()
    private fun button(value:String,action:()->Unit)=Button(this).apply{text=value;isAllCaps=false;textSize=13f;setOnClickListener{action()}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(StateStore(this).currentProfile().kidMode){finish();return}
        item=try{client.catalog(this).firstOrNull{it.id==intent.getStringExtra("sportsId")}}catch(_:Exception){null}
        if(item==null){finish();return}
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(9,14,25));setPadding(dp(16),dp(7),dp(16),dp(7))}
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(TextView(this).apply{text=item!!.title;textSize=20f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,-2,1f));head.addView(button("Back"){finish()});root.addView(head)
        view=PlayerView(this).apply{useController=true;controllerShowTimeoutMs=4000;setShowNextButton(false);setShowPreviousButton(false);isFocusable=true;isFocusableInTouchMode=true}
        root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        controls.addView(button("Retry"){start(shouldPlay=true)})
        controls.addView(button(if(item!!.kind=="live")"Go live" else "Start over"){
            if(item!!.kind=="live")player?.seekToDefaultPosition() else {getSharedPreferences("sports-resume",MODE_PRIVATE).edit().putLong("${item!!.id}:position",0).apply();player?.seekTo(0);save()};requestPlayback(true)
        })
        sourceButton=button("Source"){AlertDialog.Builder(this).setTitle("Source").setItems(item!!.sources.map{it.label}.toTypedArray()){_,which->val shouldPlay=player?.playWhenReady ?: desiredPlay;source=which;start(shouldPlay)}.show()}
        sourceButton.visibility=if(item!!.sources.size>1)View.VISIBLE else View.GONE;controls.addView(sourceButton)
        qualityButton=button("720p"){AlertDialog.Builder(this).setTitle("Quality").setItems(arrayOf("480p","720p","1080p","Auto")){_,which->quality=listOf(480,720,1080,0)[which];qualityButton.text=if(quality==0)"Auto" else "${quality}p";applyQuality()}.show()};controls.addView(qualityButton)
        controls.addView(button("Audio"){
            val languages=player?.currentTracks?.groups?.filter{it.type==C.TRACK_TYPE_AUDIO}?.flatMap{g->(0 until g.length).mapNotNull{g.getTrackFormat(it).language}}?.distinct().orEmpty()
            if(languages.isEmpty())return@button
            AlertDialog.Builder(this).setTitle("Audio").setItems(languages.map{java.util.Locale.forLanguageTag(it).displayLanguage}.toTypedArray()){_,which->player?.let{it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(languages[which]).build()}}.show()
        })
        controls.addView(button("Other channels"){finish()});root.addView(controls)
        message=TextView(this).apply{text="Opening…";textSize=13f;setTextColor(Color.rgb(164,172,193));setPadding(dp(5),dp(4),0,0)};root.addView(message);setContentView(root);view.requestFocus()
    }
    private fun applyQuality(){player?.let{it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().setMaxVideoSize(if(quality==0)Int.MAX_VALUE else 1920,if(quality==0)Int.MAX_VALUE else quality).setPreferredAudioLanguage("en").build()}}
    private fun save(){
        val selected=item?:return;val p=player?:return
        if(selected.kind!="replay"||!ready||p.duration<=0)return
        getSharedPreferences("sports-resume",MODE_PRIVATE).edit().putLong("${selected.id}:position",p.currentPosition).putLong("${selected.id}:duration",p.duration).apply()
    }
    private fun release(){save();generation++;opening?.cancel();opening=null;timer?.cancel();timer=null;session?.release();session=null;view.player=null;player?.release();player=null;ready=false;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    private fun fail(current:Int,copy:String){if(current!=generation||!foreground)return;desiredPlay=false;timer?.cancel();timer=null;player?.pause();message.text=copy+if((item?.sources?.size ?: 0)>1)" Choose Source, Retry or Other channels." else " Choose Retry or Other channels.";window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    private fun start(shouldPlay:Boolean=desiredPlay){
        if(!foreground||StateStore(this).currentProfile().kidMode){finish();return}
        val selected=item?:return;desiredPlay=shouldPlay;release();val current=generation;message.text="Opening ${if(selected.kind=="live")"live channel" else "replay"}…"
        opening=scope.launch{
            try{
                val stream=withTimeout(14000){client.prepare(selected.sources[source])}
                if(!foreground||current!=generation)return@launch
                stream.alternatives?.let{item=selected.copy(sources=it);source=stream.sourceIndex;sourceButton.visibility=if(it.size>1)View.VISIBLE else View.GONE}
                val selectedSource=item!!.sources[source]
                val transport=media.newBuilder().apply { if(stream.playbackScope!=null) dns(SportsPublicDns()) }.addNetworkInterceptor(SportsMediaGuard(stream.playbackScope)).build()
                val p=ExoPlayer.Builder(this@SportsPlayerActivity).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(transport).setDefaultRequestProperties(stream.headers))).build()
                player=p;view.player=p;applyQuality();p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);p.setHandleAudioBecomingNoisy(true)
                p.addListener(object:Player.Listener{
                    override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){if(current==generation)desiredPlay=playWhenReady}
                    override fun onIsPlayingChanged(isPlaying:Boolean){if(current!=generation)return;if(isPlaying)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                    override fun onPlaybackStateChanged(state:Int){
                        if(current!=generation)return
                        when(state){
                            Player.STATE_READY->{ready=true;message.text=(if(selected.kind=="live")"Live" else "Replay")+" · "+selectedSource.label}
                            Player.STATE_BUFFERING->message.text="Buffering…"
                            Player.STATE_ENDED->{save();message.text="Replay finished."}
                            Player.STATE_IDLE->Unit
                        }
                    }
                    override fun onPlayerError(error:PlaybackException){fail(current,SportsPlaybackFailure.message(error))}
                })
                session=MediaSession.Builder(this@SportsPlayerActivity,p).build()
                p.setMediaItem(MediaItem.Builder().setUri(stream.url).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build())
                if(selected.kind=="replay"){
                    val prefs=getSharedPreferences("sports-resume",MODE_PRIVATE)
                    val position=SportsPolicy.resume(prefs.getLong("${selected.id}:position",0),prefs.getLong("${selected.id}:duration",0))
                    if(position>0)p.seekTo(position)
                }
                p.prepare();p.playWhenReady=desiredPlay
                timer=scope.launch{
                    val started=android.os.SystemClock.elapsedRealtime();var bufferingAt=0L
                    while(isActive&&current==generation){
                        delay(1000);save();val now=android.os.SystemClock.elapsedRealtime()
                        if(!ready&&now-started>25000){fail(current,"The source did not start.");p.stop();break}
                        if(p.playWhenReady&&p.playbackState==Player.STATE_BUFFERING){if(bufferingAt==0L)bufferingAt=now;if(ready&&now-bufferingAt>20000){fail(current,"The stream stopped responding.");p.stop();break}}else bufferingAt=0
                    }
                }
            }catch(e:CancellationException){if(e is TimeoutCancellationException)fail(current,"The source took too long to respond.") else throw e}
            catch(e:Exception){fail(current,if(e is java.io.IOException)e.message.orEmpty() else "The source is unavailable.")}
        }
    }
    private fun requestPlayback(shouldPlay:Boolean){
        if(shouldPlay&&(!foreground||StateStore(this).currentProfile().kidMode))return
        desiredPlay=shouldPlay
        player?.playWhenReady=shouldPlay
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(event.action==KeyEvent.ACTION_DOWN){
            when(event.keyCode){
                KeyEvent.KEYCODE_MEDIA_PLAY->{requestPlayback(true);return true}
                KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_STOP->{requestPlayback(false);return true}
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_HEADSETHOOK->{if(event.repeatCount==0)requestPlayback(!desiredPlay);return true}
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onStart(){
        super.onStart();foreground=true
        if(::view.isInitialized&&item!=null){
            val interrupts=IntentFilter(Intent.ACTION_SCREEN_OFF)
            if(Build.VERSION.SDK_INT>=33)registerReceiver(interruptionReceiver,interrupts,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(interruptionReceiver,interrupts)
            receiverRegistered=true
            start()
        }
    }
    override fun onStop(){
        foreground=false;desiredPlay=false
        if(receiverRegistered){unregisterReceiver(interruptionReceiver);receiverRegistered=false}
        if(::view.isInitialized)release()
        super.onStop()
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
