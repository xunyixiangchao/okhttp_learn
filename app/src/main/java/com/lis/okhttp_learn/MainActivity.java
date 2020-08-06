package com.lis.okhttp_learn;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;

import javax.annotation.Nullable;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder().url("http://www.baidu.com").build();
        final Call call = okHttpClient.newCall(request);
        KTest.INSTANCE.test(this);
        KTest.jvmClass();
        JvmClass.jvmClass();
        StaticClass.Companion.staticName();
        try {
            Response execute = call.execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {

            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {

            }
        });
        TextView textView = findViewById(R.id.text);

        //设置是否允许重试 默认是允许
        new OkHttpClient().newBuilder().retryOnConnectionFailure(true);

        //设置身份校验的代理
        new OkHttpClient.Builder().proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                "localhost", 8080
        )))
        //设置身份校验(默认不设置这个）
        .proxyAuthenticator(new Authenticator() {
            @Nullable
            @Override
            public Request authenticate(Route route, Response response) throws IOException {
                //参照Authenticator接口注释
                return response.request().newBuilder()
                        .header("Proxy-Authorization", Credentials.basic("用户名","密码"))
                        .build();
            }
        }).build();


    }
}
