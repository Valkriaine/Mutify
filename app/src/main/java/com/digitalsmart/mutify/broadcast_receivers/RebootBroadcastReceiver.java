package com.digitalsmart.mutify.broadcast_receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.digitalsmart.mutify.R;
import com.digitalsmart.mutify.UserDataManager;

import static com.digitalsmart.mutify.util.Constants.NOTIFICATION_ID;
import static com.digitalsmart.mutify.util.Constants.PACKAGE_NAME;

//restore geofencing after the device reboots
public class RebootBroadcastReceiver extends BroadcastReceiver
{
    @Override
    public void onReceive(Context context, Intent intent)
    {
        Log.d("reboot", intent.getAction());
        new UserDataManager(context).addFencesAfterReboot(context);
    }
}
