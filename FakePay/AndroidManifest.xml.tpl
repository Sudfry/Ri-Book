<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="__PKG__">

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="__LABEL__"
        android:theme="@style/AppTheme">
        <activity
            android:name="com.example.fakepay.FakePayActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
