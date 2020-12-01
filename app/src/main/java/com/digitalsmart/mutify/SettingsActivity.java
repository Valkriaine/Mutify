package com.digitalsmart.mutify;

import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

public class SettingsActivity extends AppCompatActivity
{

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        WebView privacyPolicyWebView = findViewById(R.id.privacy_policy);
        privacyPolicyWebView.loadUrl("file:///android_asset/privacy_policy.html");
    }



}