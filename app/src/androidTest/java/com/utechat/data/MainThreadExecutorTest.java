package com.utechat.data;

import static org.junit.Assert.*;

import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Kiểm Looper thật: chạy ngay trên main và chuyển từ luồng nền theo FIFO. */
public class MainThreadExecutorTest {
    @Test public void mainThreadRunsInlineBeforeReturning() {
        List<Integer> order=new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            order.add(1);
            MainThreadExecutor.INSTANCE.execute(() -> order.add(2));
            order.add(3);
        });
        assertEquals(List.of(1,2,3),order);
    }

    @Test public void backgroundDispatchRunsOnMainInFifoOrder() throws Exception {
        assertNotEquals(Looper.getMainLooper(),Looper.myLooper());
        List<Integer> order=new ArrayList<>(); List<Looper> loopers=new ArrayList<>();
        CountDownLatch done=new CountDownLatch(2);
        for(int value:List.of(1,2)) MainThreadExecutor.INSTANCE.execute(() -> {
            order.add(value); loopers.add(Looper.myLooper()); done.countDown();
        });
        assertTrue(done.await(3,TimeUnit.SECONDS));
        assertEquals(List.of(1,2),order);
        assertEquals(List.of(Looper.getMainLooper(),Looper.getMainLooper()),loopers);
    }
}
