package com.lis.okhttp_learn

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Created by lis on 2020/8/6.
 */
class SecondActivity :Activity(){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toast("哈哈");
        val text = findViewById<TextView>(R.id.text)
        text.gTitle()

    }
}