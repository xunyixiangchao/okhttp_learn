package com.lis.okhttp_learn;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Created by lis on 2020/8/7.
 */
public class SecondJavaActivity extends AppCompatActivity {
    MyThread finalMyThread = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final Handler[] myHandler = {null};

        finalMyThread = new MyThread(new Runnable() {
            @Override
            public void run() {
                final long id = finalMyThread.getId();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                myHandler[0] = new Handler(Looper.getMainLooper()) {
                    @Override
                    public void handleMessage(@NonNull Message msg) {
                        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
                            System.out.println("handleMessageID:" + true);
                        }
                        super.handleMessage(msg);
                        Toast.makeText(SecondJavaActivity.this, "handleMessage" + id, Toast.LENGTH_LONG).show();
                    }
                };
                myHandler[0].sendEmptyMessage(0);
            }
        });
        finalMyThread.start();

    }


    static class MyThread extends Thread {

        public MyThread(Runnable runnable) {
            super(runnable);
        }
    }
}
