package local.hackrf.controller;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;

/** Visual smoke check only. Never connects, generates or starts output. */
public class UiDesignCheck extends Instrumentation {
    private void checkedMain(Runnable action){
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("UI check failed",failure.get());
    }

    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(TransmitService.busy)throw new IllegalStateException("Task active");
        Activity app=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        Field routes=MainActivity.class.getDeclaredField("routes");routes.setAccessible(true);
        if(!java.util.Arrays.equals((String[])routes.get(app),new String[]{"stroll","office","roadside","wander"}))throw new AssertionError("Only four person scenes may be offered");
        Field extras=MainActivity.class.getDeclaredField("extra");extras.setAccessible(true);
        java.util.Map<String,android.widget.EditText> fields=(java.util.Map<String,android.widget.EditText>)extras.get(app);
        android.view.View first=fields.get("walkMinimum"),second=fields.get("walkMaximum");
        if(first.getParent().getParent()!=second.getParent().getParent())throw new AssertionError("Motion pair is not in one row");
        Field random=MainActivity.class.getDeclaredField("randomHeading");random.setAccessible(true);
        android.view.View toggle=(android.view.View)random.get(app);
        Field fixed=MainActivity.class.getDeclaredField("heading");fixed.setAccessible(true);
        android.view.View fixedRow=(android.view.View)((android.view.View)fixed.get(app)).getParent().getParent();
        android.view.ViewGroup movement=(android.view.ViewGroup)toggle.getParent();
        android.view.View speedRow=(android.view.View)fields.get("speedVariation").getParent().getParent();
        if(movement.indexOfChild(speedRow)>=movement.indexOfChild(toggle) || movement.indexOfChild(fixedRow)<=movement.indexOfChild(toggle))
            throw new AssertionError("Heading fields must follow random-heading toggle, after speed row");
        Field location=MainActivity.class.getDeclaredField("locationMap"),places=MainActivity.class.getDeclaredField("placesPage"),monitor=MainActivity.class.getDeclaredField("monitorMap");for(Field f:new Field[]{location,places,monitor})f.setAccessible(true);
        if(((android.view.View)location.get(app)).getParent().getParent()!=places.get(app) || !(monitor.get(app) instanceof LocationMap))throw new AssertionError("Place editor must be in Places, monitor map must remain available");
        Field coords=MainActivity.class.getDeclaredField("homeCoordinates");coords.setAccessible(true);
        if(((android.widget.TextView)coords.get(app)).getPaddingTop()==0)throw new AssertionError("Missing coordinate spacing");
        Field map=MainActivity.class.getDeclaredField("monitorMap");map.setAccessible(true);if(!(map.get(app) instanceof LocationMap))throw new AssertionError("Missing map");
        checkedMain(()->{
            int gap=(int)Math.ceil(8*app.getResources().getDisplayMetrics().density);
            for(android.widget.EditText field:fields.values()){
                if(!(field.getBackground() instanceof android.graphics.drawable.StateListDrawable) || field.getPaddingTop()<gap || field.getPaddingBottom()<gap)
                    throw new AssertionError("Numeric field must have separated border and font padding");
                android.graphics.Paint.FontMetrics metrics=field.getPaint().getFontMetrics();
                int needed=(int)Math.ceil(metrics.bottom-metrics.top)+field.getPaddingTop()+field.getPaddingBottom();
                if(field.getMinHeight()<needed)throw new AssertionError("Numeric field may clip scaled font");
            }
        });
        checkedMain(()->{try{
            Field preview=MainActivity.class.getDeclaredField("preview");preview.setAccessible(true);Object plot=preview.get(app);
            Method input=MainActivity.class.getDeclaredMethod("input");input.setAccessible(true);String before=input.invoke(app).toString();
            Method setZoom=plot.getClass().getDeclaredMethod("setZoom",double.class);setZoom.setAccessible(true);
            Field zoom=plot.getClass().getDeclaredField("zoom");zoom.setAccessible(true);
            for(double[] pair:new double[][]{{32,16},{.1,.25},{2,2},{1,1}}){setZoom.invoke(plot,pair[0]);if(zoom.getDouble(plot)!=pair[1])throw new AssertionError("Zoom bounds/reset");}
            android.view.View view=(android.view.View)plot;
            for(int action:new int[]{0,2,1}){
                android.view.MotionEvent event=android.view.MotionEvent.obtain(0,10,action,action==0?30:80,action==0?40:100,0);
                view.dispatchTouchEvent(event);event.recycle();
            }
            Field panX=plot.getClass().getDeclaredField("panX"),panY=plot.getClass().getDeclaredField("panY");panX.setAccessible(true);panY.setAccessible(true);
            if(panX.getFloat(plot)!=50 || panY.getFloat(plot)!=60)throw new AssertionError("Path drag did not move both axes");
            Method reset=plot.getClass().getDeclaredMethod("resetView");reset.setAccessible(true);reset.invoke(plot);
            if(panX.getFloat(plot)!=0 || panY.getFloat(plot)!=0 || zoom.getDouble(plot)!=1)throw new AssertionError("Full view did not reset pan and zoom");
            if(!before.equals(input.invoke(app).toString()))throw new AssertionError("Preview zoom changed scenario");
        }catch(Exception e){throw new RuntimeException(e);}});
        checkedMain(()->{try{
            Field name=MainActivity.class.getDeclaredField("verifiedDeviceName"),report=MainActivity.class.getDeclaredField("verifiedDeviceReport");name.setAccessible(true);report.setAccessible(true);
            String previousName=(String)name.get(null),previousReport=(String)report.get(null);
            Method render=MainActivity.class.getDeclaredMethod("renderConnection",int.class,String.class,boolean.class);render.setAccessible(true);
            Field button=MainActivity.class.getDeclaredField("connect"),details=MainActivity.class.getDeclaredField("deviceDetails");button.setAccessible(true);details.setAccessible(true);
            try{
                String sample="板卡 ID：2\n固件：test\n端点 0x02 / OUT";name.set(null,"test-device");report.set(null,sample);render.invoke(app,1,"test-device",true);
                if(!((android.widget.Button)button.get(app)).getText().toString().equals("已连接") || !((android.widget.TextView)details.get(app)).getText().toString().equals(sample))throw new AssertionError("Connected details not shown below button");
                render.invoke(app,1,"test-device",false);
                if(!((String)name.get(null)).isEmpty() || ((android.widget.Button)button.get(app)).getText().toString().equals("已连接"))throw new AssertionError("Revoked USB still connected");
                render.invoke(app,0,"",false);
                if(((android.widget.Button)button.get(app)).isEnabled() || ((android.view.View)details.get(app)).getVisibility()!=android.view.View.GONE)throw new AssertionError("Detached USB retained details");
            }finally{name.set(null,previousName);report.set(null,previousReport);Method update=MainActivity.class.getDeclaredMethod("updateConnection");update.setAccessible(true);update.invoke(app);}
        }catch(Exception e){throw new RuntimeException(e);}});
        checkedMain(()->{try{
            Field home=MainActivity.class.getDeclaredField("homePage"),device=MainActivity.class.getDeclaredField("devicePage"),run=MainActivity.class.getDeclaredField("runSummary"),connect=MainActivity.class.getDeclaredField("connect");
            for(Field f:new Field[]{home,device,run,connect})f.setAccessible(true);
            android.view.ViewGroup homeView=(android.view.ViewGroup)home.get(app);
            if(((android.view.View)run.get(app)).getParent().getParent()!=homeView.getChildAt(0))throw new AssertionError("Run status must be first home card");
            if(((android.view.View)connect.get(app)).getParent().getParent()!=device.get(app))throw new AssertionError("Connection must be on separate device page");
        }catch(Exception e){throw new RuntimeException(e);}});
        checkedMain(()->{String oldStatus=TransmitService.status;boolean oldBusy=TransmitService.busy;
            long oldDone=TransmitService.completedBytes,oldExpected=TransmitService.expectedBytes,oldStarted=TransmitService.taskStartedElapsedMs;
            try{
                Method refresh=MainActivity.class.getDeclaredMethod("refreshTx");refresh.setAccessible(true);
                Field summary=MainActivity.class.getDeclaredField("runSummary"),details=MainActivity.class.getDeclaredField("progressDetails"),history=MainActivity.class.getDeclaredField("downloadSummary");
                for(Field f:new Field[]{summary,details,history})f.setAccessible(true);
                if(((android.view.View)history.get(app)).getVisibility()!=android.view.View.GONE)throw new AssertionError("Download history must start collapsed");
                String[][] stages={{"正在准备场景","正在准备场景"},{"正在下载导航数据 50%","正在准备场景"},{"等待计划时刻","等待计划时刻"},{"正在发送信号","正在模拟"},{"I/Q 发送完成","模拟已完成"},{"发送未完成：设备断开","运行未完成"}};
                for(int i=0;i<stages.length;i++){
                    TransmitService.busy=i<4;TransmitService.status=stages[i][0];TransmitService.taskStartedElapsedMs=SystemClock.elapsedRealtime()-12000;
                    TransmitService.expectedBytes=i>=2?900000000:0;TransmitService.completedBytes=i>=3?450000000:0;
                    refresh.invoke(app);
                    if(!((android.widget.TextView)summary.get(app)).getText().toString().equals(stages[i][1]))throw new AssertionError("Wrong phase: "+stages[i][0]);
                    if(i==3&&!((android.widget.TextView)details.get(app)).getText().toString().contains("50.0%"))throw new AssertionError("Missing formatted progress");
                }
            }catch(Exception failure){throw new RuntimeException(failure);}
            finally{TransmitService.status=oldStatus;TransmitService.busy=oldBusy;TransmitService.completedBytes=oldDone;TransmitService.expectedBytes=oldExpected;TransmitService.taskStartedElapsedMs=oldStarted;}
        });
        checkedMain(()->{try{
            Field choices=MainActivity.class.getDeclaredField("signalButtons"),start=MainActivity.class.getDeclaredField("localStart"),generate=MainActivity.class.getDeclaredField("localGenerate");for(Field f:new Field[]{choices,start,generate})f.setAccessible(true);
            android.widget.ToggleButton[] buttons=(android.widget.ToggleButton[])choices.get(app);
            buttons[1].setChecked(true);
            if(!buttons[0].isChecked()||!buttons[1].isChecked()||((android.view.View)start.get(app)).isEnabled()||((android.view.View)generate.get(app)).isEnabled())throw new AssertionError("Mixed selection must remain selectable and block unsupported generation");
            buttons[0].setChecked(false);if(!((android.widget.Button)start.get(app)).getText().toString().equals("开始北斗模拟"))throw new AssertionError("BDS action label mismatch");
            buttons[1].setChecked(false);if(((android.view.View)start.get(app)).isEnabled())throw new AssertionError("Empty system selection must not start");
            buttons[0].setChecked(true);if(!((android.widget.Button)start.get(app)).getText().toString().equals("开始GPS模拟"))throw new AssertionError("GPS action label mismatch");
        }catch(Exception e){throw new RuntimeException(e);}});
        Method show=MainActivity.class.getDeclaredMethod("showPage",int.class);show.setAccessible(true);
        for(int page:new int[]{0,1,2,3,0}) {final int target=page;java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();checkedMain(()->{try{show.invoke(app,target);Field bar=MainActivity.class.getDeclaredField("actionBar");bar.setAccessible(true);if(((android.view.View)bar.get(app)).getVisibility()!=(target==0?android.view.View.VISIBLE:android.view.View.GONE))throw new AssertionError("Actions must only appear on home");}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new RuntimeException(error.get());waitForIdleSync();SystemClock.sleep(350);
            if(page==2)checkedMain(()->{
                for(android.widget.EditText field:fields.values()){
                    android.graphics.Paint.FontMetrics fm=field.getPaint().getFontMetrics();
                    if(field.getHeight()>0 && field.getBaseline()+fm.descent>field.getHeight()-field.getPaddingBottom()+1)
                        throw new AssertionError("Numeric baseline overlaps lower padding");
                }
                try{
                    Field settings=MainActivity.class.getDeclaredField("settingsPage");settings.setAccessible(true);
                    android.view.ViewGroup container=(android.view.ViewGroup)settings.get(app);
                    for(int i=0;i<container.getChildCount();i++){
                        android.view.View card=container.getChildAt(i);if(card.getWidth()==0||card.getHeight()==0)continue;
                        Bitmap frame=Bitmap.createBitmap(card.getWidth(),card.getHeight(),Bitmap.Config.ARGB_8888);card.draw(new android.graphics.Canvas(frame));
                        try(OutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"ui-settings-card-"+i+".png"))){frame.compress(Bitmap.CompressFormat.PNG,100,out);}frame.recycle();
                    }
                }catch(Exception e){throw new RuntimeException(e);}
            });
            if(!app.hasWindowFocus())throw new IOException("App is not foreground; screenshot skipped");
            Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("Screenshot unavailable");try(OutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),"ui-page-"+page+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        }
        result.putString("stream","PASS: simulation/location/settings/device pages rendered with home-only start-stop controls; screenshots in private cache; no USB/RF");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
