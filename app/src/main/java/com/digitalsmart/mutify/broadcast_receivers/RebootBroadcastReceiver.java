package com.digitalsmart.mutify.broadcast_receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.digitalsmart.mutify.UserDataManager;

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
