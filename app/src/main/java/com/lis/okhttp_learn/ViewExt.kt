package com.lis.okhttp_learn

import android.app.Activity
import android.view.View
import android.widget.Toast

/**
 * Created by lis on 2020/8/6.
 */
fun View.gTitle():CharSequence{
    return "title";
}

fun Activity.toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT){
    Toast.makeText(this, message, duration).show()
}