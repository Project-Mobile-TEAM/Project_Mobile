package com.utechat.ui.contacts;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorageRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Bằng chứng của ca focus, dùng test-storage có sẵn để Gradle lấy file trước khi gỡ APK test. */
final class ContactsUiDiagnostics {
    private ContactsUiDiagnostics() { }
    static void capture(ActivityScenario<ContactsUiTestActivity> scenario,Throwable failure) {
        if(!"true".equals(InstrumentationRegistry.getArguments().getString("phase4UiDiagnostics"))) return;
        try {
            var instrumentation=InstrumentationRegistry.getInstrumentation();
            try(PrintWriter out=new PrintWriter(new OutputStreamWriter(output("failure.txt"),StandardCharsets.UTF_8))) { failure.printStackTrace(out); }
            // Ảnh lấy trước các lệnh dumpsys để không chụp nhầm màn hình sau cleanup.
            try {
                Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();
                if(screen!=null) {
                    try(OutputStream out=output("screen.png")) {
                        if(!screen.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Không mã hóa được ảnh PNG");
                    } finally { screen.recycle(); }
                } else write("screen-error.txt","UiAutomation.takeScreenshot trả null");
            } catch(Exception captureFailure) { write("screen-error.txt",captureFailure.toString()); }
            StringBuilder state=new StringBuilder("utcMillis="+System.currentTimeMillis()+"\n");
            if(scenario!=null) {
                state.append("scenario=").append(scenario.getState()).append('\n');
                scenario.onActivity(a->{
                    var view=a.getWindow().getDecorView();
                    state.append("activity=").append(a.getClass().getName()).append('\n')
                            .append("activityFocus=").append(a.hasWindowFocus()).append('\n')
                            .append("decorFocus=").append(view.hasWindowFocus()).append('\n')
                            .append("attached=").append(view.isAttachedToWindow()).append('\n')
                            .append("laidOut=").append(view.isLaidOut()).append('\n')
                            .append("layoutRequested=").append(view.isLayoutRequested()).append('\n')
                            .append("size=").append(view.getWidth()).append('x').append(view.getHeight()).append('\n')
                            .append("windowFlags=").append(a.getWindow().getAttributes().flags).append('\n');
                });
            }
            write("activity.txt",state.toString());
            // Chỉ giữ trạng thái cửa sổ/Activity, không dump Intent, token hay dữ liệu nhập của ứng dụng khác.
            shell("window.txt","dumpsys window",s->s.contains("mCurrentFocus=")||s.contains("mFocusedApp=")||s.contains("mTopFocusedDisplayId=")||s.contains("isKeyguardShowing="));
            shell("activities.txt","dumpsys activity activities",s->s.contains("topResumedActivity=")||s.contains("mResumedActivity="));
            shell("power.txt","dumpsys power",s->s.contains("mWakefulness=")||s.contains("mInteractive="));
            shell("logcat.txt","logcat -d -v threadtime --pid="+android.os.Process.myPid()+" -t 800 AndroidRuntime:E TestRunner:I ActivityScenario:V LifecycleMonitor:D HWUI:W SurfaceSyncGroup:W *:S",s->true);
        } catch(Exception diagnosticFailure) {
            // Chẩn đoán lỗi cũng phải được ghi nhận nhưng không thay lỗi của testcase bằng lỗi thu thập.
            failure.addSuppressed(diagnosticFailure);
            android.util.Log.e("ContactsUiDiagnostics","Không thu thập đủ bằng chứng",diagnosticFailure);
        }
    }
    private static OutputStream output(String name) throws IOException {
        return PlatformTestStorageRegistry.getInstance().openOutputFile("contacts-ui-focus/"+name);
    }
    private static void write(String name,String text) throws IOException {
        try(OutputStream out=output(name)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private static void shell(String name,String command,Predicate<String> keep) throws IOException {
        var descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(descriptor),StandardCharsets.UTF_8));
                PrintWriter out=new PrintWriter(new OutputStreamWriter(output(name),StandardCharsets.UTF_8))) {
            String line;while((line=in.readLine())!=null) if(keep.test(line)) out.println(line);
        }
    }
}
