package com.lis.okhttp_learn;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.text).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SecondJavaActivity.class));
            }
        });
        new OkHttpClient.Builder().build();
        OkHttpClient okHttpClient = new OkHttpClient();
        //Request主要有：
        //  final HttpUrl url;  请求地址
        //  final String method;  主要GET，POST
        //  final Headers headers; 请求头
        //  final RequestBody body;  请求体，一般用于POST请求--FormBody
        //  final Object tag; //附加  标签，可以用来取消请求的标识，如果没有设置，则用请求本身作为标识来进行取消操作。
        Request request = new Request.Builder().url("http://www.baidu.com").tag("tag").build();
        //创建一个请求Call-》RealCall，在RealCall的构造方法里创建一个重试与重定向拦截器
        Call call = okHttpClient.newCall(request);

        //设置最大请求数--默认5
        //保证客服端性能
        okHttpClient.dispatcher().setMaxRequests(64);
        //保证服务器性能
        okHttpClient.dispatcher().setMaxRequestsPerHost(5);
        try {
            //同步请求
            Response execute = call.execute();

        } catch (IOException e) {
            e.printStackTrace();
        }
        //异步请求
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {

            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {

            }
        });

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
                                       .header("Proxy-Authorization", Credentials.basic("用户名", "密码"))
                                       .build();
                    }
                }).build();

        //自定义拦截器
        new OkHttpClient().newBuilder().addInterceptor(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                // todo: .......
                final Response response = chain.proceed(chain.request());
                // todo: .......
                return response;
            }
        });

    }
}
