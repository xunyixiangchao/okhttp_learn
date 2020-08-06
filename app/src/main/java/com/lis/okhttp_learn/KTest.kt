package com.lis.okhttp_learn

import android.content.Context
import android.widget.TextView
import android.widget.Toast

/**
 * Created by lis on 2020/8/6.
 */
object KTest{

    fun  test(context: Context){
        Toast.makeText(context,"KTest",Toast.LENGTH_LONG).show();
    }
    @JvmStatic
    fun jvmClass(){
    }
}

fun TextView.getTitle():String{
    return "title"
}