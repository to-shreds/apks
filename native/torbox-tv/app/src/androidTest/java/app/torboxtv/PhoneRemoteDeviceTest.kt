package app.torboxtv

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.EditText
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import app.torboxtv.core.Profile
import app.torboxtv.core.Settings
import app.torboxtv.data.RemoteCommand
import app.torboxtv.data.StateStore
import app.torboxtv.ui.MainActivity
import app.torboxtv.ui.TorBoxApplication
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URI

@RunWith(AndroidJUnit4::class)
class PhoneRemoteDeviceTest {
    @get:Rule val rule=ActivityTestRule(MainActivity::class.java,false,false)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context:Context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as TorBoxApplication
    private val store get()=StateStore(context)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    @Before fun reset(){
        main{app.phone.disconnect()}
        for(name in listOf("torbox_tv_state_v1","torbox_tv_usage_v1","torbox_tv_updates_v1"))assertTrue(context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit())
    }
    @After fun clean(){
        main{
            app.phone.disconnect()
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach{it.finish()}
        }
        rule.finishActivity()
    }
    private fun pair(configured:Boolean=false):URI{
        if(configured)store.saveKey("phone-device-fixture-not-a-real-account")
        rule.launchActivity(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
        main{app.phone.showPairing(rule.activity)}
        var url:String?=null;main{url=app.phone.pairingUrl}
        assertNotNull("Native pairing must bind the emulator's private LAN interface",url)
        return URI(url!!)
    }
    private fun request(url:URI,body:String?=null,token:String=url.fragment):String{
        Socket(url.host,url.port).use{socket->
            socket.soTimeout=4000
            val origin="http://${url.host}:${url.port}";val bytes=body?.toByteArray(Charsets.UTF_8)
            val header="${if(body==null)"GET /" else "POST /api"} HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\n"+
                (if(bytes!=null)"Origin: $origin\r\nX-TorBox-Remote: $token\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "")+"\r\n"
            socket.getOutputStream().write(header.toByteArray());if(bytes!=null)socket.getOutputStream().write(bytes)
            return socket.getInputStream().bufferedReader().readText()
        }
    }
    private fun post(url:URI,command:JSONObject):Int=request(url,command.toString()).substringBefore("\r\n").split(' ')[1].toInt()
    private fun hello(url:URI)=assertEquals(200,post(url,JSONObject().put("action","hello")))
    private fun search(url:URI,query:String,sequence:Int,sports:Boolean=false)=post(url,JSONObject().put("action","search").put("scope",if(sports)"sports" else "movies").put("query",query).put("client","device-phone-1234").put("sequence",sequence))
    private fun await(message:String,predicate:()->Boolean){
        val until=SystemClock.elapsedRealtime()+5000
        while(SystemClock.elapsedRealtime()<until){var done=false;main{done=predicate()};if(done)return;SystemClock.sleep(40)}
        fail(message)
    }

    @Test fun displayedQrDecodesToTheActualPairedLocalServer(){
        val url=pair()
        main{
            fun image(view:View):ImageView?{
                if(view is ImageView&&view.contentDescription=="Phone remote pairing QR code")return view
                if(view is ViewGroup)for(i in 0 until view.childCount)image(view.getChildAt(i))?.let{return it}
                return null
            }
            // Dialog is a separate window. Its decor is captured from the manager,
            // without bypassing the actual native QR generation or bitmap decoder.
            val dialog=app.phone.javaClass.getDeclaredField("pairing").apply{isAccessible=true}.get(app.phone) as android.app.AlertDialog
            val bitmap=(image(dialog.window!!.decorView)!!.drawable as BitmapDrawable).bitmap
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))))
            assertEquals(url.toString(),decoded.text)
        }
        val page=request(url)
        assertTrue(page.contains("Type to search on your TV"));assertFalse(page.contains(url.fragment))
        hello(url)
        assertNull(store.key())
    }

    @Test fun phoneTypingUpdatesRealNativeSearchAndRejectsStaleTextWithoutChangingAccountOrSettings(){
        store.saveSettings(Settings(maxHeight=1080,rewindSeconds=20))
        val url=pair(true);hello(url)
        assertEquals(200,search(url,"xy",2));assertEquals(200,search(url,"x",1))
        main{
            assertEquals("xy",rule.activity.window.decorView.findViewWithTag<EditText>("search:input").text.toString())
        }
        assertEquals("phone-device-fixture-not-a-real-account",store.key())
        assertEquals(20,store.settings().rewindSeconds);assertEquals(1080,store.settings().maxHeight)
        assertEquals(200,post(url,JSONObject().put("action","key").put("key","UP")))
        main{assertEquals("nav:Search",rule.activity.currentFocus?.tag)}
    }

    @Test fun sportsSearchWorksWithoutTorBoxButNeverBypassesKidMode(){
        val url=pair();hello(url)
        assertEquals(200,search(url,"Red Bull",1,true))
        val monitor=ActivityLifecycleMonitorRegistry.getInstance()
        await("Phone sports search must open and populate the actual native Sports screen"){
            monitor.getActivitiesInStage(Stage.RESUMED).any{it.window.decorView.findViewWithTag<EditText>("sports:search")?.text.toString()=="Red Bull"}
        }
        assertNull(store.key())
        store.saveProfile(Profile("viewer-1","Kids",kidMode=true))
        assertEquals(409,search(url,"football",2,true))
        assertTrue(store.currentProfile().kidMode)
        assertNull(store.key())
    }

    @Test fun unpairedPhoneCannotSendKeysAndHomeClosesTheSession(){
        val url=pair();val invalid=request(url,"{\"action\":\"key\",\"key\":\"OK\"}","0".repeat(48))
        assertTrue(invalid.startsWith("HTTP/1.1 403"));assertNull(store.key())
        hello(url)
        assertTrue(UiDevice.getInstance(instrumentation).pressHome())
        await("Leaving TorBox TV must stop the LAN listener and invalidate pairing"){app.phone.pairingUrl==null}
        try{request(url);fail("Background app accepted an old paired connection")}catch(_:java.io.IOException){}
    }

    @Test fun timedOutPhoneCommandCannotRunLaterOnADifferentScreen(){
        val url=pair(true);hello(url)
        val entered=java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post{entered.countDown();SystemClock.sleep(3000)}
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(409,search(url,"must not run later",1))
        instrumentation.waitForIdleSync()
        main{assertNull("Expired commands must not change the screen after the UI unblocks",rule.activity.window.decorView.findViewWithTag<EditText>("search:input"))}
    }
}
